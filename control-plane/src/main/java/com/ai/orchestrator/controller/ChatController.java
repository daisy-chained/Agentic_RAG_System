package com.ai.orchestrator.controller;

import com.ai.orchestrator.dto.ChatRequest;
import com.ai.orchestrator.dto.QueryResponse;
import com.ai.orchestrator.service.AiQueryService;
import com.ai.orchestrator.service.AiQueryService.QueryServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Production gateway for all AI chat interactions.
 *
 * <h2>Endpoint</h2>
 * <pre>
 * POST /api/chat
 *   Body:    { "query": "...", "userId": "..." }
 *   Returns: { "answer": "...", "sourceDocuments": [...],
 *              "confidenceScore": 0.9, "latencyMs": 42 }
 * </pre>
 *
 * <p>Unlike the debug endpoint {@code /api/v1/query}, this controller:
 * <ul>
 *   <li>Automatically loads the user's conversation history from PostgreSQL</li>
 *   <li>Sends the history as context to the LLM for a coherent multi-turn dialogue</li>
 *   <li>Persists each completed exchange back to PostgreSQL</li>
 * </ul>
 *
 * <p>This controller is intentionally thin — all orchestration logic lives in
 * {@link AiQueryService#processChat}.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AiQueryService queryService;

    public ChatController(AiQueryService queryService) {
        this.queryService = queryService;
    }

    // -------------------------------------------------------------------------
    // Endpoint
    // -------------------------------------------------------------------------

    /**
     * Accepts a user message, retrieves conversation context from Postgres, forwards
     * the enriched request to the Python inference engine via gRPC, persists the
     * exchange, and returns the AI response.
     *
     * @param request the chat payload; both {@code query} and {@code userId} are required
     * @return {@code 200 OK} on success,
     *         {@code 422 Unprocessable Entity} if fields are blank,
     *         {@code 503 Service Unavailable} if the inference engine is unreachable
     */
    @PostMapping("/chat")
    public ResponseEntity<QueryResponse> chat(@RequestBody ChatRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            log.warn("Rejected chat request — 'query' is blank");
            return ResponseEntity.unprocessableEntity().build();
        }
        if (request.userId() == null || request.userId().isBlank()) {
            log.warn("Rejected chat request — 'userId' is blank");
            return ResponseEntity.unprocessableEntity().build();
        }

        log.info("POST /api/chat — userId={}", request.userId());
        QueryResponse response = queryService.processChat(request.query(), request.userId());
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Exception handlers
    // -------------------------------------------------------------------------

    /** Maps a downstream gRPC failure to a clean {@code 503} JSON response. */
    @ExceptionHandler(QueryServiceException.class)
    public ResponseEntity<Map<String, String>> handleServiceException(QueryServiceException e) {
        log.error("Returning 503 — reason: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }
}
