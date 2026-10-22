package com.ainotes.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;

/**
 * 把 {@code message} 表里的历史变成"喂给模型的那一串消息"。
 *
 * <p>对话({@link ChatService})与 Agent({@link AgentService})都要做这两件事:取最近 N 条(窗口大小
 * 见 {@code ai-notes.ai.history-window})、把空内容剔掉。放在一处,是为了两个入口**别慢慢长歪** ——
 * 历史窗口取错(比如把自己刚存进去的那条也算进历史)会让模型看见重复的提问,而且只在多轮之后才显形。
 */
final class ChatHistory {

    private ChatHistory() {
    }

    /**
     * 最近 N 条,按时间正序返回。{@code exclude} 用来剔掉"本次运行自己的提问与占位回答" ——
     * 续跑时它们已经在库里了,但接下来要单独、按原样拼进提示词(占位回答还带着工具调用)。
     */
    static List<ChatMessage> window(ChatMessageRepository messages, UUID conversationId, int limit, Set<UUID> exclude) {
        List<ChatMessage> recent = messages.findByConversationIdOrderByCreatedAtDescIdAsc(
                conversationId, PageRequest.of(0, Math.max(1, limit)));
        List<ChatMessage> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered); // 取的是倒序(要"最近 N 条"),喂模型必须正序
        return ordered.stream()
                .filter(ChatMessage::isVisible)
                .filter(message -> exclude == null || !exclude.contains(message.getId()))
                .toList();
    }

    /** 历史消息 → Spring AI 的消息。空内容跳过(占位行、历史里留下的空回答)。 */
    static List<Message> toAiMessages(List<ChatMessage> history) {
        List<Message> converted = new ArrayList<>(history.size());
        for (ChatMessage message : history) {
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            converted.add(ChatMessage.ROLE_USER.equals(message.getRole())
                    ? new UserMessage(content)
                    : new AssistantMessage(content));
        }
        return converted;
    }
}
