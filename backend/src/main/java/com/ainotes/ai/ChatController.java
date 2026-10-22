package com.ainotes.ai;

import com.ainotes.common.NotFoundException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 流式问答(SSE)。
 *
 * <p>用 POST 而不是 {@code EventSource}:提问是带正文的,还可能很长,不该塞进 URL;
 * 代价是前端得用 fetch + ReadableStream 自己解析 SSE 帧(见 frontend/src/lib/api.ts 的 streamChat)。
 *
 * <p>事件协议见设计文档 §8.3,这里只发 {@code meta} / {@code citation} / {@code token} /
 * {@code done} / {@code error} 五种 —— {@code tool_call} / {@code tool_result} 属于 M5 的工具调用。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 对某个会话说一句话,边生成边推。
     *
     * <p>注意 {@code produces}:少了它 Spring MVC 会把返回值当普通对象序列化,前端拿到的是一个
     * JSON 数组而不是流。
     *
     * <p><b>入参错误也走 SSE</b>:这个端点的响应类型是 {@code text/event-stream},而
     * {@code @RestControllerAdvice} 返回的 {@code Map} 错误体写不进这个 Content-Type ——
     * 实测结果是"抛个空 500",前端只会看到流断了。所以这里把校验提前做掉,错误作为
     * {@code error} 事件推回去(前端本来就要处理它),顺带避开一次内容协商。
     */
    @PostMapping(value = "/conversations/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chat(
            @PathVariable UUID id,
            @RequestBody(required = false) ChatRequest request) {
        try {
            String question = request == null ? null : request.q();
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            ChatMode mode = ChatMode.of(request.mode());
            return chatService.chat(id, question, mode)
                    .map(event -> ServerSentEvent.builder(event.data()).event(event.name()).build());
        } catch (NotFoundException | IllegalArgumentException error) {
            String code = error instanceof NotFoundException ? "NOT_FOUND" : "BAD_REQUEST";
            return Flux.just(errorEvent(code, error.getMessage()));
        }
    }

    private static ServerSentEvent<Object> errorEvent(String code, String message) {
        return ServerSentEvent.builder(ChatEvent.error(code, message).data())
                .event(ChatEvent.ERROR)
                .build();
    }

    /** {@code mode} 不传即 {@code rag};{@code chat} 表示不查知识库的普通聊天。 */
    public record ChatRequest(String q, String mode) {
    }
}
