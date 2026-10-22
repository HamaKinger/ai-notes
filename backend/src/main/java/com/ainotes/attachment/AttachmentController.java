package com.ainotes.attachment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping("/notes/{noteId}/attachments")
    public AttachmentResponse upload(@PathVariable UUID noteId, @RequestParam("file") MultipartFile file) {
        return AttachmentResponse.from(service.store(noteId, file));
    }

    @GetMapping("/notes/{noteId}/attachments")
    public List<AttachmentResponse> list(@PathVariable UUID noteId) {
        return service.listByNote(noteId).stream().map(AttachmentResponse::from).toList();
    }

    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        Attachment attachment = service.get(id);
        Resource resource = new FileSystemResource(service.resolve(attachment));

        MediaType mediaType = attachment.getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(attachment.getMimeType());

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.getFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    public record AttachmentResponse(
            UUID id,
            UUID noteId,
            String filename,
            String mimeType,
            Long sizeBytes,
            Instant createdAt) {

        static AttachmentResponse from(Attachment attachment) {
            return new AttachmentResponse(
                    attachment.getId(),
                    attachment.getNoteId(),
                    attachment.getFilename(),
                    attachment.getMimeType(),
                    attachment.getSizeBytes(),
                    attachment.getCreatedAt());
        }
    }
}
