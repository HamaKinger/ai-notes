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
 * 一次对话(会话)。消息在 {@link Message},引用在 {@link Citation}。
 *
 * <p>会话自己不做级联映射:删除走数据库的 {@code ON DELETE CASCADE}(V1 里 message / citation
 * 都是这么建的)。JPA 只认 {@code conversationId} 这个裸 UUID,不建立对象关联 —— 这样可以按需
 * 分别加载消息,也不用担心序列化时把整棵图拖出来。
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    /** 标题上限:首轮提问直接拿来当标题(不额外调一次模型),太长就截断 */
    static final int TITLE_MAX = 40;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id")
    private UUID userId;

    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Conversation() {
    }

    public Conversation(UUID workspaceId, UUID userId, String title) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.title = normalize(title);
    }

    /** 每说一轮都刷新活跃时间,会话列表据此排序。 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * 第一轮提问顺便当标题。
     *
     * <p>只在还没有标题时补一次:用户重命名过的会话不该被下一句提问覆盖掉。
     * 不额外调模型生成标题 —— 那要多花一次请求和几百毫秒,而首句问题本身就挺好认。
     */
    public void titleFromFirstQuestion(String question) {
        if (title != null) {
            return;
        }
        title = normalize(question);
    }

    public void rename(String title) {
        this.title = normalize(title);
        this.updatedAt = Instant.now();
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String oneLine = raw.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX) + "…";
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
