package com.ainotes.note;

import java.util.UUID;

/**
 * 笔记发生实质变更(内容 / 标题 / 删除)后发布的事务事件。
 *
 * <p>当前有两个消费者,都在**事务提交后**异步执行:
 * <ul>
 *   <li>{@code ingest.NoteIndexListener} —— 同步 Elasticsearch 检索索引;</li>
 *   <li>{@code graph.NoteGraphListener} —— 重建双链与标签。</li>
 * </ul>
 *
 * <p>{@code previousTitle} 用于处理改名:改名前指向旧标题的双链需要被解绑。
 */
public record NoteChangedEvent(UUID noteId, Action action, String previousTitle) {

    public enum Action {
        UPSERT,
        DELETE
    }

    public static NoteChangedEvent upsert(UUID noteId, String previousTitle) {
        return new NoteChangedEvent(noteId, Action.UPSERT, previousTitle);
    }

    public static NoteChangedEvent delete(UUID noteId, String previousTitle) {
        return new NoteChangedEvent(noteId, Action.DELETE, previousTitle);
    }
}
