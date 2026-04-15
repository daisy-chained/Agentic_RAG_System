package com.ai.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the AI Orchestrator Control Plane.
 *
 * <p>This service acts as the Java-side orchestrator in the polyglot RAG system.
 * It receives user queries via REST, forwards them to the Python inference-engine
 * over gRPC, and returns structured responses.
 */
@SpringBootApplication
@EnableAsync
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
