package com.ainotes.graph;

import com.ainotes.note.NoteChangedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后重建双链与标签。
 *
 * <p>和 {@code NoteIndexListener} 一样:失败不影响笔记本身,只影响关系面板。
 */
@Component
public class NoteGraphListener {

    private final NoteGraphService graphService;

    public NoteGraphListener(NoteGraphService graphService) {
        this.graphService = graphService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoteChanged(NoteChangedEvent event) {
        switch (event.action()) {
            case UPSERT -> graphService.rebuild(event.noteId(), event.previousTitle());
            case DELETE -> graphService.remove(event.noteId());
        }
    }
}
