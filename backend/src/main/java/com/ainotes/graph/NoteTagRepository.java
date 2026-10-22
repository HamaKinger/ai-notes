package com.ainotes.graph;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteTagRepository extends JpaRepository<NoteTag, NoteTagId> {

    List<NoteTag> findByIdNoteId(UUID noteId);

    List<NoteTag> findByIdTagId(UUID tagId);

    long countByIdTagId(UUID tagId);
}
