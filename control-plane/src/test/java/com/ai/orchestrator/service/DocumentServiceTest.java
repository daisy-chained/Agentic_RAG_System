package com.ai.orchestrator.service;

import com.ai.orchestrator.grpc.AiAgentServiceGrpc.AiAgentServiceBlockingStub;
import com.ai.orchestrator.grpc.IndexResponse;
import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.model.IndexingStatus;
import com.ai.orchestrator.repository.DocumentMetadataRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private AiAgentServiceBlockingStub stub;

    @Mock
    private DocumentMetadataRepository documentRepository;

    private DocumentService documentService;

    private DocumentMetadata metadata;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository);
        ReflectionTestUtils.setField(documentService, "aiStub", stub);

        metadata = new DocumentMetadata("test.pdf", "user1");
        // Simulate @PrePersist having run
        ReflectionTestUtils.setField(metadata, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(metadata, "status", IndexingStatus.PENDING);
    }

    @Test
    void indexDocumentAsync_successPath_transitionsPendingToIndexedAndSetsChunkCount() {
        IndexResponse response = IndexResponse.newBuilder()
                .setStatus("SUCCESS")
                .setChunkCount(7)
                .build();
        when(stub.indexDocument(any())).thenReturn(response);
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        documentService.indexDocumentAsync(metadata, new byte[]{1, 2, 3});

        // save() called twice: once for INDEXING transition, once in finally for INDEXED
        verify(documentRepository, times(2)).save(metadata);
        assertThat(metadata.getStatus()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(metadata.getChunkCount()).isEqualTo(7);
    }

    @Test
    void indexDocumentAsync_grpcReturnsFailed_setsStatusFailed() {
        IndexResponse response = IndexResponse.newBuilder()
                .setStatus("FAILED")
                .build();
        when(stub.indexDocument(any())).thenReturn(response);
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        documentService.indexDocumentAsync(metadata, new byte[]{});

        assertThat(metadata.getStatus()).isEqualTo(IndexingStatus.FAILED);
        // save called twice: once for INDEXING, once in finally
        verify(documentRepository, times(2)).save(metadata);
    }

    @Test
    void indexDocumentAsync_statusRuntimeException_setsFailedAndSavesInFinally() {
        when(stub.indexDocument(any())).thenThrow(new StatusRuntimeException(Status.INTERNAL));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        documentService.indexDocumentAsync(metadata, new byte[]{});

        assertThat(metadata.getStatus()).isEqualTo(IndexingStatus.FAILED);
        // finally block must still save
        verify(documentRepository, times(2)).save(metadata);
    }
}
