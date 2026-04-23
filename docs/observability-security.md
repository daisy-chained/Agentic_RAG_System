# Observability & Security

[← Back to README](../README.md)

---

## Observability

The inference engine is fully instrumented with **OpenTelemetry** via the **OpenInference** auto-instrumentation library. Every LangChain call — retrieval, first-pass generation, and the Reflexion grading call — is captured as a span and exported to the **Arize Phoenix** collector that runs as part of the Docker Compose stack.

### Accessing the dashboard

| Interface | URL |
|---|---|
| Arize Phoenix UI | http://localhost:6006 |
| OTLP gRPC collector | localhost:4317 |
| OTLP HTTP collector | localhost:4318 |

Open the Phoenix UI at `http://localhost:6006` to explore traces. Each trace corresponds to a single `POST /api/chat` or `POST /api/v1/query` request and contains a span tree covering:

- **Full query latency** — end-to-end time from gRPC receipt to response
- **Qdrant retrieval** — number and content of the top-3 retrieved chunks
- **First-pass LLM call** — full input message list and output
- **Reflexion grading call** — the grader prompt and whether it responded `APPROVE` or rewrote the answer
- **Self-correction flag** — look for the `[Reflexion Self-Corrected]` prefix in the answer span to identify requests where the loop triggered a rewrite

### OTLP endpoint

The OTLP exporter in `inference-engine/main.py` is currently hard-coded to `http://localhost:4317`. This works for both local development and Docker Compose (where `extra_hosts` maps `localhost` correctly), but it should be made configurable via an environment variable before deploying to any environment where Phoenix runs on a different host.

### Extending observability

Because the instrumentation uses standard OpenTelemetry, traces can be redirected to any OTLP-compatible backend (Jaeger, Grafana Tempo, Honeycomb, etc.) by changing the exporter endpoint. No code changes are needed — only the collector address.

---

## Security

The current security posture is a **Phase 1 open configuration** designed for local development. Every item below must be addressed before the stack is exposed externally or used in a multi-user context.

### Current state

`SecurityConfig.java` permits all HTTP requests and disables CSRF protection. This is intentional — it removes authentication friction while the core AI pipeline is being developed and tested locally.

### Pre-production checklist

Work through the following items before exposing the service externally:

1. **Replace `anyRequest().permitAll()`** with a JWT / OAuth2 resource-server configuration. Spring Security's `oauth2ResourceServer()` DSL is the natural next step for a Spring Boot 3 application.

2. **Re-enable CSRF protection** if the API will be consumed by browser sessions that use cookies for authentication. The current setup (React frontend + Bearer token) does not require CSRF protection, but re-evaluate this when auth is added.

3. **Replace the hardcoded PostgreSQL password** (`secret`) with a secret managed by your deployment tooling — for example, a Kubernetes Secret, Docker Swarm secret, or a secrets manager such as HashiCorp Vault or AWS Secrets Manager.

4. **Replace the hardcoded `userId: "dev1"`** in the React frontend (`frontend/src/App.tsx`) with a real identity from an auth provider. All documents and conversations are currently scoped to this single dev user.

5. **Add rate limiting** at the API gateway layer to protect Ollama from unbounded concurrent requests. Ollama processes requests sequentially by default; a burst of concurrent chat requests will queue up and cause timeouts without a rate limiter in front.

6. **Enable TLS on the gRPC channel** if the control plane and inference engine will run on separate hosts. The current `negotiation-type: plaintext` in `application.yml` is appropriate only for co-located services on a trusted network.

---

## Related Pages

- [Configuration](configuration.md) — environment variables including the hardcoded OTLP endpoint
- [Project Structure, Tech Stack & Roadmap](project-structure.md) — full list of known limitations
