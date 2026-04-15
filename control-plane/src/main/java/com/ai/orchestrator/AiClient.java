package com.ai.orchestrator;

import com.ai.orchestrator.grpc.AgentQuery;
import com.ai.orchestrator.grpc.AgentResponse;
import com.ai.orchestrator.grpc.AiAgentServiceGrpc.AiAgentServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Low-level gRPC client for the Python inference-engine.
 *
 * <p>This class owns the channel lifecycle (managed by the grpc-spring-boot-starter)
 * and is the only place in the codebase that depends directly on generated protobuf
 * types. All callers above this layer work with the plain {@code dto.*} types.
 *
 * <p>The channel name {@code "inference-engine"} is resolved from
 * {@code application.yml → grpc.client.inference-engine.*}.
 */
@Service
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    @GrpcClient("inference-engine")
    private AiAgentServiceBlockingStub aiStub;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a query to the inference engine and returns the raw proto response.
     *
     * @param query          the user's natural-language question
     * @param userId         opaque caller identifier used for tracing
     * @param sessionHistory ordered list of prior conversation turns
     * @return the {@link AgentResponse} proto from the Python engine
     * @throws AiClientException if the gRPC call fails for any reason
     */
    public AgentResponse processQuery(String query, String userId, List<String> sessionHistory) {
        log.info("Sending gRPC ProcessQuery — userId={} queryLength={}", userId, query.length());

        AgentQuery request = AgentQuery.newBuilder()
                .setQuery(query)
                .setUserId(userId)
                .addAllSessionHistory(sessionHistory != null ? sessionHistory : Collections.emptyList())
                .build();

        try {
            AgentResponse response = aiStub.processQuery(request);
            log.info("gRPC ProcessQuery succeeded — latencyMs={} confidence={}",
                    response.getLatencyMs(), response.getConfidenceScore());
            return response;
        } catch (StatusRuntimeException e) {
            log.error("gRPC call failed — status={} description={}",
                    e.getStatus().getCode(), e.getStatus().getDescription(), e);
            throw new AiClientException(
                    "Inference engine call failed: " + e.getStatus().getCode(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Smoke-test helper (used in dev / integration tests only)
    // -------------------------------------------------------------------------

    /**
     * Fires a minimal ping to verify the channel is reachable.
     * Not intended for production traffic.
     */
    public String testConnection() {
        AgentResponse response = processQuery("Ping", "smoke-test", Collections.emptyList());
        return response.getAnswer();
    }

    // -------------------------------------------------------------------------
    // Domain exception
    // -------------------------------------------------------------------------

    /**
     * Wraps gRPC transport errors so callers above this layer never need to
     * import {@code io.grpc} directly.
     */
    public static class AiClientException extends RuntimeException {
        public AiClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
