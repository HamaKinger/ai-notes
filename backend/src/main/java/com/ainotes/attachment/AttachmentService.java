package com.ainotes.attachment;

import com.ainotes.common.NotFoundException;
import com.ainotes.note.NoteService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件系统附件存储。
 *
 * <p>M1 先用本地目录(默认 {@code ./data/attachments/}),后续要换 S3/MinIO 只需替换本类。
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final NoteService notes;
    private final Path root;

    public AttachmentService(AttachmentRepository attachments,
                             NoteService notes,
                             @Value("${ai-notes.storage.root:./data}") String storageRoot) {
        this.attachments = attachments;
        this.notes = notes;
        this.root = Path.of(storageRoot).resolve("attachments").toAbsolutePath().normalize();
    }

    @Transactional
    public Attachment store(UUID noteId, MultipartFile file) {
        notes.get(noteId);

        UUID id = UUID.randomUUID();
        String filename = safeFilename(file.getOriginalFilename());
        String storageKey = id + "/" + filename;
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件名: " + filename);
        }

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("保存附件失败: " + e.getMessage(), e);
        }

        return attachments.save(new Attachment(
                id, noteId, filename, file.getContentType(), file.getSize(), storageKey));
    }

    @Transactional(readOnly = true)
    public List<Attachment> listByNote(UUID noteId) {
        return attachments.findByNoteIdOrderByCreatedAtDesc(noteId);
    }

    @Transactional(readOnly = true)
    public Attachment get(UUID id) {
        return attachments.findById(id)
                .orElseThrow(() -> new NotFoundException("attachment not found: " + id));
    }

    /** 解析出磁盘上的真实路径,并再次校验没有越出存储根目录。 */
    public Path resolve(Attachment attachment) {
        Path target = root.resolve(attachment.getStorageKey()).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) {
            throw new NotFoundException("附件文件已丢失: " + attachment.getFilename());
        }
        return target;
    }

    private static String safeFilename(String original) {
        String cleaned = StringUtils.cleanPath(original == null ? "file" : original);
        cleaned = cleaned.replace("..", "_");
        if (cleaned.isBlank()) {
            return "file";
        }
        return cleaned;
    }
}
