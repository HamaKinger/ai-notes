package com.ainotes.note;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, UUID> {

    Page<Note> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId, Pageable pageable);

    Page<Note> findByWorkspaceIdAndDeletedAtIsNullAndIdIn(
            UUID workspaceId, Collection<UUID> ids, Pageable pageable);

    Optional<Note> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Note> findFirstByWorkspaceIdAndTitleIgnoreCaseAndDeletedAtIsNull(UUID workspaceId, String title);

    Page<Note> findByWorkspaceIdAndDeletedAtIsNullAndTitleContainingIgnoreCase(
            UUID workspaceId, String title, Pageable pageable);

    Optional<Note> findByWorkspaceIdAndSlugAndDeletedAtIsNull(UUID workspaceId, String slug);

    boolean existsByWorkspaceIdAndSlugAndDeletedAtIsNull(UUID workspaceId, String slug);

    List<Note> findByWorkspaceIdAndDeletedAtIsNull(UUID workspaceId);
}
