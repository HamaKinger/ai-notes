package com.ainotes.ai;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 文本工具(设计文档 §8.2 的 {@code /ai/*})。
 *
 * <p>三个端点都不带 noteId:正文由前端传上来。这样**未保存的编辑**也能处理(自动保存有
 * 800ms 防抖,用户选完就点润色时后端手里那份还是旧的),后端也就不用替调用方决定
 * "以哪一份为准"。
 */
@RestController
@RequestMapping("/api/v1")
public class AiController {

    private final AiTextService service;

    public AiController(AiTextService service) {
        this.service = service;
    }

    /**
     * 选中文本操作:SSE 流式。
     *
     * <p>错误也走 SSE 事件(理由同 {@link ChatController}:这个端点的响应类型是
     * {@code text/event-stream},`@RestControllerAdvice` 的 JSON 错误体写不进去,只会变成空 500)。
     */
    @PostMapping(value = "/ai/inline", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> inline(@RequestBody(required = false) InlineRequest request) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            return service.inline(request.text(), TextAction.of(request.action()), request.to())
                    .map(event -> ServerSentEvent.builder(event.data()).event(event.name()).build());
        } catch (IllegalArgumentException error) {
            return Flux.just(ServerSentEvent.builder(TextEvent.error("BAD_REQUEST", error.getMessage()).data())
                    .event(TextEvent.ERROR)
                    .build());
        }
    }

    /** 一句话摘要;要不要存进笔记由用户决定(前端「保存为摘要」走 PATCH /notes/{id})。 */
    @PostMapping("/ai/summarize")
    public SummaryResponse summarize(@RequestBody(required = false) TextRequest request) {
        TextRequest body = require(request);
        return new SummaryResponse(service.summarize(body.title(), body.content()));
    }

    /** 推荐标签;{@code existing} 是工作区已有标签,传给模型只为尽量复用。 */
    @PostMapping("/ai/suggest-tags")
    public TagsResponse suggestTags(@RequestBody(required = false) TextRequest request) {
        TextRequest body = require(request);
        return new TagsResponse(service.suggestTags(body.title(), body.content(), body.existing()));
    }

    private static TextRequest require(TextRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return request;
    }

    /** {@code to} 只在翻译时有用;不传就按原文语言反过来译(见 AiTextService#targetOr)。 */
    public record InlineRequest(String text, String action, String to) {
    }

    public record TextRequest(String title, String content, List<String> existing) {
    }

    public record SummaryResponse(String summary) {
    }

    public record TagsResponse(List<String> tags) {
    }
}
