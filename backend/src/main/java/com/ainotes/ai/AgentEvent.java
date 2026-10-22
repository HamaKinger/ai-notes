package com.ainotes.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 运行的一个 SSE 事件(协议见设计文档 §8.3)。
 *
 * <p>与 {@link ChatEvent} 是同一套"名字 + JSON 数据"的帧格式,{@code token} / {@code citation} /
 * {@code error} 的数据结构直接复用 —— 前端解析这两条流用的是同一段代码,形状不一样反而多余。
 * 这里只多出四样:{@code tool_call}、{@code tool_result}、{@code confirm}、以及带 {@code runId} 的
 * {@code meta} / {@code done}。
 *
 * <p>顺序: {@code meta} → ({@code tool_call} → {@code tool_result})* → {@code token}* → {@code done};
 * 撞上写操作时以 {@code confirm} 收尾(不是结束,是**暂停**:等用户点确认,前端再开一条流接着跑),
 * 出错以 {@code error} 收尾。
 */
public record AgentEvent(String name, Object data) {

    public static final String META = "meta";
    public static final String TOKEN = "token";
    public static final String CITATION = "citation";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String CONFIRM = "confirm";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public static AgentEvent meta(UUID runId, UUID conversationId, String conversationTitle,
                                  UUID userMessageId, UUID assistantMessageId, String model, String goal) {
        return new AgentEvent(META, new Meta(
                runId, conversationId, conversationTitle, userMessageId, assistantMessageId, model, goal));
    }

    public static AgentEvent token(String delta) {
        return new AgentEvent(TOKEN, new ChatEvent.Token(delta));
    }

    public static AgentEvent citation(ChatSource source) {
        return new AgentEvent(CITATION, new ChatEvent.CitationRef(
                source.index(),
                source.noteId(),
                source.noteTitle(),
                source.headingPath(),
                source.snippet(),
                source.score()));
    }

    public static AgentEvent toolCall(ToolInvocation invocation) {
        return new AgentEvent(TOOL_CALL, new ToolCallRef(
                invocation.getId(),
                invocation.getToolName(),
                AgentTool.labelOf(invocation.getToolName()),
                invocation.getArguments() == null ? Map.of() : invocation.getArguments(),
                invocation.isRequiresConfirmation(),
                invocation.getSummary()));
    }

    public static AgentEvent toolResult(ToolInvocation invocation) {
        return new AgentEvent(TOOL_RESULT, new ToolResultRef(
                invocation.getId(),
                invocation.getToolName(),
                AgentTool.labelOf(invocation.getToolName()),
                ToolInvocation.OK.equals(invocation.getStatus()),
                invocation.getStatus(),
                invocation.getSummary(),
                invocation.getLatencyMs()));
    }

    /** 一轮结束、有写操作在等确认。前端据此画出确认卡片(刷新后由历史里的 run 轨迹重画)。 */
    public static AgentEvent confirm(UUID runId, List<ToolInvocation> pending) {
        return new AgentEvent(CONFIRM, new Confirm(
                runId,
                pending.stream().map(ToolCallRef::of).toList()));
    }

    public static AgentEvent done(UUID runId, UUID messageId, Integer tokenIn, Integer tokenOut, int chars, int steps) {
        return new AgentEvent(DONE, new Done(runId, messageId, tokenIn, tokenOut, chars, steps));
    }

    public static AgentEvent error(String code, String message) {
        return new AgentEvent(ERROR, new ChatEvent.Error(code, message));
    }

    public record Meta(
            UUID runId,
            UUID conversationId,
            String conversationTitle,
            UUID userMessageId,
            UUID assistantMessageId,
            String model,
            String goal) {
    }

    /** 一次工具调用(写操作会带 {@code requiresConfirmation=true},确认卡片据此渲染)。 */
    public record ToolCallRef(
            UUID id,
            String name,
            String label,
            Map<String, Object> args,
            boolean requiresConfirmation,
            String summary) {

        static ToolCallRef of(ToolInvocation invocation) {
            return new ToolCallRef(
                    invocation.getId(),
                    invocation.getToolName(),
                    AgentTool.labelOf(invocation.getToolName()),
                    invocation.getArguments() == null ? Map.of() : invocation.getArguments(),
                    invocation.isRequiresConfirmation(),
                    invocation.getSummary());
        }
    }

    public record ToolResultRef(
            UUID id,
            String name,
            String label,
            boolean ok,
            /** 与 {@link ToolInvocation} 的状态取值一致:`ok` / `error` / `rejected`。
             *  光靠 {@code ok} 分不出"工具失败了"和"用户取消了" —— 这两种在轨迹里该长得不一样。 */
            String status,
            String summary,
            Integer latencyMs) {
    }

    /** {@code chars} / {@code steps} 让人一眼看出"这次跑了多少、值不值"。 */
    public record Done(UUID runId, UUID messageId, Integer tokenIn, Integer tokenOut, int chars, int steps) {
    }

    public record Confirm(UUID runId, List<ToolCallRef> pending) {
    }
}
