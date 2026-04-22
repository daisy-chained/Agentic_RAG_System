package com.ai.orchestrator.controller;

import com.ai.orchestrator.dto.QueryResponse;
import com.ai.orchestrator.service.AiQueryService;
import com.ai.orchestrator.service.AiQueryService.QueryServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiQueryService queryService;

    @Test
    void postChat_validBody_returns200WithQueryResponse() throws Exception {
        QueryResponse resp = new QueryResponse("The answer", List.of("doc.pdf"), 0.9f, 42L);
        when(queryService.processChat("What is life?", "user1")).thenReturn(resp);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What is life?\",\"userId\":\"user1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("The answer"))
                .andExpect(jsonPath("$.sourceDocuments[0]").value("doc.pdf"))
                .andExpect(jsonPath("$.latencyMs").value(42));
    }

    @Test
    void postChat_blankQuery_returns422() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"userId\":\"user1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postChat_nullQuery_returns422() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postChat_blankUserId_returns422() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\",\"userId\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void postChat_serviceThrowsQueryServiceException_returns503WithErrorField() throws Exception {
        when(queryService.processChat("q", "u"))
                .thenThrow(new QueryServiceException("Engine down", new RuntimeException()));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\",\"userId\":\"u\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Engine down"));
    }
}
