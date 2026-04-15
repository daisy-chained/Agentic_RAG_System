package com.ai.orchestrator.controller;

import com.ai.orchestrator.dto.QueryRequest;
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
 * REST controller that exposes the RAG query endpoint.
 *
 * <h2>Endpoints</h2>
 * <pre>
 * POST /api/v1/query
 *   Body:    { "query": "...", "userId": "...", "sessionHistory": ["..."] }
 *   Returns: { "answer": "...", "sourceDocuments": [...],
 *              "confidenceScore": 0.95, "latencyMs": 12 }
 * </pre>
 *
 * <p>This layer performs only HTTP-level concerns (request/response mapping,
 * status codes). All business logic lives in {@link AiQueryService}.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final AiQueryService queryService;

    public QueryController(AiQueryService queryService) {
        this.queryService = queryService;
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * Accepts a user query, routes it to the Python inference engine via gRPC,
     * and returns a structured response.
     *
     * @param request the query payload; {@code query} and {@code userId} are required
     * @return {@code 200 OK} with the inference result on success,
     *         {@code 422 Unprocessable Entity} if the request body is invalid,
     *         {@code 503 Service Unavailable} if the inference engine is unreachable
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            log.warn("Rejected request — 'query' field is blank");
            return ResponseEntity.unprocessableEntity().build();
        }
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            log.warn("Rejected request — 'userId' field is blank");
            return ResponseEntity.unprocessableEntity().build();
        }

        log.info("POST /api/v1/query — userId={}", request.getUserId());
        QueryResponse response = queryService.processQuery(request);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Exception handlers
    // -------------------------------------------------------------------------

    /**
     * Translates a {@link QueryServiceException} (downstream gRPC failure) into a
     * {@code 503 Service Unavailable} with a JSON error body, so clients never
     * see a raw Spring error page.
     */
    @ExceptionHandler(QueryServiceException.class)
    public ResponseEntity<Map<String, String>> handleQueryServiceException(QueryServiceException e) {
        log.error("Returning 503 to client — reason: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }
}
