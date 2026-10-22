package com.ainotes.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一轮对话里的一条消息(用户提问 / 助手回答)。表名仍是 V1 建的 {@code message}。
 *
 * <p>叫 {@code ChatMessage} 而不是 {@code Message},是为了不和 Spring AI 的
 * {@code org.springframework.ai.chat.messages.Message} 撞名 —— 两者在
 * {@link ChatService} 里必然同时出现,同名会让那段代码读起来处处要靠全限定名猜。
 *
 * <p>这张表是**完整日志**:即使模型答到一半断了,也把已经生成的部分留下来,刷新页面还能看见。
 * 因此助手那行是"先建占位、流式结束再回填":{@link #assistantPlaceholder} 先落一行(立刻拿到
 * messageId 告诉前端,顺带把本轮提问一起持久化),{@link #complete} 再写内容与用量。
 */
@Entity
@Table(name = "message")
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private String role;

    @Column(columnDefinition = "text")
    private String content;

    private String model;

    @Column(name = "token_in")
    private Integer tokenIn;

    @Column(name = "token_out")
    private Integer tokenOut;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessage() {
    }

    private ChatMessage(UUID conversationId, String role, String content, String model) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.model = model;
    }

    public static ChatMessage user(UUID conversationId, String content) {
        return new ChatMessage(conversationId, ROLE_USER, content, null);
    }

    /** 助手的占位行:内容留空,等流式结束回填。 */
    public static ChatMessage assistantPlaceholder(UUID conversationId, String model) {
        return new ChatMessage(conversationId, ROLE_ASSISTANT, null, model);
    }

    /** 流式结束(或中途失败)后回填内容与用量;空内容保持 null。 */
    public void complete(String content, Integer tokenIn, Integer tokenOut) {
        this.content = content == null || content.isEmpty() ? null : content;
        if (tokenIn != null) {
            this.tokenIn = tokenIn;
        }
        if (tokenOut != null) {
            this.tokenOut = tokenOut;
        }
    }

    /**
     * 这条消息该不该出现在历史列表里。
     *
     * <p>只过滤一种情况:助手占位行还没回填(模型没答上来、进程重启了)。用户提问一律保留 ——
     * 提问丢了就不知道"当时问的是什么",而助手那半边空着只是没答上来而已。
     */
    public boolean isVisible() {
        return !ROLE_ASSISTANT.equals(role) || (content != null && !content.isBlank());
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public Integer getTokenIn() {
        return tokenIn;
    }

    public Integer getTokenOut() {
        return tokenOut;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
