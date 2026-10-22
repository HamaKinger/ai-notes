package com.ainotes.ai;

import com.ainotes.ai.AgentRunContext.ToolOutcome;
import com.ainotes.ai.dto.AgentRunView;
import com.ainotes.common.NotFoundException;
import com.ainotes.memory.MemoryService;
import com.ainotes.graph.NoteGraphService;
import com.ainotes.note.NoteRepository;
import com.ainotes.note.NoteService;
import com.ainotes.search.RetrievalPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent 运行:一个目标 + 多步工具 + 写操作人工确认(M5 Step 1,设计文档 §7.1 / §8.3)。
 *
 * <p><b>工具循环是自己跑的,没有用 Spring AI 的 {@code ToolCallingAdvisor}</b>。这是本步最关键的一个决定:
 * 2.0 起工具调用被挪到 ChatClient 的 advisor 里(模型层不再执行工具),advisor 会在一次请求内把
 * "调用 → 执行 → 再调用"全部跑完 —— 那正是我们**不能**要的:写操作必须停在中间等用户点确认,
 * 而且每一步都要落库、发事件。所以 Agent 用的是另一个 ChatClient({@code agentChatClient},
 * 建的时候就关掉了 advisor 自动注册),循环由这里显式写。代价是这段代码得自己维护消息序列,
 * 好处是"什么时候停"完全在我们手里。
 *
 * <p><b>一次运行 = 两段 HTTP</b>:{@link #run} 跑到模型要求写操作为止(发 {@code confirm} 事件结束),
 * 用户在卡片上点确认后走 {@link #resume}。两段之间后端可能重启,所以续跑需要的一切
 * (提示词里的每一条消息、工具调用 id、引用编号)都从库里重建,不放在内存里。
 *
 * <p><b>停止条件</b>有三条:模型不再要求调用工具(正常结束)、撞上写操作(等确认)、
 * 步数超过 {@code ai-notes.agent.max-steps}(防止模型绕圈烧 token)。第三种会发 {@code error},
 * 但已经把生成的部分落库了。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 模型编出工具名时顺带回一份清单,免得它反复瞎猜 */
    private static final String TOOL_HINT = "可用工具:"
            + Arrays.stream(AgentTool.values())
            .filter(tool -> tool != AgentTool.UNKNOWN)
            .map(AgentTool::wire)
            .collect(Collectors.joining(", "));

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final CitationRepository citations;
    private final AgentRunRepository runs;
    private final ToolInvocationRepository invocations;
    private final AgentTraceService traces;
    private final RetrievalPort retrieval;
    private final NoteRepository notes;
    private final NoteService noteService;
    private final NoteGraphService graph;
    private final MemoryService memory;
    private final TransactionTemplate tx;
    private final ChatClient agentChatClient;
    /** 自己 new 一个而不是注入:Spring Boot 4 自动装配的是 Jackson 3 的 JsonMapper(bean 类型不是它),
     *  与 {@link com.ainotes.ingest.ChunkIndexStore} 保持一致。这里只用来读写工具参数那一小段 JSON。 */
    private final ObjectMapper json = new ObjectMapper();
    private final int ragTopK;
    private final int historyWindow;
    private final int maxSteps;
    private final int maxNoteChars;
    private final String chatModel;

    public AgentService(
            ConversationRepository conversations,
            ChatMessageRepository messages,
            CitationRepository citations,
            AgentRunRepository runs,
            ToolInvocationRepository invocations,
            AgentTraceService traces,
            RetrievalPort retrieval,
            NoteRepository notes,
            NoteService noteService,
            NoteGraphService graph,
            MemoryService memory,
            TransactionTemplate tx,
            @Qualifier("agentChatClient") ChatClient agentChatClient,
            @Value("${ai-notes.ai.rag-top-k:6}") int ragTopK,
            @Value("${ai-notes.ai.history-window:20}") int historyWindow,
            @Value("${ai-notes.agent.max-steps:8}") int maxSteps,
            @Value("${ai-notes.agent.max-note-chars:4000}") int maxNoteChars,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String chatModel) {
        this.conversations = conversations;
        this.messages = messages;
        this.citations = citations;
        this.runs = runs;
        this.invocations = invocations;
        this.traces = traces;
        this.retrieval = retrieval;
        this.notes = notes;
        this.noteService = noteService;
        this.graph = graph;
        this.memory = memory;
        this.tx = tx;
        this.agentChatClient = agentChatClient;
        this.ragTopK = Math.max(1, ragTopK);
        this.historyWindow = Math.max(1, historyWindow);
        this.maxSteps = Math.max(1, maxSteps);
        this.maxNoteChars = Math.max(200, maxNoteChars);
        this.chatModel = chatModel;
    }

    // ---- 对外 ------------------------------------------------------

    @Transactional(readOnly = true)
    public AgentRunView trace(UUID runId) {
        return traces.of(runId);
    }

    /**
     * 开一次运行。{@code conversationId} 为 null 时新建会话(与对话侧同一套:标题取首句目标)。
     */
    public Flux<AgentEvent> run(UUID workspaceId, UUID userId, UUID conversationId, String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("目标不能为空");
        }
        String text = goal.strip();
        // 记忆召回是外部调用(嵌入 + ES),放在事务之外,不占数据库连接(理由同 ChatService#chat)
        List<String> recalled = memory.recallFor(workspaceId, text);

        Start start = tx.execute(status -> persistStart(workspaceId, userId, conversationId, text));
        AgentRunContext ctx = new AgentRunContext(workspaceId, start.conversation().getId(), start.run().getId(),
                start.assistantMessage().getId(), null);
        ctx.memories(recalled);

        List<Message> prompt = new ArrayList<>(ChatHistory.toAiMessages(start.history()));
        prompt.add(new UserMessage(text));
        return stream(ctx, start.run(), start.conversation(), start.userMessage(), start.assistantMessage(),
                prompt, Flux.empty());
    }

    /** 落库那一段的产物:会话、取到的历史窗口、两条消息和运行记录。 */
    private record Start(Conversation conversation, List<ChatMessage> history, ChatMessage userMessage,
                         ChatMessage assistantMessage, AgentRun run) {
    }

    private Start persistStart(UUID workspaceId, UUID userId, UUID conversationId, String text) {
        Conversation conversation = conversationId == null
                ? conversations.save(new Conversation(workspaceId, userId, null))
                : requireConversation(conversationId, workspaceId);

        List<ChatMessage> history = ChatHistory.window(messages, conversation.getId(), historyWindow, Set.of());
        ChatMessage userMessage = messages.save(ChatMessage.user(conversation.getId(), text));
        ChatMessage assistantMessage = messages.save(ChatMessage.assistantPlaceholder(conversation.getId(), chatModel));
        conversation.titleFromFirstQuestion(text);
        conversation.touch();
        conversations.save(conversation);

        AgentRun run = runs.save(new AgentRun(conversation.getId(), userMessage.getId(), assistantMessage.getId(), text));
        return new Start(conversation, history, userMessage, assistantMessage, run);
    }

    /**
     * 用户点了确认卡片之后接着跑。
     *
     * <p>{@code approve=false} 表示取消:被拒的调用记成 {@code rejected},并把"用户取消了"当作
     * 工具结果告诉模型 —— 让它知道这一步没做,而不是以为做完了、继续往下吹。
     */
    public Flux<AgentEvent> resume(UUID runId, boolean approve) {
        Resume resume = load(runId);
        Flux<AgentEvent> exec = Mono.fromCallable(() -> {
            List<AgentEvent> events = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            Map<String, ToolCallback> byName = callbacksByName(new AgentToolSet(resume.ctx(), retrieval, notes,
                    noteService, graph, memory, ragTopK, maxNoteChars));

            resume.run().resume();
            runs.save(resume.run());

            for (ToolInvocation invocation : resume.pending()) {
                String text;
                if (approve) {
                    text = executeTool(resume.ctx(), invocation, byName);
                } else {
                    invocation.reject();
                    invocations.save(invocation);
                    text = "用户取消了这次操作,不要重试;请在回答里说明这一步没有执行。";
                }
                events.add(AgentEvent.toolResult(invocation));
                responses.add(new ToolResponseMessage.ToolResponse(invocation.getCallId(), invocation.getToolName(), text));
            }
            resume.prompt().add(ToolResponseMessage.builder().responses(responses).build());
            return events;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);

        return stream(resume.ctx(), resume.run(), resume.conversation(), resume.userMessage(),
                resume.assistantMessage(), resume.prompt(), exec);
    }

    // ---- 流的骨架 --------------------------------------------------

    /**
     * {@code meta} → (工具事件 | token)* → {@code done} / {@code confirm} / {@code error}。
     *
     * <p>模型出错**不打断收尾**:已经生成的部分照样落库、照样发 {@code error}(带半截答案),
     * 与对话侧一致 —— 用户至少能看到"它跑到哪儿了"。
     */
    private Flux<AgentEvent> stream(AgentRunContext ctx, AgentRun run, Conversation conversation,
                                    ChatMessage userMessage, ChatMessage assistantMessage,
                                    List<Message> prompt, Flux<AgentEvent> prelude) {
        Flux<AgentEvent> head = Flux.just(AgentEvent.meta(run.getId(), conversation.getId(),
                conversation.getTitle(), userMessage.getId(), assistantMessage.getId(), chatModel, run.getGoal()));

        Flux<AgentEvent> body = Flux.concat(prelude, turns(ctx, prompt, run))
                .doOnError(error -> ctx.fail(AiStreaming.codeOf(error), AiStreaming.rootMessage(error)))
                .onErrorResume(error -> Flux.empty()); // 错误改由收尾事件统一报告,别让 SSE 直接断掉

        Mono<AgentEvent> tail = Mono.fromCallable(() -> finish(ctx, run, assistantMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(tailError -> {
                    log.error("运行 {} 收尾失败", run.getId(), tailError);
                    return Mono.just(AgentEvent.error("PERSIST_FAILED",
                            "运行结果没能保存:" + AiStreaming.rootMessage(tailError)));
                });

        return Flux.concat(head, body, tail);
    }

    /** 一轮 = 一次模型调用(流式转发正文)+ 这一轮的工具处理。 */
    private Flux<AgentEvent> turns(AgentRunContext ctx, List<Message> prompt, AgentRun run) {
        if (ctx.steps() >= maxSteps) {
            ctx.fail("STEP_LIMIT", "步数用尽(上限 " + maxSteps + " 步),已停止;可以先看看它做到了哪一步,再补一句更具体的目标。");
            return Flux.empty();
        }
        ctx.step(ctx.steps() + 1);

        return Flux.defer(() -> {
            AgentToolSet toolSet = new AgentToolSet(ctx, retrieval, notes, noteService, graph, memory, ragTopK, maxNoteChars);
            Map<String, ToolCallback> byName = callbacksByName(toolSet);
            // 工具调用的分块:同一个 id 可能出现在多块里(参数是逐字流出来的),按"参数最全的那份"合并
            Map<String, AssistantMessage.ToolCall> calls = new LinkedHashMap<>();
            StringBuilder turnText = new StringBuilder();

            // **每次订阅都重新建一次请求**:ChatClient 的 advisor 链是一次性的(复用同一个 Flux 再订阅
            // 会抛 "No StreamAdvisors available to execute"),而下面的 retryWhen 要重新订阅
            Flux<AgentEvent> model = Flux.defer(() -> agentChatClient.prompt()
                            .system(AiPrompts.agentSystem(LocalDate.now(), ctx.memories()))
                            .messages(prompt)
                            .toolCallbacks(List.copyOf(byName.values()))
                            .stream()
                            .chatResponse())
                    .<AgentEvent>handle((response, sink) -> {
                        ctx.usage(AiStreaming.usageOf(response));
                        for (AssistantMessage.ToolCall call : AiStreaming.toolCallsOf(response)) {
                            calls.merge(keyOf(call), call, AgentService::fuller);
                        }
                        String delta = AiStreaming.deltaOf(response);
                        if (delta == null || delta.isEmpty()) {
                            return; // 只有 role / usage 的块
                        }
                        ctx.answer().append(delta);
                        turnText.append(delta);
                        sink.next(AgentEvent.token(delta));
                    })
                    // 用量的单位是"一次 API 调用":一轮结束时结算,别让下一轮的块把上一轮的末尾值再算一遍
                    .doFinally(signal -> ctx.commitUsage())
                    // 服务商偶尔会在**还没吐出任何内容之前**就把流掐掉(stream reset):
                    // 这一轮等于白跑,重来一次完全安全 —— 没有重复的正文,也没有已经执行过的工具。
                    // 有内容之后再断就不重试了(重试会让正文重复)。
                    .retryWhen(Retry.max(1).filter(error -> turnText.length() == 0 && calls.isEmpty()));

            return Flux.concat(model, Flux.defer(() -> afterTurn(ctx, prompt, run, calls, turnText.toString(), byName)));
        });
    }

    /**
     * 一轮结束之后:没有工具调用就收工;有的话逐个执行(写操作不执行,改为暂停)。
     *
     * <p>工具是阻塞的(检索、JDBC),所以整段放到 boundedElastic 上 —— 不能占住模型流的线程。
     */
    private Flux<AgentEvent> afterTurn(AgentRunContext ctx, List<Message> prompt, AgentRun run,
                                       Map<String, AssistantMessage.ToolCall> calls, String turnText,
                                       Map<String, ToolCallback> byName) {
        if (calls.isEmpty()) {
            return Flux.empty();
        }
        return Mono.fromCallable(() -> prepare(ctx, prompt, turnText, calls, byName))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(prepared -> Flux.concat(
                        Flux.fromIterable(prepared.events()),
                        prepared.paused() ? Flux.empty() : turns(ctx, prompt, run)));
    }

    /**
     * 落库 + 执行这一轮的工具调用,并把"助手消息 + 工具结果"追加进对话(续跑要用)。
     *
     * <p>写操作只记账不执行:每个写调用先留一行 {@code pending},这一批里只要有一个写操作,
     * 整轮就停下等用户确认 —— 同一批的读操作照常执行,它们的结果不会白拿。
     */
    private TurnPrep prepare(AgentRunContext ctx, List<Message> prompt, String turnText,
                             Map<String, AssistantMessage.ToolCall> calls, Map<String, ToolCallback> byName) {
        List<AgentEvent> events = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        List<AssistantMessage.ToolCall> effective = new ArrayList<>();
        List<ToolInvocation> earlier = new ArrayList<>(invocations.findByRunIdOrderByOrdAsc(ctx.runId()));
        boolean paused = false;

        for (AssistantMessage.ToolCall call : calls.values()) {
            if (call.id() == null || call.id().isBlank()) {
                // 没有 id 就没法把工具结果对回去(服务商会直接拒绝下次请求),只能丢掉这次调用
                log.warn("工具调用缺少 id,已忽略:{}", call);
                continue;
            }
            effective.add(call);
            AgentTool tool = AgentTool.of(call.name());
            Map<String, Object> args = parseArgs(call.arguments());

            ToolInvocation replay = replayed(earlier, call.id(), call.name(), args);
            if (replay != null) {
                // 重放:不执行、也不当写操作去打扰用户,只回一句"别重复了"
                String text = "这一步和第 " + (replay.getOrd() + 1) + " 步完全相同(同一个工具、同样的参数),"
                        + "结果不会变,已经跳过。请直接使用那一步的结果继续,不要重复调用。";
                ToolInvocation skipped = new ToolInvocation(ctx.runId(), call.id(), call.name(), ctx.nextOrd(),
                        false, "重复调用,已跳过", args, null);
                skipped.complete(ToolInvocation.ERROR, "重复调用,已跳过",
                        Map.of("ok", false, "text", text), 0);
                invocations.save(skipped);
                earlier.add(skipped);
                events.add(AgentEvent.toolCall(skipped));
                events.add(AgentEvent.toolResult(skipped));
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), text));
                continue;
            }

            ToolInvocation invocation = invocations.save(new ToolInvocation(
                    ctx.runId(), call.id(), call.name(), ctx.nextOrd(), tool.isWrite(),
                    AgentTool.describe(call.name(), args), args, tool.isWrite() ? ToolInvocation.PENDING : null));
            earlier.add(invocation);
            events.add(AgentEvent.toolCall(invocation));

            if (tool.isWrite()) {
                paused = true; // 不执行:等用户
                continue;
            }
            int sourceBase = ctx.sources().size();
            String text = executeTool(ctx, invocation, byName);
            events.add(AgentEvent.toolResult(invocation));
            // 检索/读笔记会新增引用片段:当场发出去,前端的 [n] 才能一点就跳
            // (否则要等刷新才从 citation 行里长出来 —— 同一条回答前后长得不一样)
            for (ChatSource source : ctx.sources().subList(sourceBase, ctx.sources().size())) {
                events.add(AgentEvent.citation(source));
            }
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), text));
        }

        if (!paused && !effective.isEmpty()) {
            AssistantMessage.Builder<?> assistant = AssistantMessage.builder().toolCalls(effective);
            if (turnText != null && !turnText.isBlank()) {
                assistant = assistant.content(turnText);
            }
            prompt.add(assistant.build());
            prompt.add(ToolResponseMessage.builder().responses(responses).build());
        }
        if (paused) {
            ctx.pause();
        }
        return new TurnPrep(events, paused);
    }

    /**
     * 执行一个工具调用:调 → 记 → 落库。
     *
     * <p>"成功"以**工具自己记下的结果**为准,不是"方法没抛异常":工具在业务上失败(没找到笔记、
     * 关键词为空)时照样正常返回一段给模型的解释,那不算成功。工具没记(未知工具、抛异常)时按失败算。
     */
    private String executeTool(AgentRunContext ctx, ToolInvocation invocation, Map<String, ToolCallback> byName) {
        long start = System.nanoTime();
        ToolCallback callback = byName.get(invocation.getToolName());
        String text;
        ToolOutcome outcome = null;
        if (callback == null) {
            text = "没有叫「" + invocation.getToolName() + "」的工具。" + TOOL_HINT;
        } else {
            try {
                text = callback.call(argumentsJson(invocation));
                outcome = ctx.pollOutcome();
            } catch (RuntimeException error) {
                ctx.pollOutcome(); // 工具可能已经记了一条,清掉,免得错位到下一次调用
                log.warn("工具 {} 执行失败", invocation.getToolName(), error);
                text = "工具执行失败:" + AiStreaming.rootMessage(error) + " 换个思路再试,或直接告诉用户这一步没做成。";
            }
        }
        boolean ok = outcome != null && outcome.ok();
        String summary = outcome != null ? outcome.summary() : firstLine(text);
        invocation.complete(ok ? ToolInvocation.OK : ToolInvocation.ERROR,
                summary, Map.of("ok", ok, "text", text), (System.nanoTime() - start) / 1_000_000);
        invocations.save(invocation);
        return text;
    }

    /**
     * 收尾:回填助手消息、落引用、更新运行状态,并决定最后一个事件。
     *
     * <p>三种收尾:**暂停**(等用户确认)、**失败**(模型出错或步数用尽,半截答案照留)、**成功**。
     * 引用每次都"先删后写" —— 暂停时写一遍、续跑结束再写一遍,否则前半段的引用会被后半段顶掉。
     */
    private AgentEvent finish(AgentRunContext ctx, AgentRun run, ChatMessage assistantMessage) {
        String answer = ctx.answer().toString();
        assistantMessage.complete(answer, ctx.tokenIn(), ctx.tokenOut());
        messages.save(assistantMessage);

        citations.deleteByMessageId(assistantMessage.getId());
        if (!ctx.sources().isEmpty()) {
            // 哪怕这时候答案还是空的(模型一句话没说就要求写操作 ⇒ 停在确认上)也要落:
            // 续跑时引用编号是从这张表里装回来的,少写这一笔,后半段的 [3] 会退化成 [1]。
            citations.saveAll(ctx.sources().stream()
                    .map(source -> new Citation(assistantMessage.getId(), source))
                    .toList());
        }

        // 步数是**整次运行**的累计:续跑那一段的 ctx 从 0 数起,直接覆盖会把"跑了 12 步"写成"2 步"
        run.step(run.getSteps() + ctx.steps());
        if (ctx.paused()) {
            run.awaitingConfirmation();
            runs.save(run);
            List<ToolInvocation> pending = invocations.findByRunIdAndStatusOrderByOrdAsc(run.getId(),
                    ToolInvocation.PENDING);
            return AgentEvent.confirm(run.getId(), pending);
        }
        if (ctx.failure() != null) {
            log.warn("运行 {} 失败:{}", run.getId(), ctx.failure());
            run.fail(ctx.failure());
            runs.save(run);
            // 失败也分"有没有造成影响":写操作已经执行完才断的,用户最担心的是"到底写没写进去"
            boolean wrote = invocations.findByRunIdOrderByOrdAsc(run.getId()).stream()
                    .anyMatch(call -> AgentTool.isWrite(call.getToolName())
                            && ToolInvocation.OK.equals(call.getStatus()));
            String hint = wrote
                    ? "已经确认的写操作都已生效(去笔记列表就能看到),只是最后那段收尾文字没能生成。"
                    : "这次没有写入任何笔记,可以重试一次。";
            String message = "STEP_LIMIT".equals(ctx.failureCode())
                    ? ctx.failure()
                    : "模型服务中途断开了(" + ctx.failure() + ")。" + hint;
            return AgentEvent.error(ctx.failureCode(), message);
        }
        run.succeed();
        runs.save(run);
        return AgentEvent.done(run.getId(), assistantMessage.getId(), assistantMessage.getTokenIn(),
                assistantMessage.getTokenOut(), answer.length(), ctx.steps());
    }

    // ---- 续跑前的重建 ------------------------------------------------

    /**
     * 从库里把"接着跑所需的一切"装回来:提示词(历史 + 本次提问 + 带工具调用的助手消息 +
     * 已完成的工具结果)、引用编号、待确认的调用。
     *
     * <p>刻意不加 {@code @Transactional}:这里从头到尾只有读,每个仓库方法自己就是一个小事务。
     * 开一个长事务反而会让"读出来 → 异步执行 → 再落库"这条链上的实体一直挂在持久化上下文里
     * (收尾那一步在 boundedElastic 线程上,不共享请求线程的事务)。
     */
    private Resume load(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("运行不存在:" + runId));
        if (!AgentRun.AWAITING_CONFIRMATION.equals(run.getStatus())) {
            throw new IllegalStateException("这次运行没有待确认的操作(当前状态:" + run.getStatus() + ")。");
        }
        List<ToolInvocation> all = invocations.findByRunIdOrderByOrdAsc(runId);
        List<ToolInvocation> pending = all.stream()
                .filter(invocation -> ToolInvocation.PENDING.equals(invocation.getStatus()))
                .toList();
        if (pending.isEmpty()) {
            throw new IllegalStateException("这次运行没有待确认的操作。");
        }
        Conversation conversation = requireConversation(run.getConversationId(), null);
        ChatMessage userMessage = messages.findById(run.getUserMessageId())
                .orElseThrow(() -> new NotFoundException("这次运行的提问已经不存在了,没法续跑"));
        ChatMessage assistantMessage = messages.findById(run.getAssistantMessageId())
                .orElseThrow(() -> new NotFoundException("这次运行的回答已经不存在了,没法续跑"));

        AgentRunContext ctx = new AgentRunContext(conversation.getWorkspaceId(), conversation.getId(), run.getId(),
                assistantMessage.getId(), assistantMessage.getContent());
        // 续跑沿用开始时的目标去召回,与第一段看到的记忆一致
        ctx.memories(memory.recallFor(conversation.getWorkspaceId(), run.getGoal()));
        // 引用编号必须接着往下走:前半段的答案里可能已经写了 [3],重开一轮不能又从 [1] 数起
        for (Citation citation : citations.findByMessageIdOrderByOrdAsc(assistantMessage.getId())) {
            ctx.addSource(new ChatSource(
                    citation.getOrd() == null ? ctx.nextIndex() : citation.getOrd(),
                    citation.getChunkId(),
                    citation.getNoteId(),
                    citation.getNoteTitle(),
                    citation.getHeadingPath(),
                    citation.getSnippet(),
                    citation.getScore() == null ? 0 : citation.getScore()));
        }
        ctx.ordBase(all.stream().mapToInt(ToolInvocation::getOrd).max().orElse(-1) + 1);

        List<Message> prompt = new ArrayList<>(ChatHistory.toAiMessages(
                ChatHistory.window(messages, conversation.getId(), historyWindow,
                        Set.of(userMessage.getId(), assistantMessage.getId()))));
        prompt.add(new UserMessage(userMessage.getContent()));

        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> resolved = new ArrayList<>();
        for (ToolInvocation invocation : all) {
            calls.add(new AssistantMessage.ToolCall(invocation.getCallId(), "function", invocation.getToolName(),
                    argumentsJson(invocation)));
            if (!ToolInvocation.PENDING.equals(invocation.getStatus())) {
                resolved.add(new ToolResponseMessage.ToolResponse(invocation.getCallId(), invocation.getToolName(),
                        resultText(invocation)));
            }
        }
        AssistantMessage.Builder<?> assistant = AssistantMessage.builder().toolCalls(calls);
        if (assistantMessage.getContent() != null && !assistantMessage.getContent().isBlank()) {
            assistant = assistant.content(assistantMessage.getContent());
        }
        prompt.add(assistant.build());
        if (!resolved.isEmpty()) {
            prompt.add(ToolResponseMessage.builder().responses(resolved).build());
        }

        return new Resume(run, conversation, userMessage, assistantMessage, ctx, prompt, pending);
    }

    // ---- 小工具 ------------------------------------------------------

    private Map<String, ToolCallback> callbacksByName(AgentToolSet toolSet) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : ToolCallbacks.from(toolSet)) {
            byName.put(callback.getToolDefinition().name(), callback);
        }
        return byName;
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(arguments, new TypeReference<Map<String, Object>>() { });
        } catch (Exception notJson) {
            // 模型给了半截/坏 JSON:参数留空,让工具自己去报"缺参数"
            log.warn("工具参数不是合法 JSON:{}", arguments);
            return Map.of();
        }
    }

    /**
     * 这个调用是不是"重放"(同一个工具 + 同样的参数,前面已经有过)。
     *
     * <p>8B 模型很容易陷进"同一句话再问一遍"的循环 —— 实测连续 6 次一模一样的 {@code listNotes},
     * 每次都白花一轮 token,还会把 {@code max-steps} 耗光,最后以 STEP_LIMIT 收场:用户看到的是
     * "它明明查到了,却什么都没做"。所以按(工具名 + 参数)去重,执行这一层兜底,不指望提示词管住它。
     *
     * <p>几个刻意的例外:
     * <ul>
     *   <li>比的是**解析后的参数**({@link Map#equals} 不看键顺序):模型给的 JSON 常常只是键序变了;</li>
     *   <li>自己那行要跳过({@code callId} 相等)—— 调用是先落库再判断的;</li>
     *   <li>用户**拒过**的调用不算"做过了":他可能就是想换个参数再来一次;</li>
     *   <li>写操作同样去重:同一个 {@code createNote} 执行成功后再来一次,照做就会多出一篇重复笔记;</li>
     *   <li>参数为空的调用([]、{})一律视为不同——{@code listNotes} 不带参数和带参数是两回事。</li>
     * </ul>
     */
    private static ToolInvocation replayed(List<ToolInvocation> earlier, String callId, String toolName,
                                           Map<String, Object> args) {
        if (args.isEmpty()) {
            return null;
        }
        for (ToolInvocation one : earlier) {
            if (one.getCallId() != null && one.getCallId().equals(callId)) {
                continue;
            }
            if (!one.getToolName().equals(toolName) || ToolInvocation.REJECTED.equals(one.getStatus())) {
                continue;
            }
            Map<String, Object> that = one.getArguments();
            if (that != null && !that.isEmpty() && that.equals(args)) {
                return one;
            }
        }
        return null;
    }

    private String argumentsJson(ToolInvocation invocation) {
        Map<String, Object> args = invocation.getArguments();
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(args);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String resultText(ToolInvocation invocation) {
        Object text = invocation.getResult() == null ? null : invocation.getResult().get("text");
        return text == null ? "" : String.valueOf(text);
    }

    /** 分块合并时同一个工具调用用哪个 key(模型不给 id 就用名字兜底)。 */
    private static String keyOf(AssistantMessage.ToolCall call) {
        return call.id() == null || call.id().isBlank() ? "name:" + call.name() : call.id();
    }

    /** 留参数更全的那一份(工具参数是逐字流出来的,后到的通常更完整)。 */
    private static AssistantMessage.ToolCall fuller(AssistantMessage.ToolCall first, AssistantMessage.ToolCall second) {
        int a = first.arguments() == null ? -1 : first.arguments().length();
        int b = second.arguments() == null ? -1 : second.arguments().length();
        return b >= a ? second : first;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "没有结果";
        }
        String line = text.strip().split("\n", 2)[0].strip();
        return line.length() <= 60 ? line : line.substring(0, 60) + "…";
    }

    private Conversation requireConversation(UUID conversationId, UUID workspaceId) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new NotFoundException("会话不存在:" + conversationId));
        if (workspaceId != null && !workspaceId.equals(conversation.getWorkspaceId())) {
            throw new IllegalArgumentException("这个会话不属于当前工作区");
        }
        return conversation;
    }

    /**
     * "续跑所需的全部输入"。打包成一个 record 而不是给 {@link #stream} 加一长串参数:
     * 这些东西是在同一个事务里读出来的,摆在一起才看得出来源。
     */
    private record Resume(
            AgentRun run,
            Conversation conversation,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            AgentRunContext ctx,
            List<Message> prompt,
            List<ToolInvocation> pending) {
    }

    private record TurnPrep(List<AgentEvent> events, boolean paused) {
    }
}
