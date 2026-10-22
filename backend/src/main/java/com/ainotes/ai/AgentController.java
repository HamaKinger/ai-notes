package com.ainotes.ai;

import com.ainotes.ai.dto.AgentRunView;
import com.ainotes.auth.AppUser;
import com.ainotes.auth.CurrentUser;
import com.ainotes.common.NotFoundException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Agent 运行(SSE)与轨迹查询。端点与设计文档 §8.2 一致:
 * <ul>
 *   <li>{@code POST /workspaces/{ws}/agent/run} —— 起一个目标,流式返回轨迹;</li>
 *   <li>{@code POST /agent/runs/{id}/confirm} —— 用户点了确认/取消,接着往下跑(还是一条流);</li>
 *   <li>{@code GET /agent/runs/{id}} —— 轨迹快照,排查用。</li>
 * </ul>
 *
 * <p><b>为什么"确认"也是一个流式端点</b>:确认之后不是"写一下就完事",Agent 还要接着推理、可能再调用
 * 别的工具、最后才吐结论 —— 那是同一段流的后半截。所以它照旧是 SSE,并且重新发一次 {@code meta}
 * (带同一个 {@code runId} 与 {@code messageId}),前端不需要为"第二段"写另一套处理。
 *
 * <p>与 {@link ChatController} 同样的取舍:入参错误也走 {@code error} 事件(这个端点的
 * Content-Type 是 {@code text/event-stream},普通错误体写不进去,实测会变成一个空 500)。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentService agentService;
    private final CurrentUser currentUser;

    public AgentController(AgentService agentService, CurrentUser currentUser) {
        this.agentService = agentService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/workspaces/{workspaceId}/agent/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> run(
            @PathVariable UUID workspaceId,
            @RequestBody(required = false) RunRequest request) {
        try {
            if (request == null || request.goal() == null || request.goal().isBlank()) {
                throw new IllegalArgumentException("目标不能为空");
            }
            UUID userId = currentUser.findWithDevFallback().map(AppUser::getId).orElse(null);
            return agentService.run(workspaceId, userId, request.conversationId(), request.goal())
                    .map(AgentController::sse);
        } catch (NotFoundException | IllegalArgumentException error) {
            return Flux.just(errorEvent(error));
        }
    }

    /** @param approve 不传按"确认"处理;{@code false} 表示用户点了取消。 */
    @PostMapping(value = "/agent/runs/{id}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) ConfirmRequest request) {
        try {
            boolean approve = request == null || request.approve() == null || request.approve();
            return agentService.resume(id, approve).map(AgentController::sse);
        } catch (NotFoundException | IllegalStateException | IllegalArgumentException error) {
            return Flux.just(errorEvent(error));
        }
    }

    @GetMapping("/agent/runs/{id}")
    public AgentRunView trace(@PathVariable UUID id) {
        return agentService.trace(id);
    }

    private static ServerSentEvent<Object> sse(AgentEvent event) {
        return ServerSentEvent.builder(event.data()).event(event.name()).build();
    }

    private static ServerSentEvent<Object> errorEvent(RuntimeException error) {
        String code;
        if (error instanceof NotFoundException) {
            code = "NOT_FOUND";
        } else if (error instanceof IllegalStateException) {
            code = "CONFLICT";
        } else {
            code = "BAD_REQUEST";
        }
        return ServerSentEvent.builder(AgentEvent.error(code, error.getMessage()).data())
                .event(AgentEvent.ERROR)
                .build();
    }

    /** {@code conversationId} 为空表示新建一个会话(标题取目标首句)。 */
    public record RunRequest(String goal, UUID conversationId) {
    }

    public record ConfirmRequest(Boolean approve) {
    }
}
