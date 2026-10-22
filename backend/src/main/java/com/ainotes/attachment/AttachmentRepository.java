package com.ainotes.attachment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByNoteIdOrderByCreatedAtDesc(UUID noteId);
}
