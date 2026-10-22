package com.ainotes.ai;

/**
 * 对话模式。
 *
 * <p>{@code RAG} 先检索工作区笔记、把片段编号后交给模型,答案带引用;{@code CHAT} 不碰知识库,
 * 就是普通聊天。设为默认的是 RAG —— 这个侧栏的卖点就是"问自己的笔记",纯聊天只是 B 面,
 * 而且系统提示词里会明说"我看不到你的笔记",免得模型不懂装懂。
 */
public enum ChatMode {

    RAG,
    CHAT;

    /** 解析请求里的 mode;没给就当 RAG。 */
    public static ChatMode of(String raw) {
        if (raw == null || raw.isBlank()) {
            return RAG;
        }
        return switch (raw.trim().toLowerCase()) {
            case "rag", "knowledge", "kb" -> RAG;
            case "chat", "plain" -> CHAT;
            default -> throw new IllegalArgumentException("未知的对话模式: " + raw + "(可选 rag / chat)");
        };
    }

    /** 传给前端的写法(小写,和请求里的一致)。 */
    public String wire() {
        return name().toLowerCase();
    }

    public boolean usesKnowledgeBase() {
        return this == RAG;
    }
}
