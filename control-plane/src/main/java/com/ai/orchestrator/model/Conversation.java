package com.ai.orchestrator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists a single turn of a user conversation.
 *
 * <p>Each row captures the user's prompt and the AI's response together so that
 * the session context can be reconstructed as a chronological list of turns.
 * {@link com.ai.orchestrator.service.AiQueryService} writes a new record after
 * every successful {@code ProcessQuery} gRPC call.
 *
 * <p>Table: {@code conversations}
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "user_prompt", nullable = false, columnDefinition = "TEXT")
    private String userPrompt;

    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Round-trip latency in milliseconds reported by the inference engine. */
    @Column(name = "latency_ms")
    private long latencyMs;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public Conversation() {}

    public Conversation(String userId, String userPrompt, String aiResponse, long latencyMs) {
        this.userId = userId;
        this.userPrompt = userPrompt;
        this.aiResponse = aiResponse;
        this.latencyMs = latencyMs;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID getId()                     { return id; }
    public String getUserId()               { return userId; }
    public void setUserId(String userId)    { this.userId = userId; }
    public String getUserPrompt()           { return userPrompt; }
    public void setUserPrompt(String p)     { this.userPrompt = p; }
    public String getAiResponse()           { return aiResponse; }
    public void setAiResponse(String r)     { this.aiResponse = r; }
    public Instant getCreatedAt()           { return createdAt; }
    public long getLatencyMs()              { return latencyMs; }
    public void setLatencyMs(long ms)       { this.latencyMs = ms; }
}
