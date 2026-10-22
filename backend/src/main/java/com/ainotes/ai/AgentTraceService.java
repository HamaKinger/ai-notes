package com.ainotes.ai;

import com.ainotes.ai.dto.AgentRunView;
import com.ainotes.common.NotFoundException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读运行轨迹,并拼成给前端的视图。
 *
 * <p>单独一个类,是因为它有**两个调用方、两种场景**:会话历史要一次性给多条回答挂上轨迹
 * (见 {@link ChatService#messages})、轨迹接口要按 {@code runId} 取一条。而写轨迹的只有
 * {@link AgentService}。读写在两个类里分开,是想让"轨迹长什么样"只有一处定义。
 */
@Service
public class AgentTraceService {

    private final AgentRunRepository runs;
    private final ToolInvocationRepository invocations;

    public AgentTraceService(AgentRunRepository runs, ToolInvocationRepository invocations) {
        this.runs = runs;
        this.invocations = invocations;
    }

    /** 按助手消息 id 批量取轨迹:前端刷新会话时,有 Agent 的那几条回答要带上工具卡片。 */
    @Transactional(readOnly = true)
    public Map<UUID, AgentRunView> byAssistantMessage(Collection<UUID> assistantMessageIds) {
        if (assistantMessageIds == null || assistantMessageIds.isEmpty()) {
            return Map.of();
        }
        List<AgentRun> found = runs.findByAssistantMessageIdIn(assistantMessageIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ToolInvocation>> tools = invocations
                .findByRunIdInOrderByOrdAsc(found.stream().map(AgentRun::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ToolInvocation::getRunId));

        Map<UUID, AgentRunView> byMessage = new HashMap<>();
        for (AgentRun run : found) {
            byMessage.put(run.getAssistantMessageId(),
                    AgentRunView.from(run, tools.getOrDefault(run.getId(), List.of())));
        }
        return byMessage;
    }

    @Transactional(readOnly = true)
    public AgentRunView of(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("运行不存在:" + runId));
        return AgentRunView.from(run, invocations.findByRunIdOrderByOrdAsc(runId));
    }
}
