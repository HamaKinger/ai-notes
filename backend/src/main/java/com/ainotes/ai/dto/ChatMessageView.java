package com.ainotes.ai.dto;

import com.ainotes.ai.ChatMessage;
import com.ainotes.ai.Citation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 历史消息(带它自己的引用,以及 Agent 运行轨迹)。 */
public record ChatMessageView(
        UUID id,
        String role,
        String content,
        String model,
        Integer tokenIn,
        Integer tokenOut,
        Instant createdAt,
        List<CitationView> citations,
        AgentRunView run) {

    public static ChatMessageView from(ChatMessage message, List<Citation> citations, AgentRunView run) {
        return new ChatMessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getModel(),
                message.getTokenIn(),
                message.getTokenOut(),
                message.getCreatedAt(),
                citations.stream().map(CitationView::from).toList(),
                run);
    }
}
