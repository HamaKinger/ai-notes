package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ainotes.note.NoteRepository;
import com.ainotes.search.RetrievalPort;
import com.ainotes.search.RetrievedChunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.support.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 一轮问答的完整流程:事件顺序、落库内容、引用、降级。
 *
 * <p>测的是"流式 + 落库"这段最容易出错、又最难靠手点发现的部分:
 * 事件漏了 {@code done}、答案没回填、引用编号与 prompt 里的对不上、
 * 或者检索挂了整个问答跟着挂 —— 这些在界面上都只是"感觉哪里不对"。
 */
class ChatServiceTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NOTE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final CitationRepository citations = mock(CitationRepository.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final RetrievalPort retrieval = mock(RetrievalPort.class);
    private final AgentTraceService agentTraces = mock(AgentTraceService.class);

    private ChatService service;

    @BeforeEach
    void setUp() {
        Conversation conversation = new Conversation(WORKSPACE, null, null);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION);
        when(conversations.findById(CONVERSATION)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(call -> call.getArgument(0));

        // 主键平时由数据库生成,这里手动补上 —— 事件里要带 messageId
        when(messages.save(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            if (message.getId() == null) {
                ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            }
            return message;
        });
        when(messages.findByConversationIdOrderByCreatedAtDescIdAsc(any(), any())).thenReturn(List.of());

        service = new ChatService(conversations, messages, citations, agentTraces, notes, retrieval, chatClient(),
                new TransactionTemplate(new ResourcelessTransactionManager()), 3, 4, "test-model");
    }

    private ChatClient chatClient() {
        return ChatClient.builder(model).build();
    }

    private TestChatModel model = TestChatModel.streaming("你好", "，世界");

    private static java.util.List<String> names(List<ChatEvent> events) {
        return events.stream().map(ChatEvent::name).toList();
    }

    private static RetrievedChunk chunk(String id, String noteTitle, String heading, String content) {
        return new RetrievedChunk(id, NOTE, noteTitle, heading, content, 0.42);
    }

    @Test
    void streamsMetaCitationsTokensThenDoneAndPersistsTheAnswer() {
        when(retrieval.retrieve(any())).thenReturn(List.of(
                chunk("c1", "架构设计", "检索层", "第一段"),
                chunk("c2", "检索设计", null, "第二段")));

        List<ChatEvent> events = service.chat(CONVERSATION, "检索是怎么做的?", ChatMode.RAG).collectList().block();

        assertThat(events).isNotNull();
        assertThat(names(events)).containsExactly(
                ChatEvent.META, ChatEvent.CITATION, ChatEvent.CITATION,
                ChatEvent.TOKEN, ChatEvent.TOKEN, ChatEvent.DONE);

        // meta:会话标题取自首句提问;messageId 与随后回填的是同一条
        ChatEvent.Meta meta = (ChatEvent.Meta) events.get(0).data();
        assertThat(meta.conversationTitle()).isEqualTo("检索是怎么做的?");
        assertThat(meta.mode()).isEqualTo("rag");
        assertThat(meta.sources()).isEqualTo(2);

        // 引用编号与 prompt 里的编号必须一致(1 起)
        assertThat(events.stream()
                .filter(event -> ChatEvent.CITATION.equals(event.name()))
                .map(event -> ((ChatEvent.CitationRef) event.data()).index()))
                .containsExactly(1, 2);

        // done 带用量
        ChatEvent.Done done = (ChatEvent.Done) events.get(events.size() - 1).data();
        assertThat(done.chars()).isEqualTo("你好，世界".length());
        assertThat(done.tokenIn()).isEqualTo(11);
        assertThat(done.tokenOut()).isEqualTo(7);

        // 落库:提问 + 助手回答(内容是被回填的),引用连 ord 一起存
        assertThat(savedMessages()).anySatisfy(message -> assertThat(message.getContent()).isEqualTo("检索是怎么做的?"));
        assertThat(savedMessages()).anySatisfy(message -> {
            assertThat(message.getRole()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
            assertThat(message.getContent()).isEqualTo("你好，世界");
            assertThat(message.getTokenOut()).isEqualTo(7);
        });

        ArgumentCaptor<List<Citation>> savedCitations = ArgumentCaptor.forClass(List.class);
        verify(citations).saveAll(savedCitations.capture());
        assertThat(savedCitations.getValue()).extracting(Citation::getOrd).containsExactly(1, 2);
    }

    @Test
    void givesTheModelNumberedContextEndingWithTheQuestion() {
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk("c1", "架构设计", "检索层", "RRF 用排名倒数相加")));

        service.chat(CONVERSATION, "融合怎么做的?", ChatMode.RAG).collectList().block();

        String sent = model.lastPrompt().getInstructions().getLast().getText();
        assertThat(sent)
                .contains("[1] 《架构设计》 › 检索层")
                .contains("RRF 用排名倒数相加")
                .contains("【问题】\n融合怎么做的?");
    }

    @Test
    void chatModeNeverTouchesTheKnowledgeBase() {
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk("c1", "架构设计", null, "正文")));

        List<ChatEvent> events = service.chat(CONVERSATION, "你好", ChatMode.CHAT).collectList().block();

        verifyNoInteractions(retrieval);
        assertThat(names(events)).doesNotContain(ChatEvent.CITATION);
        assertThat(model.lastPrompt().getInstructions().getLast().getText()).isEqualTo("你好");
        assertThat(model.lastPrompt().getInstructions().getFirst().getText()).contains("看不到");
    }

    @Test
    void whenRetrievalBlowsUpTheAnswerStillGoesThroughWithoutCitations() {
        when(retrieval.retrieve(any())).thenThrow(new IllegalStateException("ES 连不上"));

        List<ChatEvent> events = service.chat(CONVERSATION, "问题", ChatMode.RAG).collectList().block();

        assertThat(events).isNotNull();
        assertThat(names(events)).doesNotContain(ChatEvent.CITATION);
        assertThat(names(events)).endsWith(ChatEvent.TOKEN, ChatEvent.DONE);
        assertThat(((ChatEvent.Meta) events.get(0).data()).sources()).isZero();
        verify(citations, never()).saveAll(any());
    }

    @Test
    void aFailedStreamKeepsTheHalfAnswerAndReportsAnError() {
        model = TestChatModel.streamingThenFailing("模型 500", "我只答到一半");
        service = new ChatService(conversations, messages, citations, agentTraces, notes, retrieval, chatClient(),
                new TransactionTemplate(new ResourcelessTransactionManager()), 3, 4, "test-model");
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk("c1", "笔记", null, "正文")));

        List<ChatEvent> events = service.chat(CONVERSATION, "问题", ChatMode.RAG).collectList().block();

        assertThat(events).isNotNull();
        assertThat(names(events)).endsWith(ChatEvent.TOKEN, ChatEvent.ERROR);
        assertThat(((ChatEvent.Error) events.get(events.size() - 1).data()).message()).contains("模型 500");
        // 半截答案也要留下 —— 刷新页面还能看见问了什么、答到哪
        assertThat(savedMessages()).anySatisfy(message -> assertThat(message.getContent()).isEqualTo("我只答到一半"));
    }

    @Test
    void historyWindowIsReadBeforeTheNewQuestionIsSaved() {
        ChatMessage previousQuestion = ChatMessage.user(CONVERSATION, "上一句");
        ReflectionTestUtils.setField(previousQuestion, "id", UUID.randomUUID());
        ChatMessage previousAnswer = ChatMessage.assistantPlaceholder(CONVERSATION, "test-model");
        previousAnswer.complete("上一答", 1, 1);
        ReflectionTestUtils.setField(previousAnswer, "id", UUID.randomUUID());
        when(messages.findByConversationIdOrderByCreatedAtDescIdAsc(any(), any()))
                .thenReturn(List.of(previousAnswer, previousQuestion));
        when(retrieval.retrieve(any())).thenReturn(List.of());

        service.chat(CONVERSATION, "再详细说说", ChatMode.RAG).collectList().block();

        List<org.springframework.ai.chat.messages.Message> instructions = model.lastPrompt().getInstructions();
        assertThat(instructions).hasSize(4); // system + 上一问 + 上一答 + 本轮
        assertThat(instructions.get(1).getText()).isEqualTo("上一句");
        assertThat(instructions.get(2).getText()).isEqualTo("上一答");
        assertThat(instructions.get(3).getText()).isEqualTo("再详细说说");
    }

    @Test
    void rejectsABlankQuestion() {
        assertThatThrownBy(() -> service.chat(CONVERSATION, "   ", ChatMode.RAG))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(retrieval);
        verify(messages, never()).save(any());
    }

    private List<ChatMessage> savedMessages() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messages, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }
}
