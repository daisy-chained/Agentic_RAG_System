package com.ai.orchestrator.repository;

import com.ai.orchestrator.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Conversation} entities.
 *
 * <p>The derived query methods follow Spring Data naming conventions and require
 * no implementation — the SQL is generated automatically at startup.
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Returns the 5 most recent conversation turns for a given user, ordered
     * newest-first. Used to build the rolling context window for the LLM.
     *
     * <p>The results are re-sorted to chronological order
     * (oldest-first) inside {@link com.ai.orchestrator.service.AiQueryService}
     * before being sent to the inference engine.
     */
    List<Conversation> findTop5ByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Returns the full conversation history for a user in chronological order.
     * Useful for audit and export — not used in the hot path.
     */
    List<Conversation> findByUserIdOrderByCreatedAtAsc(String userId);
}
