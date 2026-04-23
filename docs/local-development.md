# Local Development

[← Back to README](../README.md)

This page describes how to run each service individually on your local machine — useful when you want fast iteration on a single service without rebuilding Docker images. For the one-command containerised path, see [Getting Started](getting-started.md).

> **Prerequisites:** `init.sh` must have been run at least once. It creates the Python virtual environment, installs Node modules, and generates the initial gRPC stubs. See [Getting Started](getting-started.md) if you have not done this yet.

---

## Development Scripts

Three helper scripts live at the repo root and cover the most common development workflows.

### `./start.sh` — One-command local dev launcher

`start.sh` is the fastest way to get every service running locally. It starts infrastructure (Postgres, Qdrant, Phoenix) as Docker containers, launches the Python inference engine and the React dev server in the background, and then runs the Spring Boot control plane in the **foreground** so that `Ctrl+C` cleanly shuts everything down together.

```bash
./start.sh
```

**Requirements before running `start.sh`:**
- Docker must be running
- `inference-engine/venv` must exist (`init.sh` creates it)
- Ollama must be running separately: `ollama serve`

**Startup sequence:**

| Step | Process | Notes |
|---|---|---|
| 1 | `rag-postgres` Docker container | pgvector/pgvector:pg16, port 5432 |
| 2 | `rag-qdrant` Docker container | qdrant/qdrant:latest, ports 6333/6334 |
| 3 | `rag-phoenix` Docker container | arizephoenix/phoenix:latest, ports 6006/4317/4318 |
| 4 | Python inference engine | `inference-engine/main.py` via venv, port 50051, logs → `inference-engine.log` |
| 5 | React/Vite dev server | `frontend/`, port 5173, logs → `frontend.log` |
| 6 | Spring Boot control plane | `control-plane/`, port 8080, foreground (Ctrl+C to stop all) |

### `./build.sh` — gRPC stub regeneration

`build.sh` regenerates the Python gRPC stubs from the `.proto` file and recompiles the Java control plane. Run it after **any change** to `shared-protos/ai_service.proto` — both sides of the gRPC boundary must always stay in sync.

```bash
./build.sh
```

This is equivalent to running the following manually:

```bash
# Python stubs
source inference-engine/venv/bin/activate
python3 -m grpc_tools.protoc \
  -I./shared-protos \
  --python_out=./inference-engine \
  --grpc_python_out=./inference-engine \
  ./shared-protos/ai_service.proto

# Java stubs + compilation
cd control-plane && mvn clean compile
```

> **Version pinning:** `grpcio` and `grpcio-tools` in `requirements.txt` are pinned to match the `grpc.version` in `pom.xml`. If you bump the gRPC version, update both files together — a mismatch will cause stub incompatibilities at runtime.

---

## Control Plane (Java)

The Spring Boot control plane exposes the REST API, manages PostgreSQL persistence, and forwards AI requests to the Python inference engine over gRPC.

```bash
cd control-plane
./mvnw spring-boot:run
```

> **Note:** Protobuf stubs are generated automatically by the `protobuf-maven-plugin` during `mvn compile`. The source `.proto` is read from `../shared-protos/`, so you do not need to run `build.sh` separately before the first compile.

The control plane connects to the following services (all addresses are configurable via environment variables):

| Service | Default address | Env variable |
|---|---|---|
| PostgreSQL | `localhost:5432` | `SPRING_DATASOURCE_URL` |
| Python inference engine | `localhost:50051` | `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS` |

For a full list of configuration options, see [Configuration](configuration.md).

---

## Inference Engine (Python)

The Python inference engine is an asyncio gRPC server that handles the RAG pipeline, LLM calls, and the Reflexion self-correction loop.

```bash
cd inference-engine
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# Regenerate gRPC stubs after proto changes
python -m grpc_tools.protoc \
  -I../shared-protos \
  --python_out=. \
  --grpc_python_out=. \
  ../shared-protos/ai_service.proto

python main.py
```

The inference engine connects to the following services:

| Service | Default address | Env variable |
|---|---|---|
| Ollama | `http://localhost:11434` | `OLLAMA_HOST` |
| Qdrant | `localhost:6333` | `QDRANT_HOST` / `QDRANT_PORT` |
| Arize Phoenix (OTLP) | `localhost:4317` | Hard-coded in `main.py` — see [Configuration](configuration.md) |

---

## Frontend (React/Vite)

The React frontend is a TypeScript single-page application built with Vite. It provides the chat UI and document upload interface.

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

The Vite dev server proxies all `/api/*` requests to `http://localhost:8080` (the Spring Boot control plane). This means the frontend makes calls to its own origin (`localhost:5173/api/...`) and Vite transparently forwards them — no CORS configuration is needed in development.

**Accepted upload file types:** `.pdf`, `.md`, `.txt`

> **Note:** The frontend uses the hardcoded `userId: "dev1"` for all requests during local development. This is intentional for the current phase and should be replaced with real authentication (JWT/OAuth2) before the application is used in a multi-user or production context. See [Observability & Security](observability-security.md) for the full pre-production checklist.

---

## Related Pages

- [API Reference](api-reference.md) — full REST and gRPC API documentation
- [Configuration](configuration.md) — all environment variables and Spring Boot YAML settings
- [Ingestion Pipeline, Session History & Reflexion](ingestion-pipeline.md) — deep dive into the AI pipeline
