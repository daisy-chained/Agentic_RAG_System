package com.ai.orchestrator.repository;

import com.ai.orchestrator.model.DocumentMetadata;
import com.ai.orchestrator.model.IndexingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DocumentMetadataRepositoryTest {

    @Autowired
    private DocumentMetadataRepository repository;

    @Test
    void findByUploadedByOrderByUploadedAtDesc_returnsOnlyMatchingUserNewestFirst() {
        repository.save(new DocumentMetadata("first.pdf", "alice"));
        repository.save(new DocumentMetadata("second.pdf", "alice"));
        repository.save(new DocumentMetadata("other.txt", "bob"));

        List<DocumentMetadata> result = repository.findByUploadedByOrderByUploadedAtDesc("alice");

        assertThat(result).hasSize(2);
        result.forEach(d -> assertThat(d.getUploadedBy()).isEqualTo("alice"));
        // Newest first
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).getUploadedAt())
                    .isAfterOrEqualTo(result.get(i + 1).getUploadedAt());
        }
    }

    @Test
    void findByUploadedByOrderByUploadedAtDesc_emptyResultForUnknownUser() {
        assertThat(repository.findByUploadedByOrderByUploadedAtDesc("nobody")).isEmpty();
    }

    @Test
    void findByStatus_returnsOnlyMatchingStatus() {
        DocumentMetadata pending = new DocumentMetadata("a.pdf", "user1");
        DocumentMetadata indexed = new DocumentMetadata("b.pdf", "user1");
        pending = repository.save(pending);
        indexed = repository.save(indexed);
        indexed.setStatus(IndexingStatus.INDEXED);
        repository.save(indexed);

        List<DocumentMetadata> pendingDocs = repository.findByStatus(IndexingStatus.PENDING);
        assertThat(pendingDocs).hasSize(1);
        assertThat(pendingDocs.get(0).getOriginalFilename()).isEqualTo("a.pdf");
    }
}
