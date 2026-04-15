package com.ai.orchestrator.dto;

import java.util.List;

/**
 * Outbound DTO for a RAG query response.
 *
 * <p>Mirrors the protobuf {@code AgentResponse} fields but uses plain Java types
 * so the HTTP layer is never coupled to generated gRPC classes.
 */
public class QueryResponse {

    /** The model-generated answer to the user's query. */
    private String answer;

    /** List of source document identifiers or excerpts used to generate the answer. */
    private List<String> sourceDocuments;

    /** A confidence score in the range [0.0, 1.0] returned by the inference engine. */
    private float confidenceScore;

    /** End-to-end inference latency in milliseconds as reported by the Python engine. */
    private long latencyMs;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public QueryResponse() {}

    public QueryResponse(String answer, List<String> sourceDocuments,
                         float confidenceScore, long latencyMs) {
        this.answer = answer;
        this.sourceDocuments = sourceDocuments;
        this.confidenceScore = confidenceScore;
        this.latencyMs = latencyMs;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSourceDocuments() {
        return sourceDocuments;
    }

    public void setSourceDocuments(List<String> sourceDocuments) {
        this.sourceDocuments = sourceDocuments;
    }

    public float getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(float confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
