package com.ainotes.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 一次运行的**可变状态**(只在一条订阅链里被顺序读写,所以不需要加锁)。
 *
 * <p>为什么单开一个对象而不是在 {@link AgentService} 里堆局部变量:工具({@link AgentToolSet})
 * 也要往里写东西 —— 检索到的引用片段、以及"这次调用到底成功了没、摘要是什么"。工具是模型驱动的,
 * 它的返回值只能是一段给模型看的文本,没法顺带把结构化信息带出来;那就让工具把结构化信息
 * 记在这儿,由服务层统一落库、发事件。
 *
 * <p>{@link #outcomes} 是**队列**而不是字段:一次模型响应可以要求调用好几个工具,服务层按顺序
 * 逐个调、逐个取,谁记的就归还给谁(顺序严格一致,所以不用带 key)。
 */
final class AgentRunContext {

    private final UUID workspaceId;
    private final UUID conversationId;
    private final UUID runId;
    private final UUID assistantMessageId;

    /** 运行期收集到的引用片段:编号在整次运行内**连续递增**,跨多轮检索也不会重号 */
    private final List<ChatSource> sources = new ArrayList<>();
    /** 已经生成的答案(含"暂停"之前那一段):续跑时要接着往后写,而不是重写 */
    private final StringBuilder answer = new StringBuilder();
    private final Deque<ToolOutcome> outcomes = new ArrayDeque<>();

    private int steps;
    /** 工具调用在整次运行内的序号;续跑时从库里的最大序号接着数 */
    private int nextOrd;
    private int promptTokens;
    private int completionTokens;
    private boolean usageSeen;
    /** 本轮最后一个分块的用量(累计值),轮末由 {@link #commitUsage()} 结算 */
    private Usage turnUsage;
    /** 撞上写操作、等用户确认 */
    private boolean paused;
    private String failureCode;
    private String failure;
    /** 长期记忆:运行开始时按本次目标召回的一批,整次运行(含续跑)沿用,中途新记的不会插进来 */
    private List<String> memories = List.of();

    AgentRunContext(UUID workspaceId, UUID conversationId, UUID runId, UUID assistantMessageId, String existingAnswer) {
        this.workspaceId = workspaceId;
        this.conversationId = conversationId;
        this.runId = runId;
        this.assistantMessageId = assistantMessageId;
        if (existingAnswer != null) {
            this.answer.append(existingAnswer);
        }
    }

    UUID workspaceId() {
        return workspaceId;
    }

    UUID conversationId() {
        return conversationId;
    }

    UUID runId() {
        return runId;
    }

    List<String> memories() {
        return memories;
    }

    void memories(List<String> recalled) {
        this.memories = List.copyOf(recalled);
    }

    UUID assistantMessageId() {
        return assistantMessageId;
    }

    // ---- 答案 ------------------------------------------------------

    StringBuilder answer() {
        return answer;
    }

    // ---- 引用 ------------------------------------------------------

    List<ChatSource> sources() {
        return sources;
    }

    /** 下一个可用编号(1 起),与 {@code citation.ord}、正文里的 {@code [n]} 三者同源。 */
    int nextIndex() {
        return sources.size() + 1;
    }

    void addSource(ChatSource source) {
        sources.add(source);
    }

    // ---- 工具 ------------------------------------------------------

    void record(ToolOutcome outcome) {
        outcomes.add(outcome);
    }

    /** 取走工具自己记下的那次执行结果(服务层在每个调用之后立刻取)。 */
    ToolOutcome pollOutcome() {
        return outcomes.poll();
    }

    /** 下一个工具调用序号(0 起)。 */
    int nextOrd() {
        return nextOrd++;
    }

    /** 续跑:把序号接到库里已有轨迹之后。 */
    void ordBase(int base) {
        this.nextOrd = Math.max(0, base);
    }

    // ---- 计数与状态 -------------------------------------------------

    void step(int steps) {
        this.steps = steps;
    }

    int steps() {
        return steps;
    }

    /**
     * 记下模型这一轮**最后一次**上报的用量。
     *
     * <p>每个分块都带 usage,但它是**累计值**(实测 48 个块、每块都报同一份 prompt_tokens),
     * 所以只能覆盖、不能相加 —— 相加会把一次调用算成几十次,面板上就会出现"问一句话烧了 8 万 token"。
     * 取值方式与 {@link ChatService} 一致。
     */
    void usage(Usage usage) {
        if (usage != null) {
            this.turnUsage = usage;
        }
    }

    /**
     * 一轮结束时把这一轮的用量并进总计。
     *
     * <p>必须**按轮**结算:每一轮是一次独立的 API 调用,各自的用量相加才是整次运行的总量 ——
     * 直接在流里累加的话,下一轮的第一次上报会把上一轮的末尾值再算一遍。
     */
    void commitUsage() {
        if (turnUsage == null) {
            return;
        }
        Integer in = turnUsage.getPromptTokens();
        Integer out = turnUsage.getCompletionTokens();
        if (in != null) {
            promptTokens += in;
            usageSeen = true;
        }
        if (out != null) {
            completionTokens += out;
            usageSeen = true;
        }
        turnUsage = null;
    }

    Integer tokenIn() {
        return usageSeen ? promptTokens : null;
    }

    Integer tokenOut() {
        return usageSeen ? completionTokens : null;
    }

    void pause() {
        this.paused = true;
    }

    boolean paused() {
        return paused;
    }

    /** 记下失败原因(第一个为准:后面的往往是同一个错误的重放)。 */
    void fail(String code, String message) {
        if (this.failure == null) {
            this.failureCode = code;
            this.failure = message;
        }
    }

    String failureCode() {
        return failureCode == null ? "AGENT_ERROR" : failureCode;
    }

    String failure() {
        return failure;
    }

    /**
     * 工具执行结果里"给界面看"的那部分:成没成 + 一行摘要。
     *
     * <p>给模型看的原文不在这里 —— 它就是工具方法的返回值,服务层调用工具时手里已经有了,
     * 存两份迟早会不一致。
     */
    record ToolOutcome(boolean ok, String summary) {

        static ToolOutcome ok(String summary) {
            return new ToolOutcome(true, summary);
        }

        static ToolOutcome error(String summary) {
            return new ToolOutcome(false, summary);
        }
    }
}
