package com.ainotes.ai;

/**
 * 文本工具(选中文本操作)的 SSE 事件。与对话是**同一套帧协议**(见设计文档 §8.3),
 * 只是少了 `citation` —— 文本工具不检索知识库。
 *
 * <p>为什么也要流式:润色/续写的结果往往比一句聊天长得多,而且用户是"盯着选中那段文字
 * 等结果"的,十几秒白屏比逐字冒出来难熬得多。
 *
 * <p>{@code meta} 里没有 messageId:文本工具**不落库**(它不修改笔记,改动一律由用户点
 * 「替换/插入」后从前端发 PATCH)。所以这里给的是一次操作的身份:动作、是否该写回、模型名。
 */
public record TextEvent(String name, Object data) {

    public static final String META = "meta";
    public static final String TOKEN = "token";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public static TextEvent meta(TextAction action, String model) {
        return new TextEvent(META, new Meta(action.wire(), action.label(), action.writesBack(), model));
    }

    public static TextEvent token(String delta) {
        return new TextEvent(TOKEN, new Token(delta));
    }

    public static TextEvent done(Integer tokenIn, Integer tokenOut, int chars) {
        return new TextEvent(DONE, new Done(tokenIn, tokenOut, chars));
    }

    public static TextEvent error(String code, String message) {
        return new TextEvent(ERROR, new Error(code, message));
    }

    /** {@code writesBack} 由后端定:解释类结果不该被替换回笔记 —— 规则只写一处。 */
    public record Meta(String action, String label, boolean writesBack, String model) {
    }

    public record Token(String delta) {
    }

    public record Done(Integer tokenIn, Integer tokenOut, int chars) {
    }

    public record Error(String code, String message) {
    }
}
