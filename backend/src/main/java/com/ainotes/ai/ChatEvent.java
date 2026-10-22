package com.ainotes.ai;

import java.util.UUID;

/**
 * 一条 SSE 事件(协议见设计文档 §8.3)。{@link ChatController} 原样翻成
 * {@code ServerSentEvent} —— 事件名 = {@link #name()},数据 = {@link #data()}(JSON)。
 *
 * <p>顺序是固定的:{@code meta} → {@code citation}* → {@code token}* → {@code done}
 * (出错时以 {@code error} 收尾,可能是 {@code done} 的替代,也可能是 {@code token} 之后)。
 * 引用**先于**正文发出:片段在调模型之前就已经检索好了,让用户立刻看到"我打算依据这几段回答",
 * 比等答案出来再补引用更可信,也解释了后面为什么要等。
 *
 * <p>{@code meta} 里的 {@code assistantMessageId} 是**数据库里那一行**的 id(这时候内容还空着),
 * 前端先用它当本地消息的 key,刷新后同一条消息还是同一个 id,可以直接对上。
 */
public record ChatEvent(String name, Object data) {

    public static final String META = "meta";
    public static final String TOKEN = "token";
    public static final String CITATION = "citation";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public static ChatEvent meta(UUID conversationId, String conversationTitle, UUID userMessageId,
                                 UUID assistantMessageId, String model, ChatMode mode, int sources) {
        return new ChatEvent(META, new Meta(
                conversationId, conversationTitle, userMessageId, assistantMessageId, model, mode.wire(), sources));
    }

    public static ChatEvent token(String delta) {
        return new ChatEvent(TOKEN, new Token(delta));
    }

    public static ChatEvent citation(ChatSource source) {
        return new ChatEvent(CITATION, new CitationRef(
                source.index(),
                source.noteId(),
                source.noteTitle(),
                source.headingPath(),
                source.snippet(),
                source.score()));
    }

    public static ChatEvent done(UUID messageId, Integer tokenIn, Integer tokenOut, int chars) {
        return new ChatEvent(DONE, new Done(messageId, tokenIn, tokenOut, chars));
    }

    public static ChatEvent error(String code, String message) {
        return new ChatEvent(ERROR, new Error(code, message));
    }

    public record Meta(
            UUID conversationId,
            String conversationTitle,
            UUID userMessageId,
            UUID assistantMessageId,
            String model,
            String mode,
            int sources) {
    }

    public record Token(String delta) {
    }

    public record CitationRef(
            int index,
            UUID noteId,
            String noteTitle,
            String headingPath,
            String snippet,
            double score) {
    }

    /** {@code chars} 是答案长度,方便前端/日志确认"流完了到底存进去多少字"。 */
    public record Done(UUID messageId, Integer tokenIn, Integer tokenOut, int chars) {
    }

    public record Error(String code, String message) {
    }
}
