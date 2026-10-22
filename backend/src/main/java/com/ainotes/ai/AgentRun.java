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
 * 一次 Agent 运行(表 {@code agent_run},V1 建表、V7 补列)。
 *
 * <p><b>为什么要有这张表</b>:普通问答是"一问一答",断在哪都无所谓;而 Agent 是"一个目标、多步工具、
 * 中间还要等用户确认",用户回头必须能看见"刚才它跑到哪一步了"。所以运行状态、步骤数、失败原因
 * 都落库,{@code tool_invocation} 是它的明细。
 *
 * <p><b>状态机</b>:{@code running} ⇄(有待确认的写操作)⇄ {@code awaiting_confirmation},终态
 * {@code succeeded} / {@code failed}。中间态可以来回——用户确认后继续跑,可能又提出一次新的写操作。
 *
 * <p>不做 JPA 关联:与 {@link Conversation} / {@link ChatMessage} 一样只存裸 UUID,
 * 需要哪一段就单独加载一段(见 {@link AgentService} 的续跑)。
 */
@Entity
@Table(name = "agent_run")
public class AgentRun {

    public static final String RUNNING = "running";
    public static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_message_id")
    private UUID userMessageId;

    @Column(name = "assistant_message_id")
    private UUID assistantMessageId;

    @Column(columnDefinition = "text")
    private String goal;

    @Column(nullable = false)
    private String status = RUNNING;

    @Column(nullable = false)
    private int steps;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AgentRun() {
    }

    public AgentRun(UUID conversationId, UUID userMessageId, UUID assistantMessageId, String goal) {
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.assistantMessageId = assistantMessageId;
        this.goal = goal;
    }

    /** 又推进了一步(一次模型调用算一步)。 */
    public void step(int steps) {
        this.steps = steps;
    }

    public void awaitingConfirmation() {
        this.status = AWAITING_CONFIRMATION;
        this.error = null;
    }

    /** 用户点了确认/取消之后重新开跑。 */
    public void resume() {
        this.status = RUNNING;
        this.error = null;
        this.finishedAt = null;
    }

    public void succeed() {
        this.status = SUCCEEDED;
        this.error = null;
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = FAILED;
        this.error = message;
        this.finishedAt = Instant.now();
    }

    public boolean isTerminal() {
        return SUCCEEDED.equals(status) || FAILED.equals(status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getUserMessageId() {
        return userMessageId;
    }

    public UUID getAssistantMessageId() {
        return assistantMessageId;
    }

    public String getGoal() {
        return goal;
    }

    public String getStatus() {
        return status;
    }

    public int getSteps() {
        return steps;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
