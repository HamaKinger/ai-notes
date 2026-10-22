package com.ainotes.ai.dto;

import com.ainotes.ai.AgentRun;
import com.ainotes.ai.AgentTool;
import com.ainotes.ai.ToolInvocation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次 Agent 运行的轨迹(`GET /agent/runs/{id}`),也是会话历史里挂在助手消息上的 {@code run} 字段。
 *
 * <p>会话历史带上它,是为了"刷新之后待确认的卡片还在":如果只把轨迹放在 SSE 事件里,用户一刷新
 * 就只剩半截回答和一个没有按钮的句子。
 */
public record AgentRunView(
        UUID id,
        String goal,
        String status,
        int steps,
        String error,
        Instant createdAt,
        Instant finishedAt,
        List<ToolStepView> tools) {

    public static AgentRunView from(AgentRun run, List<ToolInvocation> tools) {
        return new AgentRunView(
                run.getId(),
                run.getGoal(),
                run.getStatus(),
                run.getSteps(),
                run.getError(),
                run.getCreatedAt(),
                run.getFinishedAt(),
                tools.stream().map(ToolStepView::from).toList());
    }

    /** 轨迹里的一步。 */
    public record ToolStepView(
            UUID id,
            String name,
            String label,
            Map<String, Object> args,
            String status,
            boolean requiresConfirmation,
            String summary,
            String text,
            Integer latencyMs) {

        public static ToolStepView from(ToolInvocation invocation) {
            return new ToolStepView(
                    invocation.getId(),
                    invocation.getToolName(),
                    AgentTool.labelOf(invocation.getToolName()),
                    invocation.getArguments() == null ? Map.of() : invocation.getArguments(),
                    invocation.getStatus(),
                    invocation.isRequiresConfirmation(),
                    invocation.getSummary(),
                    textOf(invocation),
                    invocation.getLatencyMs());
        }

        /**
         * 工具返回给模型的那段原文。
         *
         * <p>回放时它比 {@code summary} 有用:摘要只说"做了什么",原文才说明"拿到了什么"。
         * 视图里截断到 500 字 —— 轨迹是给人扫一眼的,不是把整篇笔记再传一遍。
         */
        private static String textOf(ToolInvocation invocation) {
            Map<String, Object> result = invocation.getResult();
            Object text = result == null ? null : result.get("text");
            if (text == null) {
                return null;
            }
            String raw = String.valueOf(text).strip();
            return raw.length() <= 500 ? raw : raw.substring(0, 500) + "…";
        }
    }
}
