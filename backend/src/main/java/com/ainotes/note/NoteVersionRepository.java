package com.ainotes.note;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteVersionRepository extends JpaRepository<NoteVersion, UUID> {

    List<NoteVersion> findByNoteIdOrderByVersionDesc(UUID noteId);

    Optional<NoteVersion> findByNoteIdAndVersion(UUID noteId, int version);

    long countByNoteId(UUID noteId);
}
