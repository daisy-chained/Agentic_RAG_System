package com.ai.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a document that has been (or is being) indexed into the vector database.
 *
 * <p>The Java Control Plane owns the *metadata* for each indexed document (who uploaded
 * it, when, and what state the indexing pipeline is in). The actual chunk embeddings
 * live in the vector database managed by the Python Inference Engine.
 *
 * <p>Table: {@code document_metadata}
 */
@Entity
@Table(name = "document_metadata")
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** The userId of the user who triggered the indexing. */
    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IndexingStatus status;

    /** Number of text chunks generated from this document during indexing. */
    @Column(name = "chunk_count")
    private int chunkCount;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        uploadedAt = Instant.now();
        if (status == null) {
            status = IndexingStatus.PENDING;
        }
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public DocumentMetadata() {}

    public DocumentMetadata(String originalFilename, String uploadedBy) {
        this.originalFilename = originalFilename;
        this.uploadedBy = uploadedBy;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId()                                 { return id; }
    public String getOriginalFilename()                 { return originalFilename; }
    public void setOriginalFilename(String f)           { this.originalFilename = f; }
    public String getUploadedBy()                       { return uploadedBy; }
    public void setUploadedBy(String u)                 { this.uploadedBy = u; }
    public Instant getUploadedAt()                      { return uploadedAt; }
    public IndexingStatus getStatus()                   { return status; }
    public void setStatus(IndexingStatus status)        { this.status = status; }
    public int getChunkCount()                          { return chunkCount; }
    public void setChunkCount(int chunkCount)           { this.chunkCount = chunkCount; }
}
