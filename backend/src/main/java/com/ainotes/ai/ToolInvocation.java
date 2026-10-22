package com.ainotes.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一次工具调用(表 {@code tool_invocation},V1 建表、V7 补列)。运行轨迹就是它的有序列表。
 *
 * <p><b>写操作"先记账、后执行"</b>:模型要求写入时,先把这一行落库(status={@code pending},
 * {@code requires_confirmation=true}),然后**不执行**、把控制权交回用户;确认之后才执行并回填
 * {@code result} / {@code status} / {@code latency_ms}。这样"待确认"是数据库里的事实,
 * 而不是某个内存对象的状态 —— 后端重启也不丢。
 *
 * <p>{@code arguments} / {@code result} 是 JSONB(V1 的列类型),用 Hibernate 的 JSON 映射读写,
 * 不额外转字符串:轨迹接口要原样把它们交给前端,psql 里也才能直接 `arguments->>'title'`。
 */
@Entity
@Table(name = "tool_invocation")
public class ToolInvocation {

    /** 等用户确认(写操作) */
    public static final String PENDING = "pending";
    public static final String OK = "ok";
    public static final String ERROR = "error";
    /** 用户在确认卡片上点了取消 */
    public static final String REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    /** 模型给这次调用的 id,续跑时用它对齐 tool_calls 与 tool 响应 */
    @Column(name = "call_id")
    private String callId;

    @Column(nullable = false)
    private int ord;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> arguments;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> result;

    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ToolInvocation() {
    }

    public ToolInvocation(UUID runId, String callId, String toolName, int ord,
                          boolean requiresConfirmation, String summary, Map<String, Object> arguments,
                          String status) {
        this.runId = runId;
        this.callId = callId;
        this.toolName = toolName;
        this.ord = ord;
        this.requiresConfirmation = requiresConfirmation;
        this.summary = summary;
        this.arguments = arguments;
        this.status = status;
    }

    /** 执行完成(成功或失败都一样回填,失败也要能看见失败在哪)。 */
    public void complete(String status, String summary, Map<String, Object> result, long latencyMs) {
        this.status = status;
        this.summary = summary;
        this.result = result;
        this.latencyMs = (int) Math.min(Integer.MAX_VALUE, latencyMs);
    }

    /** 用户取消了这次写操作。 */
    public void reject() {
        this.status = REJECTED;
        this.result = Map.of("ok", false, "text", "用户取消了这次操作");
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getCallId() {
        return callId;
    }

    public int getOrd() {
        return ord;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getStatus() {
        return status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
