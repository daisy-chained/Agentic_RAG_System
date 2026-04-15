package com.ai.orchestrator.repository;

import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.model.IndexingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DocumentMetadata} entities.
 *
 * <p>Phase 2 provides basic lookup by owner and status. Phase 3 will add
 * methods to trigger and poll the indexing pipeline.
 */
@Repository
public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, UUID> {

    /** Returns all documents uploaded by a specific user, newest-first. */
    List<DocumentMetadata> findByUploadedByOrderByUploadedAtDesc(String uploadedBy);

    /** Returns all documents currently in a given pipeline state. */
    List<DocumentMetadata> findByStatus(IndexingStatus status);
}
