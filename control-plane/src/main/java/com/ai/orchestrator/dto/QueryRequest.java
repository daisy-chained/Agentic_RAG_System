package com.ai.orchestrator.dto;

import java.util.List;

// Inbound DTO for a RAG query request.

public class QueryRequest {

    private String query;

    private String userId;

    /**
     * Ordered list of previous turns in the conversation, each serialised as a
     * plain
     * string in the format {@code "role: message"}.
     */
    private List<String> sessionHistory;

    public QueryRequest() {
    }

    public QueryRequest(String query, String userId, List<String> sessionHistory) {
        this.query = query;
        this.userId = userId;
        this.sessionHistory = sessionHistory;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<String> getSessionHistory() {
        return sessionHistory;
    }

    public void setSessionHistory(List<String> sessionHistory) {
        this.sessionHistory = sessionHistory;
    }
}
