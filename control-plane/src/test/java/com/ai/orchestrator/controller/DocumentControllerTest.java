package com.ai.orchestrator.controller;

import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.model.IndexingStatus;
import com.ai.orchestrator.repository.DocumentMetadataRepository;
import com.ai.orchestrator.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentMetadataRepository documentRepository;

    @MockBean
    private DocumentService documentService;

    @Test
    void postDocuments_validFile_returns202WithDocumentMetadata() throws Exception {
        DocumentMetadata saved = new DocumentMetadata("report.pdf", "user1");
        ReflectionTestUtils.setField(saved, "id", UUID.fromString("11111111-1111-1111-1111-111111111111"));
        ReflectionTestUtils.setField(saved, "status", IndexingStatus.PENDING);
        ReflectionTestUtils.setField(saved, "uploadedAt", Instant.now());
        when(documentRepository.save(any())).thenReturn(saved);

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("userId", "user1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.uploadedBy").value("user1"));

        verify(documentService).indexDocumentAsync(eq(saved), any(byte[].class));
    }

    @Test
    void postDocuments_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(multipart("/api/documents")
                        .file(empty)
                        .param("userId", "user1"))
                .andExpect(status().isBadRequest());

        verify(documentService, never()).indexDocumentAsync(any(), any());
    }

    @Test
    void getDocumentsByUserId_returns200WithList() throws Exception {
        DocumentMetadata doc = new DocumentMetadata("notes.txt", "user2");
        ReflectionTestUtils.setField(doc, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(doc, "status", IndexingStatus.INDEXED);
        ReflectionTestUtils.setField(doc, "uploadedAt", Instant.now());
        when(documentRepository.findByUploadedByOrderByUploadedAtDesc("user2"))
                .thenReturn(List.of(doc));

        mockMvc.perform(get("/api/documents/user2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("notes.txt"))
                .andExpect(jsonPath("$[0].uploadedBy").value("user2"));
    }
}
