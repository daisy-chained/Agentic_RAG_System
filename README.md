# Agentic RAG System

A context-aware, polyglot **Retrieval-Augmented Generation** platform built for developer advocacy workflows. It combines a Java orchestration layer, a Python inference engine, and a React frontend — all wired together via gRPC and deployed as a single Docker Compose stack. This is a work in progress, with no active support.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Key Features](#key-features)
3. [Prerequisites](#prerequisites)
4. [Getting Started — First-Time Setup (`init.sh`)](#getting-started--first-time-setup-initsh)
5. [Quick Start — Docker Compose](#quick-start--docker-compose)
6. [Local Development](#local-development)
   - [Development Scripts](#development-scripts)
   - [Control Plane (Java)](#control-plane-java)
   - [Inference Engine (Python)](#inference-engine-python)
   - [Frontend (React/Vite)](#frontend-reactvite)
7. [REST API Reference](#rest-api-reference)
8. [gRPC API](#grpc-api)
9. [Document Ingestion Pipeline](#document-ingestion-pipeline)
10. [Session History & Conversation Persistence](#session-history--conversation-persistence)
11. [Reflexion Self-Correction Loop](#reflexion-self-correction-loop)
12. [Data Model](#data-model)
13. [Configuration Reference](#configuration-reference)
14. [Spring Boot Configuration](#spring-boot-configuration)
15. [Observability](#observability)
16. [Security](#security)
17. [Project Structure](#project-structure)
18. [Tech Stack](#tech-stack)
19. [Known Limitations & Roadmap](#known-limitations--roadmap)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     React Frontend (Vite)                   │
│                  localhost:5173  · TypeScript                │
└──────────────────────────┬──────────────────────────────────┘
                           │  REST (HTTP/JSON)  [proxied via Vite dev server]
┌──────────────────────────▼──────────────────────────────────┐
│              Control Plane  (Spring Boot 3 · Java 21)       │
│   localhost:8080  ·  REST API  ·  PostgreSQL persistence     │
└──────────────────────────┬──────────────────────────────────┘
                           │  gRPC  (port 50051)
┌──────────────────────────▼──────────────────────────────────┐
│          Inference Engine  (asyncio gRPC server · Python 3.12)│
│   AiAgentService  ·  LangChain + Ollama  ·  Reflexion Loop  │
└─────────────┬───────────────────────────┬───────────────────┘
              │                           │
   ┌──────────▼──────────┐   ┌────────────▼──────────────┐
   │  Qdrant Vector DB   │   │   Ollama (host machine)   │
   │  Cosine · 768-dim   │   │   llama3.2 + nomic-embed  │
   └─────────────────────┘   └───────────────────────────┘

             Arize Phoenix  (OTLP traces · localhost:6006)
```

### Services at a Glance

| Service | Port(s) | Technology | Role |
|---|---|---|---|
| `java-api` | `8080` | Spring Boot 3.2 · Java 21 | REST API, auth, document metadata, gRPC client |
| `inference-engine` | `50051` | Python 3.12 · asyncio gRPC | RAG pipeline, LLM calls, reflexion self-correction |
| `postgres` | `5432` | PostgreSQL 16 + pgvector | Metadata persistence (conversations, document metadata) |
| `qdrant` | `6333` / `6334` | Qdrant | Vector storage & similarity search |
| `phoenix` | `6006` / `4317` / `4318` | Arize Phoenix | LLM observability & OTLP tracing |
| `frontend` | `5173` (dev) | React + Vite + TypeScript | Chat & document-ingestion UI |

---

## Key Features

- **Polyglot gRPC communication** — a single `.proto` contract (`shared-protos/ai_service.proto`) generates type-safe stubs for both Java (Maven plugin) and Python (`grpcio-tools`).
- **Reflexion self-correction** — after generating a draft answer the inference engine runs a second LLM pass to detect hallucinations and override the response if needed.
- **Async document ingestion** — Spring Boot's `@Async` with Java 21 virtual threads hands off the heavy gRPC indexing call without blocking a web thread.
- **Full-text chunking** — uploaded PDFs and plain-text files are split into 1 000-token chunks (200-token overlap) and embedded via `nomic-embed-text` before being stored in Qdrant.
- **Session-aware chat** — the last 5 conversation turns are loaded from PostgreSQL and forwarded to the LLM on every request, enabling multi-turn conversations.
- **OpenTelemetry tracing** — LangChain calls are auto-instrumented via `openinference` and exported to Arize Phoenix over OTLP gRPC.
- **Hardware-aware initialisation** — `init.sh` detects GPU vendor and VRAM, installs drivers, and selects the largest Ollama model + context window that fits entirely in VRAM (or RAM).

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Ubuntu Linux | 20.04 / 22.04 / 24.04 | Required for `init.sh`; Docker path works on any OS with Docker |
| Docker & Docker Compose | v2.20+ | Required for the containerised stack |
| Ollama | latest | [ollama.com](https://ollama.com) — runs on the **host machine**, not in Docker |
| Java (local dev only) | 21+ | Installed by `init.sh` if absent |
| Python (local dev only) | 3.11+ | Installed by `init.sh` if absent |
| Node.js (local dev only) | 20+ | Installed by `init.sh` if absent |

---

## Getting Started — First-Time Setup (`init.sh`)

`init.sh` is a one-shot Ubuntu bootstrap script that automates everything needed to run the project for the first time. It only needs to be run once per machine (it is idempotent — safe to re-run).

```bash
git clone <repo-url>
cd Agentic_RAG_System
./init.sh
```

### What `init.sh` Does

| Step | What Happens |
|---|---|
| **1. Preflight** | Verifies Ubuntu OS, checks for `sudo`, caches credentials |
| **2. Hardware detection** | Uses `lspci` to detect NVIDIA/AMD GPU, queries VRAM via `nvidia-smi` / `rocm-smi` / sysfs, reads total RAM with `free -m` |
| **3. GPU drivers** | **NVIDIA**: runs `ubuntu-drivers autoinstall` + installs `nvidia-cuda-toolkit`. **AMD**: downloads and runs `amdgpu-install` with ROCm. Emits a reboot warning if drivers were freshly installed |
| **4. Model selection** | Selects the highest-capability Ollama model + context window that fits in VRAM (or RAM) without spilling — see table below |
| **5. Docker** | Installs Docker CE + Compose plugin from the official Docker apt repo if absent |
| **6. Java 21** | Installs Eclipse Temurin 21 JDK via the Adoptium apt repo if absent |
| **7. Maven** | Uses the system `mvn` if ≥ 3.9; otherwise downloads Maven 3.9.x into `.maven/` (no system-level install) |
| **8. Python 3.12** | Installs Python 3.12 from the `deadsnakes` PPA if the system Python is < 3.11 |
| **9. Node.js 20** | Installs Node.js 20 LTS via the NodeSource apt repo if absent |
| **10. Ollama** | Installs Ollama via its official install script if absent; starts `ollama serve` in the background |
| **11. Model pulls** | Pulls `$OLLAMA_MODEL` and `nomic-embed-text` in the background (non-blocking) |
| **12. Python venv** | Creates `inference-engine/venv` and runs `pip install -r requirements.txt` |
| **13. Frontend deps** | Runs `npm install` inside `frontend/` |
| **14. gRPC stubs** | Generates Python stubs with `grpc_tools.protoc`; compiles Java stubs with Maven |
| **15. `.env`** | Writes `.env` at the repo root with the hardware-selected `OLLAMA_MODEL` and `OLLAMA_NUM_CTX` |

### VRAM / RAM Model Selection

The script budgets **90% of available VRAM** (GPU path) or **55% of total RAM** (CPU-only path) to leave headroom for Ollama overhead and the OS. It accounts for both the chat model weights and the `nomic-embed-text` embedding model (~274 MB) running simultaneously. The first profile below that fits wins:

| Model | Context | Approx. VRAM/RAM needed |
|---|---|---|
| `llama3.1:8b` | 16 384 | ~9 474 MB |
| `llama3.1:8b` | 8 192 | ~7 426 MB |
| `llama3.1:8b` | 4 096 | ~6 402 MB |
| `llama3.1:8b` | 2 048 | ~5 890 MB |
| `llama3.2` | 8 192 | ~3 234 MB |
| `llama3.2` | 4 096 | ~2 754 MB |
| `llama3.2` | 2 048 | ~2 514 MB |
| `llama3.2:1b` | 2 048 | ~1 338 MB |
| `llama3.2:1b` | 1 024 | ~1 256 MB |
| `llama3.2:1b` | 512 *(fallback)* | < 1 256 MB |

### `init.sh` Flags

| Flag | Effect |
|---|---|
| *(none)* | Interactive mode — prompts for `sudo` once if needed |
| `--ci` | Non-interactive; assumes passwordless `sudo` (useful in CI/CD) |
| `--dry-run` | Prints every command without executing anything |
| `--force` | Re-creates the Python venv, re-runs `npm install`, overwrites `.env` |
| `--skip-stubs` | Skips gRPC stub generation (saves time on subsequent runs) |

---

## Quick Start — Docker Compose

### 1. Bootstrap (first time only, Ubuntu)

```bash
./init.sh          # installs deps, selects model, writes .env
```

### 2. Pull required Ollama models (if not pulled by init.sh)

```bash
ollama pull llama3.2          # or whichever model was selected in .env
ollama pull nomic-embed-text  # embedding model — always required
```

### 3. Start the full stack

```bash
docker compose up --build
```

| Endpoint | URL |
|---|---|
| REST API | http://localhost:8080 |
| Arize Phoenix UI | http://localhost:6006 |
| Qdrant Dashboard | http://localhost:6333/dashboard |

### 4. Run the frontend (dev mode)

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

---

## Local Development

### Development Scripts

Three helper scripts live at the repo root:

#### `./start.sh` — One-command local dev launcher

Starts all infrastructure (Postgres, Qdrant, Phoenix) as Docker containers, then launches the Python inference engine and the React dev server in the background, and finally runs the Spring Boot control plane in the foreground (Ctrl+C cleanly shuts everything down).

```bash
./start.sh
```

**Requirements before running `start.sh`:**
- Docker running
- `inference-engine/venv` must exist (`init.sh` creates it)
- Ollama running separately: `ollama serve`

**What it starts:**

| Step | Process | Notes |
|---|---|---|
| 1 | `rag-postgres` Docker container | pgvector/pgvector:pg16, port 5432 |
| 2 | `rag-qdrant` Docker container | qdrant/qdrant:latest, ports 6333/6334 |
| 3 | `rag-phoenix` Docker container | arizephoenix/phoenix:latest, ports 6006/4317/4318 |
| 4 | Python inference engine | `inference-engine/main.py` via venv, port 50051, logs → `inference-engine.log` |
| 5 | React/Vite dev server | `frontend/`, port 5173, logs → `frontend.log` |
| 6 | Spring Boot control plane | `control-plane/`, port 8080, foreground (Ctrl+C to stop all) |

#### `./build.sh` — gRPC stub regeneration

Regenerates the Python gRPC stubs from the `.proto` file and recompiles the Java control plane. Run this after any change to `shared-protos/ai_service.proto`.

```bash
./build.sh
```

This is equivalent to running both of these manually:

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

---

### Control Plane (Java)

```bash
cd control-plane
./mvnw spring-boot:run
```

> **Note:** Protobuf stubs are generated automatically by the `protobuf-maven-plugin` during `mvn compile`. The source `.proto` is read from `../shared-protos/`.

The control plane connects to:
- `localhost:5432` (PostgreSQL) — configurable via `SPRING_DATASOURCE_URL`
- `localhost:50051` (Python inference engine) — configurable via `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS`

### Inference Engine (Python)

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

The inference engine connects to:
- `localhost:11434` (Ollama) — configurable via `OLLAMA_HOST`
- `localhost:6333` (Qdrant) — configurable via `QDRANT_HOST` / `QDRANT_PORT`
- `localhost:4317` (Arize Phoenix OTLP collector) — hard-coded in `main.py`

### Frontend (React/Vite)

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

The Vite dev server proxies all `/api/*` requests to `http://localhost:8080` (the Spring Boot control plane). This means the frontend makes calls to its own origin (`localhost:5173/api/...`) and Vite transparently forwards them — no CORS configuration is needed in development.

**Accepted upload file types:** `.pdf`, `.md`, `.txt`

> **Note:** The frontend uses the hardcoded `userId: "dev1"` for all requests during local development. This is intentional for Phase 1 and should be replaced with real authentication in a later phase.

---

## REST API Reference

All endpoints are served by the Spring Boot control plane on port `8080`.

### `POST /api/chat`

**Production chat endpoint.** Automatically loads and persists conversation history from PostgreSQL, enabling stateful multi-turn dialogue. This is the endpoint used by the React frontend.

**Request**

```json
{
  "query":  "What is the refund policy?",
  "userId": "alice"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | ✓ | The user's message |
| `userId` | string | ✓ | Opaque user identifier used to scope conversation history |

**Response `200 OK`**

```json
{
  "answer":          "Based on the uploaded policy document, refunds are...",
  "sourceDocuments": ["refund-policy.pdf", "faq.txt"],
  "confidenceScore": 0.9,
  "latencyMs":       342
}
```

**Error responses**

| Status | Condition |
|---|---|
| `422 Unprocessable Entity` | `query` or `userId` is blank |
| `503 Service Unavailable` | Python inference engine is unreachable |

---

### `POST /api/v1/query`

**Stateless debug endpoint.** Does **not** load or persist conversation history. Useful for smoke-testing the gRPC pipeline without Postgres involvement. Session history can be passed manually.

**Request**

```json
{
  "query":          "Summarise the uploaded document.",
  "userId":         "alice",
  "sessionHistory": [
    "user: What did we discuss before?",
    "assistant: We discussed the refund policy."
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | ✓ | The user's message |
| `userId` | string | ✓ | Opaque user identifier |
| `sessionHistory` | string[] | — | Ordered list of prior turns in `"role: message"` format |

**Response** — same schema as `/api/chat`.

---

### `POST /api/documents`

**Upload and index a document.** Saves document metadata to PostgreSQL immediately (status `PENDING`) and triggers the indexing pipeline asynchronously. Returns `202 Accepted` straight away — the caller should poll `GET /api/documents/{userId}` to check completion.

**Request** — `multipart/form-data`

| Field | Type | Required | Description |
|---|---|---|---|
| `file` | file | ✓ | The document to index (`.pdf`, `.txt`, `.md`) |
| `userId` | string | ✓ | Owner of the document |

**Response `202 Accepted`**

```json
{
  "id":               "3f7a1c2d-...",
  "originalFilename": "architecture.pdf",
  "uploadedBy":       "alice",
  "uploadedAt":       "2026-04-23T10:00:00Z",
  "status":           "PENDING",
  "chunkCount":       0
}
```

**Error responses**

| Status | Condition |
|---|---|
| `400 Bad Request` | No file attached or empty file |
| `500 Internal Server Error` | Could not read file bytes |

---

### `GET /api/documents/{userId}`

**List a user's documents.** Returns all documents uploaded by the given user, newest first. Use this to poll indexing status after an upload.

**Path parameter:** `userId` — the same identifier used during upload.

**Response `200 OK`**

```json
[
  {
    "id":               "3f7a1c2d-...",
    "originalFilename": "architecture.pdf",
    "uploadedBy":       "alice",
    "uploadedAt":       "2026-04-23T10:00:00Z",
    "status":           "INDEXED",
    "chunkCount":       42
  }
]
```

**`status` field values:**

| Value | Meaning |
|---|---|
| `PENDING` | Upload received; indexing not yet started |
| `INDEXING` | gRPC `IndexDocument` call in progress |
| `INDEXED` | All chunks successfully stored in Qdrant |
| `FAILED` | An error occurred during the gRPC call or chunking |

---

## gRPC API

Defined in `shared-protos/ai_service.proto`. Java and Python stubs are generated from this single source of truth.

```protobuf
service AiAgentService {
  rpc ProcessQuery   (AgentQuery)   returns (AgentResponse) {}
  rpc IndexDocument  (IndexRequest) returns (IndexResponse) {}
}
```

### Messages

| Message | Field | Type | Description |
|---|---|---|---|
| `AgentQuery` | `query` | string | User's natural-language question |
| | `user_id` | string | Opaque caller identifier |
| | `session_history` | repeated string | Ordered prior turns (`"role: message"`) |
| `AgentResponse` | `answer` | string | LLM-generated answer (may be Reflexion-corrected) |
| | `source_documents` | repeated string | Filenames of retrieved chunks |
| | `confidence_score` | float | Currently hardcoded `0.9`; dynamic scoring planned |
| | `latency_ms` | int64 | Total inference time in milliseconds |
| `IndexRequest` | `document_id` | string | UUID assigned by PostgreSQL |
| | `filename` | string | Original file name |
| | `user_id` | string | Uploader identifier |
| | `file_content` | bytes | Raw file bytes |
| `IndexResponse` | `chunk_count` | int32 | Number of chunks stored in Qdrant |
| | `status` | string | `"SUCCESS"` or `"FAILED"` |

> **Version pinning:** `grpcio` and `grpcio-tools` in `requirements.txt` are pinned to `1.62.2` to match the Java-side `grpc.version` in `pom.xml`. Both must be updated together if the gRPC version is bumped.

---

## Document Ingestion Pipeline

```
Browser upload (multipart)
        │
        ▼
DocumentController (Java)
  ├─ Saves DocumentMetadata → Postgres  [status = PENDING]
  └─ Calls DocumentService.indexDocumentAsync()  (@Async + Virtual Thread)
                │
                ▼   [non-blocking — HTTP response returned immediately as 202]
        DocumentService (Java)
          ├─ Updates status → INDEXING in Postgres
          ├─ Sends IndexRequest via gRPC (blocking stub)
          └─ Updates status → INDEXED / FAILED in Postgres
                │
                ▼
        Python Inference Engine — IndexDocument handler
          ├─ Writes bytes to a temp file
          ├─ Loads document:
          │    .pdf → PyPDFLoader
          │    .txt / .md → TextLoader
          ├─ RecursiveCharacterTextSplitter
          │    chunk_size = 1 000 tokens
          │    chunk_overlap = 200 tokens
          ├─ Attaches metadata: source, document_id, uploaded_by
          └─ OllamaEmbeddings (nomic-embed-text, 768-dim)
               └─ Upserts chunks → Qdrant collection "agentic_rag"
```

**Supported file types:** `.pdf` (via `PyPDFLoader`), `.txt` and `.md` (via `TextLoader`).

**Qdrant collection:** `agentic_rag` — cosine similarity, 768-dimensional vectors. The collection is created automatically on first start if it does not exist.

---

## Session History & Conversation Persistence

Every call to `POST /api/chat` triggers the following:

1. **Load** — the last **5 conversation turns** for the given `userId` are fetched from the `conversations` PostgreSQL table, sorted oldest-first, and formatted as `"user: <prompt>"` / `"assistant: <response>"` strings.
2. **Infer** — the history is forwarded to the Python inference engine as `session_history` in the `AgentQuery` proto. The Python layer prepends them to the message list sent to Ollama so the LLM has prior context.
3. **Persist** — after a successful gRPC response, the new turn (prompt + response + latency) is saved to the `conversations` table.

The rolling window of 5 turns means the LLM context always contains at most 10 messages (5 user + 5 assistant) of history, keeping token usage bounded.

> `POST /api/v1/query` is a stateless pass-through — it does **not** load or persist conversation history. Pass `sessionHistory` manually in the request body if needed.

---

## Reflexion Self-Correction Loop

After the draft answer is generated, the inference engine runs a second LLM call to act as a strict grader. This "Reflexion" pass evaluates whether the draft is grounded in the retrieved context.

```
User query
    │
    ▼
1. Qdrant similarity search  (top-3 chunks)
    │
    ▼
2. First LLM call  →  draft answer
    │
    ▼
3. Reflexion LLM call
   Prompt: "QUERY: ...  CONTEXT: ...  DRAFT ANSWER: ..."
   Rules:
     - If context doesn't answer the query → draft must say "I don't know"
       (if it hallucinated anyway → REWRITE)
     - If draft contradicts or misses context → REWRITE
     - If draft is correct → output exactly "APPROVE"
    │
    ├─ "APPROVE"  →  return draft answer as-is
    └─ anything else  →  return "[Reflexion Self-Corrected]\n<rewritten answer>"
```

Both LLM calls use the same Ollama model (selected at startup via `OLLAMA_MODEL`) and the same `OLLAMA_NUM_CTX` context window.

---

## Data Model

### PostgreSQL — `conversations`

Stores one row per completed chat turn for session context reconstruction.

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` (PK) | Auto-generated |
| `user_id` | `VARCHAR` | Identifies whose conversation this is |
| `user_prompt` | `TEXT` | The user's message |
| `ai_response` | `TEXT` | The final (possibly Reflexion-corrected) answer |
| `latency_ms` | `BIGINT` | Round-trip inference time reported by the Python engine |
| `created_at` | `TIMESTAMP` | Set by `@PrePersist`; not updatable |

### PostgreSQL — `document_metadata`

Tracks the lifecycle of every uploaded document.

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` (PK) | Auto-generated; also used as `document_id` in Qdrant chunk metadata |
| `original_filename` | `VARCHAR` | File name as uploaded |
| `uploaded_by` | `VARCHAR` | `userId` supplied at upload time |
| `uploaded_at` | `TIMESTAMP` | Set by `@PrePersist`; not updatable |
| `status` | `VARCHAR` (enum) | `PENDING` → `INDEXING` → `INDEXED` / `FAILED` |
| `chunk_count` | `INT` | Number of vector chunks stored in Qdrant (0 until `INDEXED`) |

> **Schema management:** `spring.jpa.hibernate.ddl-auto` is set to `create-drop` in `application.yml`. Tables are created fresh on startup and dropped on shutdown. Switch to `validate` + Flyway migrations before any deployment that needs data persistence across restarts.

---

## Configuration Reference

All variables can be set in a `.env` file at the repository root (read by both Docker Compose and `python-dotenv`). `init.sh` generates a `.env` automatically from detected hardware.

### Inference Engine (Python)

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama server URL. Inside Docker Compose this is `http://host.docker.internal:11434` |
| `OLLAMA_MODEL` | `llama3.2` | Ollama chat model tag. Set by `init.sh` based on VRAM/RAM |
| `OLLAMA_NUM_CTX` | `4096` | LLM context window in tokens. Set by `init.sh` based on VRAM/RAM budget |
| `QDRANT_HOST` | `localhost` | Qdrant hostname |
| `QDRANT_PORT` | `6333` | Qdrant REST port |

### Control Plane (Java / Spring Boot)

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/orchestrator` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `orchestrator` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | `secret` | PostgreSQL password |
| `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS` | `static://localhost:50051` | gRPC target for the inference engine. In Docker Compose: `static://inference-engine:50051` |

### Linux host networking note

On Linux, `host.docker.internal` is not resolved automatically inside containers. The `compose.yaml` adds an `extra_hosts` entry to the `inference-engine` service:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

This maps `host.docker.internal` to the host machine's gateway IP so the Python container can reach Ollama running on the host. This is handled automatically — no manual configuration is required.

---

## Spring Boot Configuration

Key settings in `control-plane/src/main/resources/application.yml`:

### Java 21 Virtual Threads

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Delegates all Tomcat request threads to the JVM's virtual thread scheduler. This is the single biggest concurrency win in Java 21+ — it allows the server to handle many concurrent AI requests (which can be slow) without exhausting a fixed-size thread pool.

### JPA / Hibernate

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
```

Tables are auto-created on startup and dropped on shutdown. **Change to `validate` and add Flyway migrations before any deployment where data must persist across restarts.**

### gRPC Client

```yaml
grpc:
  client:
    inference-engine:
      address: 'static://localhost:50051'
      negotiation-type: plaintext
```

The channel name `inference-engine` matches the `@GrpcClient("inference-engine")` annotation used in `AiClient.java` and `DocumentService.java`. Override the address in production via the `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS` environment variable.

---

## Observability

All LangChain calls are traced via **OpenInference** and exported to the **Arize Phoenix** collector running in the stack.

- Dashboard: **http://localhost:6006**
- OTLP gRPC collector: `localhost:4317`
- OTLP HTTP collector: `localhost:4318`

Traces capture:
- Full query latency (end-to-end)
- Number and content of retrieved Qdrant chunks
- LLM input messages and output
- Whether the Reflexion loop triggered a correction (look for `[Reflexion Self-Corrected]` prefix in the answer span)

The OTLP exporter endpoint is currently hard-coded to `http://localhost:4317` in `inference-engine/main.py`.

---

## Security

`SecurityConfig.java` uses a **Phase 1 open configuration** — all HTTP requests are permitted and CSRF is disabled. This is intentional for local development.

**Before exposing this service externally or moving toward production:**

1. Replace the `anyRequest().permitAll()` rule with JWT / OAuth2 resource-server configuration.
2. Re-enable CSRF protection if the API will be consumed by browser sessions with cookies.
3. Replace the hardcoded PostgreSQL password (`secret`) with a secret managed by your deployment tooling.
4. Replace the hardcoded `userId: "dev1"` in the React frontend with real identity from an auth provider.
5. Consider adding rate limiting at the API gateway layer to protect Ollama from unbounded concurrent requests.

---

## Project Structure

```
Agentic_RAG_System/
├── init.sh                           # First-time Ubuntu setup: drivers, deps, model selection, .env
├── start.sh                          # Local dev launcher (all services in one command)
├── build.sh                          # Regenerates gRPC stubs (Python + Java)
├── compose.yaml                      # Full containerised stack
├── .env                              # Generated by init.sh; read by compose.yaml and python-dotenv
├── shared-protos/
│   └── ai_service.proto              # Single source of truth for the gRPC contract
├── control-plane/                    # Java 21 · Spring Boot 3
│   ├── pom.xml
│   └── src/main/java/com/ai/orchestrator/
│       ├── AiClient.java             # Low-level gRPC blocking stub wrapper
│       ├── OrchestratorApplication.java
│       ├── config/
│       │   └── SecurityConfig.java   # Phase 1: permits all; replace before prod
│       ├── controller/
│       │   ├── ChatController.java   # POST /api/chat (stateful, persists history)
│       │   ├── DocumentController.java # POST/GET /api/documents
│       │   └── QueryController.java  # POST /api/v1/query (stateless debug)
│       ├── dto/
│       │   ├── ChatRequest.java
│       │   ├── QueryRequest.java
│       │   └── QueryResponse.java
│       ├── model/
│       │   ├── Conversation.java     # JPA entity: conversations table
│       │   ├── DocumentMetadata.java # JPA entity: document_metadata table
│       │   └── IndexingStatus.java   # Enum: PENDING, INDEXING, INDEXED, FAILED
│       ├── repository/
│       │   ├── ConversationRepository.java
│       │   └── DocumentMetadataRepository.java
│       └── service/
│           ├── AiQueryService.java   # Orchestrates query + chat; loads/saves history
│           └── DocumentService.java  # @Async indexing via gRPC
├── inference-engine/                 # Python 3.12 · asyncio gRPC server
│   ├── main.py                       # gRPC servicer: ProcessQuery + IndexDocument + Reflexion loop
│   ├── rag.py                        # Qdrant vector store + OllamaEmbeddings initialisation
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                         # React 18 · Vite 5 · TypeScript
│   └── src/
│       ├── App.tsx                   # Chat UI + document upload; proxies /api → :8080
│       └── index.css                 # Global styles
└── documentation/
    ├── idea.md                       # Original project concept notes
    └── planning.md                   # Phase-by-phase build plan
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Control Plane** | Java 21, Spring Boot 3.2, Spring Data JPA, Spring Security |
| **gRPC (Java)** | `grpc-client-spring-boot-starter`, `protobuf-maven-plugin` |
| **Inference Engine** | Python 3.12, `grpcio 1.62.2`, `langchain`, `langchain-ollama` |
| **Vector Store** | Qdrant, `langchain-qdrant`, `nomic-embed-text` (768-dim cosine) |
| **LLM** | Ollama — model auto-selected by `init.sh` based on available VRAM/RAM |
| **Persistence** | PostgreSQL 16, pgvector |
| **Observability** | Arize Phoenix, OpenTelemetry, OpenInference |
| **Frontend** | React 18, Vite 5, TypeScript, Lucide React |
| **Infrastructure** | Docker Compose v2 |
| **Bootstrapping** | `init.sh` (Ubuntu 20.04/22.04/24.04) |

---

## Known Limitations & Roadmap

| Limitation | Notes |
|---|---|
| `userId` hardcoded to `"dev1"` in the frontend | Replace with real auth (JWT/OAuth2) before multi-user use |
| `ddl-auto: create-drop` drops the database on every restart | Add Flyway migrations and switch to `validate` for persistent data |
| `confidence_score` is hardcoded to `0.9` | Dynamic calculation from Qdrant cosine distance scores planned |
| OTLP endpoint hardcoded to `localhost:4317` | Make configurable via env var |
| No streaming responses | LLM outputs are buffered; streaming (`ainvoke` → async generator) is a natural extension |
| Single Qdrant collection for all users | Per-user or per-document-set namespacing planned |
| GPU driver install requires reboot | `init.sh` warns and sets a flag; subsequent runs detect the driver automatically |
