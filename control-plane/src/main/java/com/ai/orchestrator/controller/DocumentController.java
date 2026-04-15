package com.ai.orchestrator.controller;

import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.repository.DocumentMetadataRepository;
import com.ai.orchestrator.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentMetadataRepository documentRepository;
    private final DocumentService documentService;

    public DocumentController(
            DocumentMetadataRepository documentRepository,
            DocumentService documentService) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
    }

    /**
     * Uploads a document and starts the async indexing pipeline.
     */
    @PostMapping
    public ResponseEntity<DocumentMetadata> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId) {

        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        log.info("Received upload request: file={}, userId={}", file.getOriginalFilename(), userId);

        try {
            byte[] fileBytes = file.getBytes();
            
            // Initial save to represent PENDING state
            DocumentMetadata metadata = new DocumentMetadata(file.getOriginalFilename(), userId);
            metadata = documentRepository.save(metadata);

            // Trigger asynchronous pipeline
            documentService.indexDocumentAsync(metadata, fileBytes);

            return ResponseEntity.accepted().body(metadata);

        } catch (IOException e) {
            log.error("Failed to read file bytes", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Polling endpoint to check on indexing status for a user's uploaded documents.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<List<DocumentMetadata>> getUserDocuments(@PathVariable String userId) {
        List<DocumentMetadata> docs = documentRepository.findByUploadedByOrderByUploadedAtDesc(userId);
        return ResponseEntity.ok(docs);
    }
}
