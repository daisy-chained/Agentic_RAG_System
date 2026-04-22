package com.ai.orchestrator.repository;

import com.ai.orchestrator.model.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ConversationRepositoryTest {

    @Autowired
    private ConversationRepository repository;

    @Test
    void findTop5ByUserIdOrderByCreatedAtDesc_returnsNewest5() {
        for (int i = 1; i <= 7; i++) {
            Conversation c = new Conversation("userA", "q" + i, "a" + i, i * 10L);
            // Let @PrePersist set createdAt naturally; we save in order
            repository.save(c);
        }

        List<Conversation> result = repository.findTop5ByUserIdOrderByCreatedAtDesc("userA");

        assertThat(result).hasSize(5);
        // Newest first: IDs are auto-incremented so the last 5 saved are q3..q7
        // Verify ordering: each prompt index descending
        List<String> prompts = result.stream().map(Conversation::getUserPrompt).toList();
        // Prompts should be in descending order (newest first)
        for (int i = 0; i < prompts.size() - 1; i++) {
            int cur  = Integer.parseInt(prompts.get(i).substring(1));
            int next = Integer.parseInt(prompts.get(i + 1).substring(1));
            assertThat(cur).isGreaterThan(next);
        }
    }

    @Test
    void findTop5ByUserIdOrderByCreatedAtDesc_doesNotReturnOtherUsersData() {
        repository.save(new Conversation("userA", "qA", "aA", 1L));
        repository.save(new Conversation("userB", "qB", "aB", 1L));

        List<Conversation> result = repository.findTop5ByUserIdOrderByCreatedAtDesc("userA");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo("userA");
    }

    @Test
    void findByUserIdOrderByCreatedAtAsc_returnsAllInChronologicalOrder() {
        for (int i = 1; i <= 3; i++) {
            repository.save(new Conversation("userC", "q" + i, "a" + i, i * 5L));
        }

        List<Conversation> asc = repository.findByUserIdOrderByCreatedAtAsc("userC");

        assertThat(asc).hasSize(3);
        for (int i = 0; i < asc.size() - 1; i++) {
            assertThat(asc.get(i).getCreatedAt())
                    .isBeforeOrEqualTo(asc.get(i + 1).getCreatedAt());
        }
    }
}
