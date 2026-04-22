package com.ai.orchestrator.integration;

import com.ai.orchestrator.AiClient;
import com.ai.orchestrator.grpc.AgentResponse;
import com.ai.orchestrator.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring context smoke test.
 * Boots the real application against a Testcontainers Postgres instance.
 * AiClient (the Spring @Service that wraps gRPC) is mocked so no Python
 * inference engine or gRPC channel is required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChatIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Explicitly override the H2 driver set in src/test/resources/application.yml
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationRepository conversationRepository;

    /**
     * Mock AiClient (the @Service bean) rather than the raw gRPC stub.
     * The gRPC stub is injected via @GrpcClient — not a standard Spring bean —
     * so @MockBean on the stub type has no effect on the real AiClient instance.
     */
    @MockBean
    private AiClient aiClient;

    @Test
    void postChat_roundTrip_persistsConversationRow() throws Exception {
        AgentResponse fakeResponse = AgentResponse.newBuilder()
                .setAnswer("Integration answer")
                .setConfidenceScore(0.95f)
                .setLatencyMs(5L)
                .build();
        when(aiClient.processQuery(anyString(), anyString(), anyList()))
                .thenReturn(fakeResponse);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"smoke test?\",\"userId\":\"integUser\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Integration answer"));

        long rowCount = conversationRepository.findByUserIdOrderByCreatedAtAsc("integUser").size();
        assertThat(rowCount).isGreaterThanOrEqualTo(1);
    }
}
