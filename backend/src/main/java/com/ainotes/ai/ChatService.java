package com.ainotes.ai;

import com.ainotes.ai.dto.AgentRunView;
import com.ainotes.ai.dto.ChatMessageView;
import com.ainotes.ai.dto.ConversationView;
import com.ainotes.common.NotFoundException;
import com.ainotes.note.NoteRepository;
import com.ainotes.search.RetrievalPort;
import com.ainotes.search.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 对话(RAG 问答)—— 设计文档 §7 的第一段落地,只做"问笔记、答带引用",
 * 工具调用/多步任务留给 M5。
 *
 * <p><b>一轮问答的顺序</b>(每一步都是有意为之):
 * <ol>
 *   <li>取历史窗口 —— 必须在写入本轮提问**之前**取,否则这一问会既在历史里又在末尾出现两次;</li>
 *   <li>RAG 模式先检索(拿到引用,供 prompt 编号);</li>
 *   <li>落用户的提问 + 助手的空占位行 —— 先落库再调模型,这样"提问"永远不会丢,
 *       而 {@code meta} 事件能立刻把 messageId 交给前端,刷新后同一条消息还是同一个 id;</li>
 *   <li>流式调模型,边流边攒;</li>
 *   <li>结束(或失败)后回填助手消息 + 落引用,发出 {@code done} / {@code error}。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code MessageChatMemoryAdvisor}</b>:Spring AI 的 ChatMemory 以
 * "一组消息"为单位,配合 {@code ChatMemoryRepository.saveAll} 会**整体覆盖**某个会话的记忆
 * (窗口滑出去的老消息就被删了)。而本项目的 {@code message} 表是完整日志:历史记录要能翻,
 * 部分失败的答案也要留着。所以这里自己按窗口取历史喂给模型,表结构不动 —— 记忆策略在
 * {@code ai-notes.ai.history-window} 可调。
 *
 * <p><b>失败时也留半句</b>:模型答到一半断了,已生成的部分照样回填并落库,前端收到
 * {@code error} 事件后把这段留在气泡里 —— 比整条消息消失强。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final CitationRepository citations;
    private final AgentTraceService agentTraces;
    private final NoteRepository notes;
    private final RetrievalPort retrieval;
    private final ChatClient chatClient;
    private final TransactionTemplate tx;
    private final int ragTopK;
    private final int historyWindow;
    private final String chatModel;

    public ChatService(
            ConversationRepository conversations,
            ChatMessageRepository messages,
            CitationRepository citations,
            AgentTraceService agentTraces,
            NoteRepository notes,
            RetrievalPort retrieval,
            ChatClient chatClient,
            TransactionTemplate tx,
            @Value("${ai-notes.ai.rag-top-k:6}") int ragTopK,
            @Value("${ai-notes.ai.history-window:20}") int historyWindow,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String chatModel) {
        this.conversations = conversations;
        this.messages = messages;
        this.citations = citations;
        this.agentTraces = agentTraces;
        this.notes = notes;
        this.retrieval = retrieval;
        this.chatClient = chatClient;
        this.tx = tx;
        this.ragTopK = Math.max(1, ragTopK);
        this.historyWindow = Math.max(1, historyWindow);
        this.chatModel = chatModel;
    }

    // ---- 会话 ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ConversationView> list(UUID workspaceId) {
        return conversations.findByWorkspaceIdOrderByUpdatedAtDesc(workspaceId).stream()
                .map(ConversationView::from)
                .toList();
    }

    @Transactional
    public ConversationView create(UUID workspaceId, UUID userId, String title) {
        return ConversationView.from(conversations.save(new Conversation(workspaceId, userId, title)));
    }

    /**
     * 会话的完整历史(带各自的引用)。助手占位行没回填时跳过,见 {@link ChatMessage#isVisible()},
     * 只有一种例外:那次运行**正等着用户确认**。
     *
     * <p>跑过 Agent 的那几条助手消息还会带上 {@code run}(工具轨迹 + 有没有待确认的写操作):
     * 刷新页面之后,确认卡片必须还在原地 —— 只把轨迹放在 SSE 事件里,用户一刷新就只剩半截回答。
     *
     * <p>上面这条例外正是为它开的:模型常常在给出工具调用前一句话都不说(正文为空 ⇒ 占位行本来会被
     * 过滤掉),可这时卡片还等着点"确认"。把有待确认运行的助手消息一并留下,刷新才丢不了。
     */
    @Transactional(readOnly = true)
    public List<ChatMessageView> messages(UUID conversationId) {
        requireConversation(conversationId);
        List<ChatMessage> history = messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        if (history.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = history.stream().map(ChatMessage::getId).toList();
        Map<UUID, AgentRunView> traces = agentTraces.byAssistantMessage(ids);
        List<ChatMessage> all = history.stream()
                .filter(message -> message.isVisible() || awaitsConfirmation(traces.get(message.getId())))
                .toList();
        Map<UUID, List<Citation>> byMessage = citations
                .findByMessageIdInOrderByOrdAsc(all.stream().map(ChatMessage::getId).toList()).stream()
                .collect(Collectors.groupingBy(Citation::getMessageId));
        return all.stream()
                .map(message -> ChatMessageView.from(
                        message,
                        byMessage.getOrDefault(message.getId(), List.of()),
                        traces.get(message.getId())))
                .toList();
    }

    /** 这次运行停在"等用户点确认"上 —— 卡着的那条空助手消息也得留在历史里。 */
    private static boolean awaitsConfirmation(AgentRunView run) {
        return run != null && AgentRun.AWAITING_CONFIRMATION.equals(run.status());
    }

    @Transactional
    public ConversationView rename(UUID conversationId, String title) {
        Conversation conversation = requireConversation(conversationId);
        conversation.rename(title);
        return ConversationView.from(conversations.save(conversation));
    }

    /** 删会话:消息与引用交给数据库的 ON DELETE CASCADE(V1 建表时就是这么定的)。 */
    @Transactional
    public void delete(UUID conversationId) {
        conversations.delete(requireConversation(conversationId));
    }

    // ---- 流式问答 --------------------------------------------------

    /** 一轮提问落库后的两条消息:提问本身,以及用来回填答案的助手占位行。 */
    private record Turn(ChatMessage userMessage, ChatMessage assistantMessage) {
    }

    private Turn persistQuestion(Conversation conversation, String text) {
        ChatMessage userMessage = messages.save(ChatMessage.user(conversation.getId(), text));
        ChatMessage assistantMessage = messages.save(ChatMessage.assistantPlaceholder(conversation.getId(), chatModel));
        conversation.titleFromFirstQuestion(text);
        conversation.touch();
        conversations.save(conversation);
        return new Turn(userMessage, assistantMessage);
    }

    /**
     * 流式问答。
     *
     * <p>事务只罩住落库那一小段(提问 + 占位行 + 会话标题),由 {@link TransactionTemplate} 显式包住。
     * 检索(查询向量化 + ES)是外部 HTTP 调用,放在事务之外 —— 否则它会在整个等待期间占着一个数据库连接,
     * 嵌入服务一慢,连接池就会被占满。真正的流式与收尾同样在事务之外、boundedElastic 线程上完成。
     */
    public Flux<ChatEvent> chat(UUID conversationId, String question, ChatMode mode) {
        Conversation conversation = requireConversation(conversationId);
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String text = question.strip();

        List<ChatMessage> history = history(conversationId);
        List<ChatSource> sources = mode.usesKnowledgeBase()
                ? retrieve(conversation.getWorkspaceId(), text)
                : List.of();

        Turn turn = tx.execute(status -> persistQuestion(conversation, text));
        ChatMessage userMessage = turn.userMessage();
        ChatMessage assistantMessage = turn.assistantMessage();

        // 攒答案 + 记录用量/失败。这些对象只在反应式信号的顺序里被读写:
        // token 信号是串行发出的,收尾的 Mono 也在同一订阅链上,所以不需要加锁。
        StringBuilder answer = new StringBuilder();
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Flux<ChatEvent> head = Flux.concat(
                Flux.just(ChatEvent.meta(
                        conversationId,
                        conversation.getTitle(),
                        userMessage.getId(),
                        assistantMessage.getId(),
                        chatModel,
                        mode,
                        sources.size())),
                Flux.fromIterable(sources).map(ChatEvent::citation));

        Flux<ChatEvent> body = chatClient.prompt()
                .system(AiPrompts.system(mode.usesKnowledgeBase()))
                .messages(ChatHistory.toAiMessages(history))
                .user(AiPrompts.userMessage(text, sources))
                .stream()
                .chatResponse()
                .<ChatEvent>handle((response, sink) -> {
                    Usage usage = AiStreaming.usageOf(response);
                    if (usage != null) {
                        lastUsage.set(usage);
                    }
                    String delta = AiStreaming.deltaOf(response);
                    if (delta == null || delta.isEmpty()) {
                        return; // 只有 role/usage 的块,没有正文
                    }
                    answer.append(delta);
                    sink.next(ChatEvent.token(delta));
                })
                .doOnError(failure::set)
                .onErrorResume(error -> Flux.empty()); // 错误改由收尾事件统一报告,别让 SSE 直接断掉

        Mono<ChatEvent> tail = Mono
                .fromCallable(() -> finish(assistantMessage, answer.toString(), lastUsage.get(), failure.get(), sources))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(tailError -> {
                    log.error("对话 {} 收尾失败(答案没能落库)", conversationId, tailError);
                    return Mono.just(ChatEvent.error("PERSIST_FAILED", "回答没能保存:" + AiStreaming.rootMessage(tailError)));
                });

        return Flux.concat(head, body, tail);
    }

    // ---- 内部 ------------------------------------------------------

    /**
     * 收尾:回填助手消息、落引用,并决定最后一个事件是 {@code done} 还是 {@code error}。
     *
     * <p>跑在 boundedElastic 上 —— 这一段是阻塞的 JPA 写,不该占住 reactor 的网络线程。
     * 客户端中途断开时这个 Mono 不会被订阅,于是半截答案留在内存里没落库;这是有意的取舍:
     * 与其为了"断开也保存"引入不可取消的旁路线程,不如让用户重问一次。
     */
    private ChatEvent finish(ChatMessage assistantMessage, String answer, Usage usage,
                             Throwable failure, List<ChatSource> sources) {
        assistantMessage.complete(answer, AiStreaming.tokensOf(usage, true), AiStreaming.tokensOf(usage, false));
        messages.save(assistantMessage);

        if (!answer.isBlank() && !sources.isEmpty()) {
            citations.saveAll(sources.stream()
                    .map(source -> new Citation(assistantMessage.getId(), source))
                    .toList());
        }

        if (failure == null) {
            return ChatEvent.done(
                    assistantMessage.getId(),
                    assistantMessage.getTokenIn(),
                    assistantMessage.getTokenOut(),
                    answer.length());
        }
        log.warn("对话 {} 生成失败,已保留 {} 字: {}", assistantMessage.getConversationId(), answer.length(), failure.toString());
        return ChatEvent.error(AiStreaming.codeOf(failure), "模型调用失败:" + AiStreaming.rootMessage(failure));
    }

    /** 历史窗口:最近 N 条,按时间正序喂给模型(与 Agent 侧共用一套取法,见 {@link ChatHistory})。 */
    private List<ChatMessage> history(UUID conversationId) {
        return ChatHistory.window(messages, conversationId, historyWindow, Set.of());
    }

    /**
     * 检索用于回答的片段。
     *
     * <p>检索失败**不让问答失败**:降级成"没有片段",模型会按系统提示说知识库里没有相关内容,
     * 前端也会看到 sources=0。这比直接抛 500 好 —— 至少用户还知道是"没查到"而不是"系统坏了"。
     * (检索层自己也有降级:嵌入/ES 单路失败时用另一路的结果,见 SearchService。)
     */
    private List<ChatSource> retrieve(UUID workspaceId, String text) {
        try {
            List<RetrievedChunk> chunks = retrieval.retrieve(
                    new RetrievalPort.SearchQuery(workspaceId, text, null, ragTopK));
            List<ChatSource> sources = new ArrayList<>(chunks.size());
            for (RetrievedChunk chunk : chunks) {
                sources.add(ChatSource.of(sources.size() + 1, chunk, titleOf(chunk)));
            }
            return sources;
        } catch (RuntimeException error) {
            log.warn("检索失败,本轮回答不带引用: {}", error.toString());
            return List.of();
        }
    }

    /** 片段所属笔记的标题:优先用索引里的(ES 的 chunk 文档带着),缺失才回查一次 note 表。 */
    private String titleOf(RetrievedChunk chunk) {
        if (chunk.noteTitle() != null && !chunk.noteTitle().isBlank()) {
            return chunk.noteTitle();
        }
        return chunk.noteId() == null ? null : notes.findById(chunk.noteId()).map(note -> note.getTitle()).orElse(null);
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("会话不存在:" + conversationId));
    }
}
