package com.ainotes.memory;

import com.ainotes.common.NotFoundException;
import com.ainotes.ingest.ChunkEmbeddingService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 长期记忆(M5 Step 3):用户明确让 Agent 记住的事实,之后每次提问按语义召回、注入系统提示词。
 *
 * <p>几条约定:
 * <ul>
 *   <li><b>只有显式写入</b>:记忆由 {@code rememberFact} 工具产生,而它是写操作,要用户点确认才落库。
 *       不从对话里自动抽取 —— 那会悄悄记下用户不想让它记的东西。</li>
 *   <li><b>去重</b>:新事实与已有记忆几乎一样(相似度 ≥ {@code dedup-score})就不再记一条。</li>
 *   <li><b>召回降级不影响提问</b>:嵌入服务不可用时召回返回空并打 WARN,这一轮照常回答,只是没有记忆。
 *       写入则相反 —— 嵌入不可用时直接报错,不写一条没有向量、以后永远召不回的记忆。</li>
 * </ul>
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 一条记忆的字数上限:记忆是一句陈述,长了就不是"事实",而是一段要被反复注入的文本 */
    static final int MAX_CHARS = 300;

    private final MemoryRepository memories;
    private final MemoryIndexStore index;
    private final ChunkEmbeddingService embeddings;
    private final int recallTopK;
    private final double recallMinScore;
    private final double dedupScore;

    public MemoryService(MemoryRepository memories,
                         MemoryIndexStore index,
                         ChunkEmbeddingService embeddings,
                         @Value("${ai-notes.memory.recall-top-k:5}") int recallTopK,
                         @Value("${ai-notes.memory.recall-min-score:0.7}") double recallMinScore,
                         @Value("${ai-notes.memory.dedup-score:0.95}") double dedupScore) {
        this.memories = memories;
        this.index = index;
        this.embeddings = embeddings;
        this.recallTopK = Math.max(1, recallTopK);
        this.recallMinScore = recallMinScore;
        this.dedupScore = dedupScore;
    }

    /** 写入的结果:{@code created=false} 表示已经有几乎一样的记忆,{@code content} 是那条已有的。 */
    public record Outcome(boolean created, String content) {
    }

    /**
     * 记下一条事实。
     *
     * @throws IllegalArgumentException 内容为空或超长
     * @throws IllegalStateException    嵌入或索引不可用(此时不写 PG,避免留下召不回的半条记忆)
     */
    public Outcome remember(UUID workspaceId, String fact) {
        String text = fact == null ? "" : fact.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("要记住的内容不能为空");
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("一条记忆最多 " + MAX_CHARS + " 字,请写成一句话");
        }
        float[] vector = embeddings.embedOne(text);

        List<MemoryIndexStore.Hit> similar = index.knn(workspaceId, vector, 1);
        if (!similar.isEmpty() && similar.get(0).score() >= dedupScore) {
            return new Outcome(false, similar.get(0).content());
        }

        Memory saved = memories.save(new Memory(workspaceId, text));
        try {
            index.index(saved.getId(), workspaceId, text, vector);
        } catch (RuntimeException indexFailed) {
            // 向量没写进去就回滚 PG 那一行,否则会留下一条永远召不回的"僵尸"记忆
            memories.delete(saved);
            throw indexFailed;
        }
        return new Outcome(true, text);
    }

    /**
     * 按本次提问召回相关记忆(按相似度从高到低,低于 {@code recall-min-score} 的丢弃)。
     * 任何失败都返回空列表 —— 召回是锦上添花,不能让提问本身失败。
     */
    public List<String> recallFor(UUID workspaceId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] vector = embeddings.embedOne(query.strip());
            return index.knn(workspaceId, vector, recallTopK).stream()
                    .filter(hit -> hit.score() >= recallMinScore)
                    .map(MemoryIndexStore.Hit::content)
                    .toList();
        } catch (RuntimeException unavailable) {
            log.warn("长期记忆召回失败,本次回答不带记忆: {}", unavailable.getMessage());
            return List.of();
        }
    }

    public List<Memory> list(UUID workspaceId) {
        return memories.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /** 先删 ES 再删 PG:ES 失败时 PG 那行还在,用户可以再点一次,不会出现"界面没了、向量还在"。 */
    public void forget(UUID workspaceId, UUID memoryId) {
        Memory memory = memories.findByIdAndWorkspaceId(memoryId, workspaceId)
                .orElseThrow(() -> new NotFoundException("记忆不存在:" + memoryId));
        index.delete(memory.getId());
        memories.delete(memory);
    }
}
