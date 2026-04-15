# Agentic RAG System

A context-aware, polyglot **Retrieval-Augmented Generation** platform built for developer advocacy workflows. It combines a Java orchestration layer, a Python inference engine, and a React frontend — all wired together via gRPC and deployed as a single Docker Compose stack. This is a work in progress, with no active support.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     React Frontend (Vite)                   │
│                  localhost:5173  · TypeScript                │
└──────────────────────────┬──────────────────────────────────┘
                           │  REST (HTTP/JSON)
┌──────────────────────────▼──────────────────────────────────┐
│              Control Plane  (Spring Boot 3 · Java 21)       │
│   localhost:8080  ·  REST API  ·  PostgreSQL persistence     │
└──────────────────────────┬──────────────────────────────────┘
                           │  gRPC  (port 50051)
┌──────────────────────────▼──────────────────────────────────┐
│          Inference Engine  (FastAPI / asyncio · Python 3.12) │
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
| `postgres` | `5432` | PostgreSQL 16 + pgvector | Metadata persistence |
| `qdrant` | `6333` / `6334` | Qdrant | Vector storage & similarity search |
| `phoenix` | `6006` / `4317` / `4318` | Arize Phoenix | LLM observability & OTLP tracing |
| `frontend` | `5173` (dev) | React + Vite + TypeScript | Chat & document-ingestion UI |

---

## Key Features

- **Polyglot gRPC communication** — a single `.proto` contract (`shared-protos/ai_service.proto`) generates type-safe stubs for both Java (Maven plugin) and Python (`grpcio-tools`).
- **Reflexion self-correction** — after generating a draft answer the inference engine runs a second LLM pass to detect hallucinations and override the response if needed.
- **Async document ingestion** — Spring Boot's `@Async` with Java 21 virtual threads hands off the heavy gRPC indexing call without blocking a web thread.
- **Full-text chunking** — uploaded PDFs and plain-text files are split into 1 000-token chunks (200-token overlap) and embedded via `nomic-embed-text` before being stored in Qdrant.
- **Session-aware chat** — `session_history` is forwarded to the LLM on every request, enabling multi-turn conversations.
- **OpenTelemetry tracing** — LangChain calls are auto-instrumented via `openinference` and exported to Arize Phoenix over OTLP gRPC.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Docker & Docker Compose | v2.20+ |
| Ollama | [ollama.com](https://ollama.com) — running on the host machine |
| Java (local dev only) | 21+ |
| Python (local dev only) | 3.11+ |
| Node.js (local dev only) | 20+ |

### Pull required Ollama models

```bash
ollama pull llama3.2          # LLM (override with OLLAMA_MODEL env var)
ollama pull nomic-embed-text  # Embedding model used by the inference engine
```

---

## Quick Start

### 1. Clone & configure

```bash
git clone <repo-url>
cd Agentic_RAG_System

# Optional: override the Ollama model
echo "OLLAMA_MODEL=llama3.2" > .env
```

### 2. Start the stack

```bash
docker compose up --build
```

| Endpoint | URL |
|---|---|
| REST API (Swagger) | http://localhost:8080 |
| Arize Phoenix UI | http://localhost:6006 |
| Qdrant Dashboard | http://localhost:6333/dashboard |

### 3. Run the frontend (dev mode)

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

---

## Local Development

### Control Plane (Java)

```bash
cd control-plane
./mvnw spring-boot:run
```

> **Note:** Protobuf stubs are generated automatically by the `protobuf-maven-plugin` during `mvn compile`. The source `.proto` is read from `../shared-protos/`.

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

---

## Project Structure

```
Agentic_RAG_System/
├── shared-protos/
│   └── ai_service.proto          # Single source of truth for gRPC contract
├── control-plane/                # Java 21 · Spring Boot 3
│   ├── pom.xml
│   └── src/main/java/com/ai/orchestrator/
│       ├── controller/           # REST endpoints (chat, documents, query)
│       ├── service/              # DocumentService (async ingestion), AiQueryService
│       ├── model/                # JPA entities (DocumentMetadata, Conversation)
│       └── repository/           # Spring Data JPA repositories
├── inference-engine/             # Python 3.12 · asyncio gRPC server
│   ├── main.py                   # gRPC servicer + Reflexion loop
│   ├── rag.py                    # Qdrant vector store initialisation
│   └── requirements.txt
├── frontend/                     # React · Vite · TypeScript
│   └── src/
│       ├── App.tsx               # Chat & document upload UI
│       └── index.css             # Global styles
├── compose.yaml                  # Full stack orchestration
└── .gitignore
```

---

## gRPC API

Defined in `shared-protos/ai_service.proto`:

```protobuf
service AiAgentService {
  rpc ProcessQuery   (AgentQuery)   returns (AgentResponse) {}
  rpc IndexDocument  (IndexRequest) returns (IndexResponse) {}
}
```

| RPC | Request | Response |
|---|---|---|
| `ProcessQuery` | `query`, `user_id`, `session_history[]` | `answer`, `source_documents[]`, `confidence_score`, `latency_ms` |
| `IndexDocument` | `document_id`, `filename`, `user_id`, `file_content` | `chunk_count`, `status` |

---

## Configuration Reference

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `llama3.2` | Chat model to use |
| `QDRANT_HOST` | `localhost` | Qdrant host |
| `QDRANT_PORT` | `6333` | Qdrant REST port |
| `SPRING_DATASOURCE_URL` | *(compose default)* | PostgreSQL JDBC URL |
| `GRPC_CLIENT_INFERENCE_ENGINE_ADDRESS` | `static://inference-engine:50051` | gRPC target address (Spring) |

---

## Observability

All LangChain calls are traced via **OpenInference** and exported to the **Arize Phoenix** collector running in the stack.

- Dashboard: **http://localhost:6006**
- OTLP gRPC collector: `localhost:4317`
- OTLP HTTP collector: `localhost:4318`

Traces capture query latency, retrieved chunks, LLM inputs/outputs, and whether the Reflexion loop triggered a correction.

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Control Plane** | Java 21, Spring Boot 3.2, Spring Data JPA, Spring Security |
| **gRPC (Java)** | `grpc-client-spring-boot-starter`, `protobuf-maven-plugin` |
| **Inference Engine** | Python 3.12, `grpcio`, `langchain`, `langchain-ollama` |
| **Vector Store** | Qdrant, `langchain-qdrant`, `nomic-embed-text` (768-dim) |
| **LLM** | Ollama (`llama3.2` by default) |
| **Persistence** | PostgreSQL 16, pgvector |
| **Observability** | Arize Phoenix, OpenTelemetry, OpenInference |
| **Frontend** | React 18, Vite 5, TypeScript |
| **Infrastructure** | Docker Compose v2 |
