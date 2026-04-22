package com.ai.orchestrator;

import com.ai.orchestrator.AiClient.AiClientException;
import com.ai.orchestrator.grpc.AgentQuery;
import com.ai.orchestrator.grpc.AgentResponse;
import com.ai.orchestrator.grpc.AiAgentServiceGrpc.AiAgentServiceBlockingStub;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiClientTest {

    @Mock
    private AiAgentServiceBlockingStub stub;

    private AiClient aiClient;

    @BeforeEach
    void setUp() {
        aiClient = new AiClient();
        ReflectionTestUtils.setField(aiClient, "aiStub", stub);
    }

    @Test
    void processQuery_buildsCorrectProtoAndReturnsResponse() {
        AgentResponse expected = AgentResponse.newBuilder()
                .setAnswer("answer")
                .setConfidenceScore(0.8f)
                .setLatencyMs(50)
                .build();
        when(stub.processQuery(any(AgentQuery.class))).thenReturn(expected);

        AgentResponse actual = aiClient.processQuery("hello", "user42", List.of("user: hi", "assistant: hey"));

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<AgentQuery> captor = ArgumentCaptor.forClass(AgentQuery.class);
        verify(stub).processQuery(captor.capture());
        AgentQuery proto = captor.getValue();
        assertThat(proto.getQuery()).isEqualTo("hello");
        assertThat(proto.getUserId()).isEqualTo("user42");
        assertThat(proto.getSessionHistoryList()).containsExactly("user: hi", "assistant: hey");
    }

    @Test
    void processQuery_withNullHistory_sendsEmptyList() {
        when(stub.processQuery(any())).thenReturn(AgentResponse.getDefaultInstance());

        aiClient.processQuery("q", "u", null);

        ArgumentCaptor<AgentQuery> captor = ArgumentCaptor.forClass(AgentQuery.class);
        verify(stub).processQuery(captor.capture());
        assertThat(captor.getValue().getSessionHistoryList()).isEmpty();
    }

    @Test
    void processQuery_withStatusRuntimeException_throwsAiClientException() {
        StatusRuntimeException cause = new StatusRuntimeException(Status.UNAVAILABLE);
        when(stub.processQuery(any())).thenThrow(cause);

        assertThatThrownBy(() -> aiClient.processQuery("q", "u", List.of()))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("UNAVAILABLE")
                .hasCause(cause);
    }
}
