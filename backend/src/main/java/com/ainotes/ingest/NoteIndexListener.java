package com.ainotes.ingest;

import com.ainotes.note.NoteChangedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后异步触发索引同步。
 *
 * <p>放在独立 Bean 里(而不是让 {@code IndexingService} 自己监听)是为了避免自调用绕过事务代理。
 */
@Component
public class NoteIndexListener {

    private final IndexingService indexingService;

    public NoteIndexListener(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNoteChanged(NoteChangedEvent event) {
        switch (event.action()) {
            case UPSERT -> indexingService.upsert(event.noteId());
            case DELETE -> indexingService.remove(event.noteId());
        }
    }
}
