# Configuration

[← Back to README](../README.md)

All runtime configuration is driven by environment variables. The `.env` file at the repository root is the central place to set them — it is read by both Docker Compose and the Python `python-dotenv` library, so the same file covers both the containerised stack and local development.

`init.sh` generates `.env` automatically based on detected hardware (GPU vendor, VRAM, RAM). If you need to override a value — for example, to point the stack at an external Ollama server or a different database — edit `.env` before running `docker compose up`.

---

## Inference Engine (Python)

These variables are consumed by `inference-engine/main.py` and `inference-engine/rag.py`.

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama server URL. Inside Docker Compose this must be `http://host.docker.internal:11434` to reach Ollama on the host machine — Docker Compose sets this automatically via `extra_hosts` (see [Linux Host Networking](#linux-host-networking)) |
| `OLLAMA_MODEL` | `llama3.2` | The Ollama chat model tag to use. Set by `init.sh` based on available VRAM/RAM. Change this to switch models without touching the code |
| `OLLAMA_NUM_CTX` | `4096` | LLM context window in tokens. Set by `init.sh` based on VRAM/RAM budget. Larger values improve multi-turn coherence at the cost of memory and latency |
| `QDRANT_HOST` | `localhost` | Qdrant hostname. Inside Docker Compose this is `qdrant` (the service name) |
| `QDRANT_PORT` | `6333` | Qdrant REST port |

---

## Control Plane (Java / Spring Boot)

These variables are consumed by the Spring Boot application. They can be set in `.env` (Docker Compose passes them through) or as standard `SPRING_*` / `GRPC_*` environment variables in any deployment environment.

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/orchestrator` | PostgreSQL JDBC URL. Inside Docker Compose this is `jdbc:postgresql://postgres:5432/orchestrator` |
| `SPRING_DATASOURCE_USERNAME` | `orchestrator` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | `secret` | PostgreSQL password. **Replace with a strong secret before any non-local deployment** |
| `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS` | `static://localhost:50051` | gRPC target for the inference engine. Inside Docker Compose this is `static://inference-engine:50051`. The channel name `inference-engine` must match the `@GrpcClient("inference-engine")` annotation in `AiClient.java` and `DocumentService.java` |

---

## Spring Boot YAML Settings

Key settings are declared in `control-plane/src/main/resources/application.yml`. Environment variables (above) override these at runtime.

### Java 21 Virtual Threads

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Delegates all Tomcat request threads to the JVM's virtual thread scheduler. This is the single biggest concurrency win in Java 21+ — it allows the server to handle many concurrent AI requests (which can be slow) without exhausting a fixed-size thread pool. Because virtual threads are cheap to create, the control plane can park hundreds of blocked gRPC calls simultaneously without degrading responsiveness.

### JPA / Hibernate

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

Tables are auto-created on startup and dropped on shutdown. This keeps the schema in sync with JPA entity definitions without a migration tool, at the cost of losing all data on every restart. **Change to `validate` and add Flyway migrations before any deployment where data must persist across restarts.**

### gRPC Client

```yaml
grpc:
  client:
    inference-engine:
      address: 'static://localhost:50051'
      negotiation-type: plaintext
```

The channel name `inference-engine` is referenced in `@GrpcClient("inference-engine")` annotations in the Java source. The address is overridden at runtime via `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS`. `negotiation-type: plaintext` means there is no TLS on the internal gRPC channel — add TLS termination at the infrastructure layer before exposing these services externally.

---

## Linux Host Networking

On Linux, `host.docker.internal` is **not** resolved automatically inside Docker containers (unlike on macOS and Windows). The `compose.yaml` handles this by adding an `extra_hosts` entry to the `inference-engine` service:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

This maps `host.docker.internal` to the host machine's gateway IP so the Python container can reach Ollama running on the host. This is configured automatically — no manual action is required.

---

## Related Pages

- [Getting Started](getting-started.md) — how `init.sh` generates the initial `.env`
- [Observability & Security](observability-security.md) — security-relevant configuration items
- [Data Model](data-model.md) — database schema details
