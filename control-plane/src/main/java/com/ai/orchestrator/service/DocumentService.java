package com.ai.orchestrator.service;

import com.ai.orchestrator.grpc.AiAgentServiceGrpc;
import com.ai.orchestrator.grpc.IndexRequest;
import com.ai.orchestrator.grpc.IndexResponse;
import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.model.IndexingStatus;
import com.ai.orchestrator.repository.DocumentMetadataRepository;
import com.google.protobuf.ByteString;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Handles the asynchronous document ingestion pipeline.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    @net.devh.boot.grpc.client.inject.GrpcClient("inference-engine")
    private AiAgentServiceGrpc.AiAgentServiceBlockingStub aiStub;
    
    private final DocumentMetadataRepository documentRepository;

    public DocumentService(DocumentMetadataRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Executes the heavy gRPC indexing call asynchronously.
     * With Virtual Threads enabled in Spring Boot, @Async maps to a lightweight thread
     * instead of monopolizing a heavy OS thread from the pool.
     */
    @Async
    public void indexDocumentAsync(DocumentMetadata metadata, byte[] fileBytes) {
        log.info("Starting async indexing for document: {} (ID: {})", 
                metadata.getOriginalFilename(), metadata.getId());

        try {
            metadata.setStatus(IndexingStatus.INDEXING);
            documentRepository.save(metadata);

            IndexRequest request = IndexRequest.newBuilder()
                    .setDocumentId(metadata.getId().toString())
                    .setFilename(metadata.getOriginalFilename())
                    .setUserId(metadata.getUploadedBy())
                    .setFileContent(ByteString.copyFrom(fileBytes))
                    .build();

            // Blocking call to Python backend
            IndexResponse response = aiStub.indexDocument(request);

            if ("SUCCESS".equals(response.getStatus())) {
                metadata.setStatus(IndexingStatus.INDEXED);
                metadata.setChunkCount(response.getChunkCount());
                log.info("Successfully indexed {} chunks for {}", 
                        response.getChunkCount(), metadata.getOriginalFilename());
            } else {
                metadata.setStatus(IndexingStatus.FAILED);
                log.warn("Indexing returned FAILED status for {}", metadata.getOriginalFilename());
            }

        } catch (StatusRuntimeException e) {
            log.error("gRPC failure during indexing: {}", e.getStatus().getDescription(), e);
            metadata.setStatus(IndexingStatus.FAILED);
        } catch (Exception e) {
            log.error("Unexpected error during indexing", e);
            metadata.setStatus(IndexingStatus.FAILED);
        } finally {
            documentRepository.save(metadata);
        }
    }
}
