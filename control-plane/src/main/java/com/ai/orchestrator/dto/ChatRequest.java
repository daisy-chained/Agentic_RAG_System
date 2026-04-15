package com.ai.orchestrator.dto;

/**
 * Inbound DTO for the {@code POST /api/chat} production gateway.
 *
 * <p>A Java 21 record — immutable, compact, and serialisable by Jackson
 * without any additional configuration.
 *
 * <p>Session history is intentionally absent here: it is loaded automatically
 * from the {@code conversations} table by
 * {@link com.ai.orchestrator.service.AiQueryService#processChat}, so the
 * client only needs to send the current turn.
 */
public record ChatRequest(String query, String userId) {}
