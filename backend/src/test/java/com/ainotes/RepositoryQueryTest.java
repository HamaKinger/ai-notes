package com.ainotes;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.ainotes.attachment.Attachment;
import com.ainotes.auth.AppUser;
import com.ainotes.graph.Link;
import com.ainotes.graph.NoteTag;
import com.ainotes.graph.Tag;
import com.ainotes.ingest.IndexJob;
import com.ainotes.note.Note;
import com.ainotes.note.NoteVersion;
import com.ainotes.workspace.Workspace;
import com.ainotes.workspace.WorkspaceMember;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * Spring Data 的方法名派生查询**只能在容器启动时**才会被校验。
 *
 * <p>而本项目的单元测试不启动 Spring 上下文(启动需要 PostgreSQL / Elasticsearch),
 * 所以拼错的属性名(例如把 {@code id.noteId} 写成 {@code noteId})会一直藏到运行时。
 * 这里直接用 Spring Data 自己的 {@link PartTree} 把每个派生方法名解析一遍,
 * 把这类问题提前到 `mvn test`。
 */
class RepositoryQueryTest {

    @Test
    void noteQueriesResolve() {
        assertParses(Note.class,
                "findByWorkspaceIdAndDeletedAtIsNull",
                "findByWorkspaceIdAndDeletedAtIsNullAndIdIn",
                "findByIdAndDeletedAtIsNull",
                "findFirstByWorkspaceIdAndTitleIgnoreCaseAndDeletedAtIsNull",
                "findByWorkspaceIdAndDeletedAtIsNullAndTitleContainingIgnoreCase",
                "findByWorkspaceIdAndSlugAndDeletedAtIsNull",
                "existsByWorkspaceIdAndSlugAndDeletedAtIsNull");
    }

    @Test
    void linkQueriesResolve() {
        assertParses(Link.class,
                "findBySourceNoteId",
                "findByTargetNoteId",
                "findByWorkspaceId",
                "findByWorkspaceIdAndTargetTitleIgnoreCase",
                "deleteBySourceNoteId");
    }

    @Test
    void tagQueriesResolve() {
        assertParses(Tag.class,
                "findByWorkspaceIdAndNameIgnoreCase",
                "findByWorkspaceId");
    }

    /**
     * note_tag 是复合主键,属性路径写成 id.noteId —— 这种写法最容易拼错。
     *
     * <p>这里**故意不列** `deleteByNoteId`:根属性是 {@code id},那种写法会让 Spring Data
     * 在启动时抛 `No property 'noteId' found for type 'NoteTag'`。
     * 相关清理逻辑改用 {@code findByIdNoteId + deleteAll} 实现。
     */
    @Test
    void noteTagQueriesResolve() {
        assertParses(NoteTag.class,
                "findByIdNoteId",
                "findByIdTagId",
                "countByIdTagId");
    }

    @Test
    void workspaceQueriesResolve() {
        assertParses(Workspace.class, "findAllByIdIn");
        assertParses(WorkspaceMember.class, "findByIdUserId", "countByIdWorkspaceId");
    }

    @Test
    void otherQueriesResolve() {
        assertParses(NoteVersion.class,
                "findByNoteIdOrderByVersionDesc",
                "findByNoteIdAndVersion",
                "countByNoteId");
        assertParses(Attachment.class, "findByNoteIdOrderByCreatedAtDesc");
        assertParses(AppUser.class, "findByEmailIgnoreCase", "existsByEmailIgnoreCase");
        assertParses(IndexJob.class, "findTop50ByStatusOrderByCreatedAtAsc");
    }

    private static void assertParses(Class<?> entity, String... methodNames) {
        for (String methodName : methodNames) {
            assertThatCode(() -> new PartTree(methodName, entity))
                    .as("%s :: %s", entity.getSimpleName(), methodName)
                    .doesNotThrowAnyException();
        }
    }
}
