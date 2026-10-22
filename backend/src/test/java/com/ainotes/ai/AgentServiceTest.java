package com.ainotes.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ainotes.ai.dto.AgentRunView;
import com.ainotes.graph.NoteGraphService;
import com.ainotes.note.Note;
import com.ainotes.note.NoteRepository;
import com.ainotes.note.NoteService;
import com.ainotes.memory.MemoryService;
import com.ainotes.note.dto.NoteCreateRequest;
import com.ainotes.search.RetrievalPort;
import com.ainotes.search.RetrievedChunk;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.ResourcelessTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Agent 循环:事件顺序、工具结果回灌、写操作暂停、续跑重建、步数上限。
 *
 * <p>测的都是"手点很难发现、但用户一定会撞上"的地方:确认卡片能不能刷出来一半、
 * 用户点确认之后模型还记不记得自己要干什么(提示词里得带上带 tool_calls 的助手消息)、
 * 检索编号会不会在第二次检索时又从 [1] 数起。
 */
class AgentServiceTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NOTE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final CitationRepository citations = mock(CitationRepository.class);
    private final MemoryService memory = mock(MemoryService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ToolInvocationRepository invocations = mock(ToolInvocationRepository.class);
    private final RetrievalPort retrieval = mock(RetrievalPort.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final NoteService noteService = mock(NoteService.class);
    private final NoteGraphService graph = mock(NoteGraphService.class);

    private final List<AgentRun> savedRuns = new ArrayList<>();
    private final List<ToolInvocation> savedInvocations = new ArrayList<>();
    private final List<Citation> savedCitations = new ArrayList<>();
    private final Map<UUID, ChatMessage> savedMessages = new LinkedHashMap<>();

    private TestAgentModel model = TestAgentModel.scripted();
    private AgentService service;

    @BeforeEach
    void setUp() {
        setUpWithMaxSteps(3);
    }

    private void setUpWithMaxSteps(int maxSteps) {
        Conversation conversation = new Conversation(WORKSPACE, null, null);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION);
        when(conversations.findById(CONVERSATION)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(call -> call.getArgument(0));

        when(messages.save(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            // 注意:setUpWithMaxSteps 会被第二次调用,那时 Mockito 重新打桩本身就会以 null 触发这条
            // 已经装好的 answer —— 不判空会 NPE(经典坑)。
            if (message == null) {
                return null;
            }
            if (message.getId() == null) {
                ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            }
            savedMessages.put(message.getId(), message);
            return message;
        });
        when(messages.findById(any())).thenAnswer(call -> Optional.ofNullable(savedMessages.get(call.getArgument(0))));
        when(messages.findByConversationIdOrderByCreatedAtDescIdAsc(any(), any())).thenReturn(List.of());

        when(runs.save(any())).thenAnswer(call -> {
            AgentRun run = call.getArgument(0);
            if (run == null) { // 重新打桩时 Mockito 会拿 null 走一遍旧 answer
                return null;
            }
            if (run.getId() == null) {
                ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
                savedRuns.add(run);
            }
            return run;
        });
        when(runs.findById(any())).thenAnswer(call -> savedRuns.stream()
                .filter(run -> run.getId().equals(call.getArgument(0)))
                .findFirst());
        when(invocations.save(any())).thenAnswer(call -> {
            ToolInvocation invocation = call.getArgument(0);
            if (invocation == null) {
                return null;
            }
            if (invocation.getId() == null) {
                ReflectionTestUtils.setField(invocation, "id", UUID.randomUUID());
                savedInvocations.add(invocation);
            }
            return invocation;
        });
        when(invocations.findByRunIdOrderByOrdAsc(any())).thenAnswer(call -> byRun(call.getArgument(0)));
        when(invocations.findByRunIdAndStatusOrderByOrdAsc(any(), any())).thenAnswer(call -> byRun(call.getArgument(0)).stream()
                .filter(invocation -> invocation.getStatus().equals(call.getArgument(1)))
                .toList());
        when(citations.saveAll(any())).thenAnswer(call -> {
            Collection<Citation> saved = call.getArgument(0);
            if (saved == null) {
                return List.of();
            }
            savedCitations.addAll(saved);
            return List.copyOf(saved);
        });
        when(citations.findByMessageIdOrderByOrdAsc(any())).thenReturn(List.of());

        when(notes.findByWorkspaceIdAndDeletedAtIsNull(WORKSPACE)).thenReturn(List.of());
        when(graph.relations(any())).thenReturn(new NoteGraphService.NoteRelations(List.of(), List.of(), List.of()));
        when(graph.noteIdsWithTag(any(), any())).thenReturn(List.of());

        service = new AgentService(
                conversations, messages, citations, runs, invocations,
                new AgentTraceService(runs, invocations),
                retrieval, notes, noteService, graph, memory,
                new TransactionTemplate(new ResourcelessTransactionManager()),
                ChatClient.builder(model)
                        .defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                        .build(),
                3, 4, maxSteps, 4000, "test-model");
    }

    // ---- 读工具:执行、回灌、落库 -------------------------------------

    @Test
    void aReadToolRunsImmediatelyAndItsResultGoesBackToTheModel() {
        when(retrieval.retrieve(any())).thenReturn(List.of(
                chunk("c1", "架构设计", "检索层", "BM25 与向量各取一路,再用 RRF 融合。")));
        model.toolCall("searchNotes", "{\"query\":\"混合检索\"}")
                .says("检索是把 BM25 与向量两路结果用 RRF 融合的 [1]。");

        List<AgentEvent> events = run("混合检索是怎么做的?");

        assertThat(names(events)).containsExactly(
                AgentEvent.META, AgentEvent.TOOL_CALL, AgentEvent.TOOL_RESULT, AgentEvent.CITATION,
                AgentEvent.TOKEN, AgentEvent.DONE);

        // tool_call 事件:名字、中文名、参数、以及"不用确认"
        AgentEvent.ToolCallRef call = (AgentEvent.ToolCallRef) events.get(1).data();
        assertThat(call.name()).isEqualTo("searchNotes");
        assertThat(call.label()).isEqualTo("搜索笔记");
        assertThat(call.requiresConfirmation()).isFalse();
        assertThat(call.summary()).isEqualTo("检索「混合检索」");

        // 工具结果:落库 + 事件 + 摘要
        ToolInvocation invocation = savedInvocations.get(0);
        assertThat(invocation.getStatus()).isEqualTo(ToolInvocation.OK);
        assertThat(invocation.getCallId()).isEqualTo("call_1");
        assertThat(invocation.getOrd()).isZero();
        assertThat(invocation.getResult()).containsEntry("ok", true);
        assertThat(String.valueOf(invocation.getResult().get("text"))).contains("命中 1 段");
        assertThat(invocation.getLatencyMs()).isNotNull();
        assertThat(((AgentEvent.ToolResultRef) events.get(2).data()).ok()).isTrue();

        // 引用编号:与 prompt 里的 [1] 同源
        assertThat(savedCitations).hasSize(1);
        assertThat(savedCitations.get(0).getOrd()).isEqualTo(1);
        assertThat(savedCitations.get(0).getNoteTitle()).isEqualTo("架构设计");

        // 第二轮模型必须看得见工具结果(否则它会以为工具没被调用)
        assertThat(toolResponseTexts(model.prompts().get(1))).singleElement()
                .asString().contains("RRF 融合");

        // 助手消息回填的是模型说的最后一句;运行以 succeeded 收尾
        ChatMessage assistant = assistantMessage();
        assertThat(assistant.getContent()).isEqualTo("检索是把 BM25 与向量两路结果用 RRF 融合的 [1]。");
        assertThat(assistant.getTokenIn()).isEqualTo(22); // 两轮,每轮 11
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.SUCCEEDED);
        assertThat(savedRuns.get(0).getSteps()).isEqualTo(2);
    }

    @Test
    void usageIsCountedOncePerTurnNotOncePerChunk() {
        // 服务商**每个分块**都带 usage,而且它是累计值 —— 分块数不能乘进去。
        // 之前这里是把每个块的数字加起来:一次真实运行因此报出 tokenIn=82722(实际约 1700)。
        model.toolCall("listNotes", "{}").says("第一句。", "第二句。", "第三句。");

        List<AgentEvent> events = run("有哪些笔记?");

        AgentEvent.Done done = (AgentEvent.Done) events.get(events.size() - 1).data();
        assertThat(done.tokenIn()).isEqualTo(22); // 两轮 × 11,而不是四个分块 × 11
        assertThat(done.tokenOut()).isEqualTo(14);
    }

    @Test
    void noteLookupFallsBackToTheTitleWhenTheModelInventsAnId() {
        // 实测模型很爱自己编一个序号当 id("7")、同时把正确的标题也带上:
        // 这时候必须按标题找到,不然一步就白烧了
        Note architecture = note(UUID.randomUUID(), "架构设计", "分层:接口 / 领域 / 基础设施。");
        when(notes.findFirstByWorkspaceIdAndTitleIgnoreCaseAndDeletedAtIsNull(WORKSPACE, "架构设计"))
                .thenReturn(Optional.of(architecture));
        model.toolCall("getNote", "{\"noteId\":\"7\",\"title\":\"架构设计\"}").says("读到了。");

        run("读一下架构设计");

        assertThat(savedInvocations.get(0).getStatus()).isEqualTo(ToolInvocation.OK);
        assertThat(savedInvocations.get(0).getSummary()).isEqualTo("读取《架构设计》");
        assertThat(toolResponseTexts(model.lastPrompt())).singleElement()
                .asString().contains("分层:接口 / 领域 / 基础设施。");
    }

    @Test
    void listNotesTreatsSinceDaysZeroAsNoTimeLimit() {
        // 模型用 0 表达"不限时间";若按"最近 0 天"算就只剩今天更新的笔记,
        // 它会以为工作区里只有一篇,然后开始乱编 id(实测就是这么演的)
        Note fresh = note(UUID.randomUUID(), "今天写的", "x");
        Note old = note(UUID.randomUUID(), "上个月写的", "y");
        ReflectionTestUtils.setField(old, "updatedAt", Instant.now().minus(40, ChronoUnit.DAYS));
        when(notes.findByWorkspaceIdAndDeletedAtIsNull(WORKSPACE)).thenReturn(List.of(old, fresh));
        model.toolCall("listNotes", "{\"sinceDays\":0}").says("看到了。");

        run("有哪些笔记?");

        assertThat(savedInvocations.get(0).getSummary()).isEqualTo("列出 2 篇笔记");
        assertThat(toolResponseTexts(model.lastPrompt())).singleElement()
                .asString().contains("今天写的").contains("上个月写的");
    }

    @Test
    void aStreamThatDiesBeforeProducingAnythingIsRetriedOnce() {
        // 服务商偶尔在还没吐出任何内容时就把流掐掉(实测 "stream was reset: CANCEL"),
        // 这一轮等于白跑:重来一次是安全的 —— 正文没重复,工具也没执行过
        model.failsOnce().says("好的,已经整理完了。");

        List<AgentEvent> events = run("整理一下");

        assertThat(model.prompts()).hasSize(2); // 失败一次 + 重试一次
        assertThat(events).anyMatch(event -> AgentEvent.TOKEN.equals(event.name()));
        assertThat(((AgentEvent.Done) events.get(events.size() - 1).data()).chars()).isGreaterThan(0);
    }

    @Test
    void thePromptCarriesTheToolDefinitions() {
        model.toolCall("listNotes", "{}").says("好了。");

        run("有哪些笔记?");

        // 工具定义没进请求的话,真实模型根本不会调用任何工具 —— 这是最容易静默失效的一环
        assertThat(toolNames(model.prompts().get(0).getOptions()))
                .contains("searchNotes", "getNote", "listNotes", "createNote", "updateNote", "addTags");
    }

    private static List<String> toolNames(org.springframework.ai.chat.prompt.ChatOptions options) {
        if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null) {
            return toolOptions.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        }
        return List.of();
    }

    @Test
    void citationNumberingKeepsCountingAcrossSeveralSearches() {
        when(retrieval.retrieve(any()))
                .thenReturn(List.of(chunk("c1", "甲", null, "一"), chunk("c2", "乙", null, "二")))
                .thenReturn(List.of(chunk("c3", "丙", null, "三")));
        model.toolCall("searchNotes", "{\"query\":\"一\"}")
                .toolCall("searchNotes", "{\"query\":\"二\"}")
                .says("先放一边。");

        run("两次检索");

        // 第二轮检索接着数:否则正文里的 [3] 会指到第 1 段上
        assertThat(savedCitations.stream().map(Citation::getOrd)).containsExactly(1, 2, 3);
    }

    @Test
    void anUnknownToolIsReportedBackInsteadOfBreakingTheRun() {
        model.toolCall("frobnicate", "{}").says("换个办法。");

        List<AgentEvent> events = run("帮我 frobnicate");

        assertThat(names(events)).containsExactly(
                AgentEvent.META, AgentEvent.TOOL_CALL, AgentEvent.TOOL_RESULT, AgentEvent.TOKEN, AgentEvent.DONE);
        assertThat(((AgentEvent.ToolResultRef) events.get(2).data()).ok()).isFalse();
        assertThat(toolResponseTexts(model.prompts().get(1))).singleElement()
                .asString().contains("没有叫「frobnicate」的工具");
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.SUCCEEDED);
    }

    @Test
    void repeatingAnIdenticalCallIsSkippedInsteadOfBurningSteps() {
        when(notes.findByIdAndDeletedAtIsNull(NOTE)).thenReturn(Optional.of(note(NOTE, "架构设计", "正文")));
        String sameArgs = "{\"noteId\":\"" + NOTE + "\"}";
        model.toolCall("getNote", sameArgs)
                .toolCall("getNote", sameArgs)   // 8B 模型真的会这样:一字不差地再来一次
                .says("好。");

        List<AgentEvent> events = run("读一下架构设计");

        // 第二次不再真的执行:只回一句"别重复",省下一次查询和一轮 token
        assertThat(savedInvocations).hasSize(2);
        assertThat(savedInvocations.get(1).getStatus()).isEqualTo(ToolInvocation.ERROR);
        assertThat(savedInvocations.get(1).getSummary()).isEqualTo("重复调用,已跳过");
        verify(notes, times(1)).findByIdAndDeletedAtIsNull(NOTE);
        assertThat(toolResponseTexts(model.lastPrompt()))
                .anySatisfy(text -> assertThat(text).contains("已经跳过"));
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.SUCCEEDED);
        assertThat(names(events)).endsWith(AgentEvent.TOKEN, AgentEvent.DONE);
    }

    @Test
    void runningOutOfStepsStopsWithAnErrorButKeepsTheHalfAnswer() {
        setUpWithMaxSteps(1);
        model.toolCall("listNotes", "{}");

        List<AgentEvent> events = run("一直查下去");

        assertThat(names(events)).containsExactly(
                AgentEvent.META, AgentEvent.TOOL_CALL, AgentEvent.TOOL_RESULT, AgentEvent.ERROR);
        assertThat(((ChatEvent.Error) events.get(3).data()).code()).isEqualTo("STEP_LIMIT");
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.FAILED);
        assertThat(model.prompts()).hasSize(1); // 第二步压根没发出去
    }

    // ---- 写工具:暂停 → 确认 / 取消 → 续跑 ---------------------------

    @Test
    void aWriteToolPausesTheRunAndWaitsForTheUser() {
        model.toolCall("createNote", "{\"title\":\"本周周报\",\"contentMd\":\"# 周报\",\"tags\":\"周报 工作\"}");

        List<AgentEvent> events = run("整理本周笔记成周报");

        assertThat(names(events)).containsExactly(AgentEvent.META, AgentEvent.TOOL_CALL, AgentEvent.CONFIRM);

        AgentEvent.ToolCallRef call = (AgentEvent.ToolCallRef) events.get(1).data();
        assertThat(call.requiresConfirmation()).isTrue();
        assertThat(call.summary()).isEqualTo("新建《本周周报》");

        AgentEvent.Confirm confirm = (AgentEvent.Confirm) events.get(2).data();
        assertThat(confirm.pending()).singleElement()
                .satisfies(pending -> assertThat(pending.name()).isEqualTo("createNote"));

        // 写操作**没有**执行,也没落成笔记;它只是被记成 pending 等用户点
        verify(noteService, never()).create(any(), any());
        assertThat(savedInvocations.get(0).getStatus()).isEqualTo(ToolInvocation.PENDING);
        assertThat(savedInvocations.get(0).isRequiresConfirmation()).isTrue();
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.AWAITING_CONFIRMATION);

        // 暂停时模型不再被调用:卡片没点之前,后面什么都不该发生
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void confirmingExecutesTheWriteThenContinuesTheLoop() {
        String runId = pauseOnCreateNote();
        when(noteService.create(eq(WORKSPACE), any(NoteCreateRequest.class)))
                .thenAnswer(call -> note(call.getArgument(1)));
        model.says("周报已经建好了。");

        List<AgentEvent> events = resume(runId, true);

        assertThat(names(events)).containsExactly(
                AgentEvent.META, AgentEvent.TOOL_RESULT, AgentEvent.TOKEN, AgentEvent.DONE);
        assertThat(((AgentEvent.ToolResultRef) events.get(1).data()).ok()).isTrue();
        assertThat(savedInvocations.get(0).getStatus()).isEqualTo(ToolInvocation.OK);
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.SUCCEEDED);

        // 真的落成笔记了,而且标签写进了正文末尾
        var request = org.mockito.ArgumentCaptor.forClass(NoteCreateRequest.class);
        verify(noteService).create(eq(WORKSPACE), request.capture());
        assertThat(request.getValue().title()).isEqualTo("本周周报");
        assertThat(request.getValue().contentMd()).contains("# 周报").contains("#周报 #工作");

        // 续跑时提示词必须重建出"助手要求调用工具"的那一轮,否则服务商会拒绝
        List<Message> secondRun = model.lastPrompt().getInstructions();
        assertThat(secondRun.get(secondRun.size() - 1)).isInstanceOf(ToolResponseMessage.class);
        assertThat(secondRun.stream().anyMatch(org.springframework.ai.chat.messages.AssistantMessage.class::isInstance))
                .as("助手消息要带上 tool_calls,不然工具结果无处安放")
                .isTrue();
    }

    @Test
    void rejectingTheWriteTellsTheModelItWasCancelledAndNothingIsWritten() {
        String runId = pauseOnCreateNote();
        model.says("好,那就不建了。");

        List<AgentEvent> events = resume(runId, false);

        assertThat(names(events)).containsExactly(
                AgentEvent.META, AgentEvent.TOOL_RESULT, AgentEvent.TOKEN, AgentEvent.DONE);
        assertThat(((AgentEvent.ToolResultRef) events.get(1).data()).ok()).isFalse();
        verify(noteService, never()).create(any(), any());
        assertThat(savedInvocations.get(0).getStatus()).isEqualTo(ToolInvocation.REJECTED);
        assertThat(toolResponseTexts(model.lastPrompt())).singleElement()
                .asString().contains("用户取消了这次操作");
        // 取消不是失败:运行照常收尾
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(AgentRun.SUCCEEDED);
    }

    @Test
    void resumingPreloadsCitationsSoNumberingContinues() {
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk("c1", "甲", null, "一"), chunk("c2", "乙", null, "二")));
        model.toolCall("searchNotes", "{\"query\":\"一\"}")
                .toolCall("createNote", "{\"title\":\"汇总\",\"contentMd\":\"x\"}");
        run("把检索结果整理成笔记");
        String runId = savedRuns.get(savedRuns.size() - 1).getId().toString();

        // 暂停前已经落了两条引用
        assertThat(savedCitations.stream().map(Citation::getOrd)).containsExactly(1, 2);
        // 续跑时把这两条装回去,下一次检索必须从 [3] 开始
        when(citations.findByMessageIdOrderByOrdAsc(any())).thenReturn(List.copyOf(savedCitations));
        savedCitations.clear();
        when(retrieval.retrieve(any())).thenReturn(List.of(chunk("c3", "丙", null, "三")));
        model.toolCall("searchNotes", "{\"query\":\"再查\"}").says("好了。");
        when(noteService.create(any(), any())).thenAnswer(call -> note(call.getArgument(1)));

        List<AgentEvent> events = service.resume(UUID.fromString(runId), true).collectList().block();

        assertThat(events).isNotNull();
        // 新检索到的片段接着数(3),暂停前那两条照样留在引用表里
        assertThat(savedCitations.stream().map(Citation::getOrd)).containsExactly(1, 2, 3);
    }

    // ---- 轨迹视图 ---------------------------------------------------

    @Test
    void theTraceViewExposesPendingWritesSoTheFrontendCanRedrawTheCard() {
        String runId = pauseOnCreateNote();

        AgentRunView view = service.trace(UUID.fromString(runId));

        assertThat(view.status()).isEqualTo(AgentRun.AWAITING_CONFIRMATION);
        assertThat(view.goal()).isEqualTo("整理本周笔记成周报");
        assertThat(view.tools()).singleElement().satisfies(step -> {
            assertThat(step.name()).isEqualTo("createNote");
            assertThat(step.label()).isEqualTo("新建笔记");
            assertThat(step.status()).isEqualTo(ToolInvocation.PENDING);
            assertThat(step.requiresConfirmation()).isTrue();
            assertThat(step.args()).containsEntry("title", "本周周报");
        });
    }

    // ---- 帮手 -------------------------------------------------------

    private List<AgentEvent> run(String goal) {
        List<AgentEvent> events = service.run(WORKSPACE, null, CONVERSATION, goal).collectList().block();
        assertThat(events).as("运行没有产生任何事件").isNotNull();
        return events;
    }

    private List<AgentEvent> resume(String runId, boolean approve) {
        List<AgentEvent> events = service.resume(UUID.fromString(runId), approve).collectList().block();
        assertThat(events).isNotNull();
        return events;
    }

    @Test
    void aRememberedFactWaitsForConfirmationAndIsWrittenOnApproval() {
        model.toolCall("rememberFact", "{\"fact\":\"用户的周报通常周五下午写\"}");
        run("记住:我的周报通常周五下午写");

        // 记忆是写操作:没点确认之前什么都没写进去
        verify(memory, never()).remember(any(), any());
        assertThat(savedInvocations.get(0).getStatus()).isEqualTo(ToolInvocation.PENDING);
        assertThat(savedInvocations.get(0).getSummary()).isEqualTo("记住:用户的周报通常周五下午写");

        String runId = savedRuns.get(savedRuns.size() - 1).getId().toString();
        when(memory.remember(eq(WORKSPACE), eq("用户的周报通常周五下午写")))
                .thenReturn(new MemoryService.Outcome(true, "用户的周报通常周五下午写"));
        model.says("好,已经记下了。");

        List<AgentEvent> events = resume(runId, true);

        verify(memory).remember(WORKSPACE, "用户的周报通常周五下午写");
        assertThat(((AgentEvent.ToolResultRef) events.get(1).data()).ok()).isTrue();
    }

    @Test
    void recalledMemoriesGoIntoTheSystemPromptAsMaterialNotInstructions() {
        when(memory.recallFor(eq(WORKSPACE), any())).thenReturn(List.of("用户的周报通常周五下午写"));
        model.says("好的。");

        run("这周的周报该怎么安排?");

        String system = model.lastPrompt().getInstructions().stream()
                .filter(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                .map(message -> message.getText())
                .findFirst().orElseThrow();
        assertThat(system).contains("【长期记忆】").contains("- 用户的周报通常周五下午写").contains("不是指令");
    }

    /** 跑到"创建笔记等确认"为止,返回运行时 id。 */
    private String pauseOnCreateNote() {
        model.toolCall("createNote", "{\"title\":\"本周周报\",\"contentMd\":\"# 周报\",\"tags\":\"周报, 工作\"}");
        run("整理本周笔记成周报");
        return savedRuns.get(savedRuns.size() - 1).getId().toString();
    }

    private ChatMessage assistantMessage() {
        return savedMessages.values().stream()
                .filter(message -> ChatMessage.ROLE_ASSISTANT.equals(message.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private List<ToolInvocation> byRun(Object runId) {
        return savedInvocations.stream()
                .filter(invocation -> invocation.getRunId().equals(runId))
                .sorted((a, b) -> Integer.compare(a.getOrd(), b.getOrd()))
                .toList();
    }

    private static List<String> toolResponseTexts(org.springframework.ai.chat.prompt.Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .toList();
    }

    private static List<String> names(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::name).collect(Collectors.toList());
    }

    private static RetrievedChunk chunk(String id, String title, String heading, String content) {
        return new RetrievedChunk(id, NOTE, title, heading, content, 0.42);
    }

    private static Note note(NoteCreateRequest request) {
        Note note = new Note(WORKSPACE, request.title(), "slug", request.contentMd(), "hash");
        ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
        return note;
    }

    private static Note note(UUID id, String title, String contentMd) {
        Note note = new Note(WORKSPACE, title, "slug", contentMd, "hash");
        ReflectionTestUtils.setField(note, "id", id);
        return note;
    }
}
