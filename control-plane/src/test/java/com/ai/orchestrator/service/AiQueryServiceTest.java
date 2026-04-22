package com.ai.orchestrator.service;

import com.ai.orchestrator.AiClient;
import com.ai.orchestrator.AiClient.AiClientException;
import com.ai.orchestrator.dto.QueryRequest;
import com.ai.orchestrator.dto.QueryResponse;
import com.ai.orchestrator.grpc.AgentResponse;
import com.ai.orchestrator.model.Conversation;
import com.ai.orchestrator.repository.ConversationRepository;
import com.ai.orchestrator.service.AiQueryService.QueryServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQueryServiceTest {

    @Mock
    private AiClient aiClient;

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private AiQueryService service;

    private AgentResponse sampleProto;

    @BeforeEach
    void setUp() {
        sampleProto = AgentResponse.newBuilder()
                .setAnswer("42")
                .addSourceDocuments("doc1.pdf")
                .setConfidenceScore(0.9f)
                .setLatencyMs(100)
                .build();
    }

    // ── processQuery ──────────────────────────────────────────────────────────

    @Test
    void processQuery_mapsProtoToDto() {
        QueryRequest req = new QueryRequest("What is life?", "user1", List.of());
        when(aiClient.processQuery("What is life?", "user1", List.of())).thenReturn(sampleProto);

        QueryResponse resp = service.processQuery(req);

        assertThat(resp.getAnswer()).isEqualTo("42");
        assertThat(resp.getSourceDocuments()).containsExactly("doc1.pdf");
        assertThat(resp.getConfidenceScore()).isEqualTo(0.9f);
        assertThat(resp.getLatencyMs()).isEqualTo(100L);
    }

    @Test
    void processQuery_wrapsAiClientExceptionIntoQueryServiceException() {
        QueryRequest req = new QueryRequest("fail?", "user1", List.of());
        when(aiClient.processQuery(anyString(), anyString(), anyList()))
                .thenThrow(new AiClientException("gRPC down", new RuntimeException()));

        assertThatThrownBy(() -> service.processQuery(req))
                .isInstanceOf(QueryServiceException.class)
                .hasMessageContaining("Unable to process query");
    }

    // ── processChat ───────────────────────────────────────────────────────────

    @Test
    void processChat_loadsHistoryFormatsAndPersistsTurn() {
        Conversation conv = makeConversation("hello", "world", Instant.now());
        when(conversationRepository.findTop5ByUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(List.of(conv));
        when(aiClient.processQuery(anyString(), anyString(), anyList())).thenReturn(sampleProto);

        QueryResponse resp = service.processChat("next question", "u1");

        assertThat(resp.getAnswer()).isEqualTo("42");

        // Verify history was formatted and passed
        ArgumentCaptor<List<String>> histCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).processQuery(anyString(), anyString(), histCaptor.capture());
        assertThat(histCaptor.getValue()).containsExactly("user: hello", "assistant: world");

        // Verify turn was persisted
        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    void processChat_withAiClientException_propagatesAsQueryServiceException() {
        when(conversationRepository.findTop5ByUserIdOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of());
        when(aiClient.processQuery(anyString(), anyString(), anyList()))
                .thenThrow(new AiClientException("boom", new RuntimeException()));

        assertThatThrownBy(() -> service.processChat("q", "u1"))
                .isInstanceOf(QueryServiceException.class);

        // Conversation must NOT be persisted on failure
        verify(conversationRepository, never()).save(any());
    }

    // ── buildSessionHistory ───────────────────────────────────────────────────

    @Test
    void buildSessionHistory_reversesNewestFirstToChronologicalAndInterleaves() {
        Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2024-01-01T11:00:00Z");
        Instant t3 = Instant.parse("2024-01-01T12:00:00Z");

        // DB returns newest-first
        List<Conversation> newestFirst = List.of(
                makeConversation("q3", "a3", t3),
                makeConversation("q2", "a2", t2),
                makeConversation("q1", "a1", t1)
        );
        when(conversationRepository.findTop5ByUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(newestFirst);
        when(aiClient.processQuery(anyString(), anyString(), anyList())).thenReturn(sampleProto);

        service.processChat("current", "u1");

        ArgumentCaptor<List<String>> histCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).processQuery(anyString(), anyString(), histCaptor.capture());

        List<String> hist = histCaptor.getValue();
        // Chronological order: q1 first, q3 last
        assertThat(hist).containsExactly(
                "user: q1", "assistant: a1",
                "user: q2", "assistant: a2",
                "user: q3", "assistant: a3"
        );
    }

    @Test
    void buildSessionHistory_capsAtFiveTurns() {
        // The DB query itself is capped by the repository method name (findTop5…),
        // so we simulate it returning exactly 5 rows.
        List<Conversation> fiveConvs = List.of(
                makeConversation("q5", "a5", Instant.parse("2024-01-01T15:00:00Z")),
                makeConversation("q4", "a4", Instant.parse("2024-01-01T14:00:00Z")),
                makeConversation("q3", "a3", Instant.parse("2024-01-01T13:00:00Z")),
                makeConversation("q2", "a2", Instant.parse("2024-01-01T12:00:00Z")),
                makeConversation("q1", "a1", Instant.parse("2024-01-01T11:00:00Z"))
        );
        when(conversationRepository.findTop5ByUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(fiveConvs);
        when(aiClient.processQuery(anyString(), anyString(), anyList())).thenReturn(sampleProto);

        service.processChat("now", "u1");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).processQuery(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(10); // 5 turns × 2 strings each
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Conversation makeConversation(String prompt, String response, Instant createdAt) {
        Conversation c = new Conversation("u1", prompt, response, 10L);
        // Set createdAt via reflection since @PrePersist normally sets it
        try {
            var field = Conversation.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(c, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return c;
    }
}
