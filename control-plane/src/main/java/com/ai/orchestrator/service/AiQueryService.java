package com.ai.orchestrator.service;

import com.ai.orchestrator.AiClient;
import com.ai.orchestrator.AiClient.AiClientException;
import com.ai.orchestrator.dto.QueryRequest;
import com.ai.orchestrator.dto.QueryResponse;
import com.ai.orchestrator.grpc.AgentResponse;
import com.ai.orchestrator.model.Conversation;
import com.ai.orchestrator.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Application-layer service that orchestrates all RAG query operations.
 *
 * <h2>Two entry points</h2>
 * <ul>
 *   <li>{@link #processQuery(QueryRequest)} — raw pass-through used by the debug
 *       endpoint {@code /api/v1/query}. Does <em>not</em> persist the exchange.</li>
 *   <li>{@link #processChat(String, String)} — production path used by
 *       {@code /api/chat}. Loads session history from Postgres, calls gRPC, and
 *       persists the completed turn.</li>
 * </ul>
 *
 * <p>This is the only class that knows both the DTO layer and the gRPC layer.
 * Controllers only see DTOs; {@link AiClient} only sees proto types.
 */
@Service
public class AiQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiQueryService.class);

    /** Maximum number of past turns to include as session context. */
    private static final int MAX_HISTORY_TURNS = 5;

    private final AiClient aiClient;
    private final ConversationRepository conversationRepository;

    public AiQueryService(AiClient aiClient, ConversationRepository conversationRepository) {
        this.aiClient = aiClient;
        this.conversationRepository = conversationRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Raw pass-through query. Calls gRPC but does <em>not</em> load or persist
     * conversation history. Useful as a debug/smoke-test endpoint.
     *
     * @param request the inbound DTO (includes explicit sessionHistory if provided)
     * @return the mapped response DTO
     * @throws QueryServiceException if the downstream gRPC call fails
     */
    public QueryResponse processQuery(QueryRequest request) {
        log.debug("processQuery — userId={}", request.getUserId());
        try {
            AgentResponse grpcResponse = aiClient.processQuery(
                    request.getQuery(),
                    request.getUserId(),
                    request.getSessionHistory()
            );
            return toDto(grpcResponse);

        } catch (AiClientException e) {
            log.error("Query failed for userId={}", request.getUserId(), e);
            throw new QueryServiceException("Unable to process query at this time.", e);
        }
    }

    /**
     * Production chat path.
     *
     * <ol>
     *   <li>Loads the last {@value #MAX_HISTORY_TURNS} conversation turns from Postgres
     *       and formats them as {@code "role: message"} strings for the LLM context window.</li>
     *   <li>Forwards the query + history to the Python inference engine via gRPC.</li>
     *   <li>Saves the completed turn to Postgres so future calls have more context.</li>
     * </ol>
     *
     * @param query  the user's current message
     * @param userId used to scope the conversation history in Postgres
     * @return the mapped response DTO
     * @throws QueryServiceException if the downstream gRPC call fails
     */
    public QueryResponse processChat(String query, String userId) {
        log.debug("processChat — userId={}", userId);

        List<String> sessionHistory = buildSessionHistory(userId);
        log.debug("Loaded {} history strings for userId={}", sessionHistory.size(), userId);

        try {
            AgentResponse grpcResponse = aiClient.processQuery(query, userId, sessionHistory);
            QueryResponse dto = toDto(grpcResponse);

            // Persist the completed turn so subsequent calls have richer context
            conversationRepository.save(
                    new Conversation(userId, query, dto.getAnswer(), dto.getLatencyMs())
            );

            return dto;

        } catch (AiClientException e) {
            log.error("Chat failed for userId={}", userId, e);
            throw new QueryServiceException("Unable to process chat at this time.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reconstructs the rolling context window from Postgres.
     *
     * <p>Fetches the {@value #MAX_HISTORY_TURNS} most recent turns (newest-first from
     * the DB), reverses them to chronological order, then interleaves each turn into
     * a pair of strings: {@code "user: <prompt>"} and {@code "assistant: <response>"}.
     * This matches the format expected by the Python gRPC servicer when it builds the
     * Gemini chat history.
     */
    private List<String> buildSessionHistory(String userId) {
        return conversationRepository
                .findTop5ByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .sorted(Comparator.comparing(Conversation::getCreatedAt))   // oldest → newest
                .flatMap(c -> Stream.of(
                        "user: " + c.getUserPrompt(),
                        "assistant: " + c.getAiResponse()
                ))
                .toList();
    }

    private QueryResponse toDto(AgentResponse proto) {
        return new QueryResponse(
                proto.getAnswer(),
                proto.getSourceDocumentsList(),
                proto.getConfidenceScore(),
                proto.getLatencyMs()
        );
    }

    // -------------------------------------------------------------------------
    // Domain exception
    // -------------------------------------------------------------------------

    /**
     * Wraps all downstream failures so the REST layer never imports gRPC types.
     */
    public static class QueryServiceException extends RuntimeException {
        public QueryServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
