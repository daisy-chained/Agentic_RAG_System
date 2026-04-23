# Agentic RAG System

A context-aware, polyglot **Retrieval-Augmented Generation** platform built for developer advocacy workflows. It combines a Java orchestration layer, a Python inference engine, and a React frontend — all wired together via gRPC and deployed as a single Docker Compose stack. This is a work in progress, with no active support.

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
- **Cross-encoder reranking** — Qdrant retrieves the top-10 candidate chunks; a local `cross-encoder/ms-marco-MiniLM-L-6-v2` model rescores each `(query, chunk)` pair and retains only the top-3, improving context precision without an extra LLM call.
- **Async document ingestion** — Spring Boot's `@Async` with Java 21 virtual threads hands off the heavy gRPC indexing call without blocking a web thread.
- **Multi-format document support** — uploaded documents (PDF, Word, Excel, PowerPoint, CSV, HTML, EPUB, Markdown, plain-text) are split into 1 000-token chunks (200-token overlap) and embedded via `nomic-embed-text` before being stored in Qdrant. All parsers are pure-Python — no system-level packages (e.g. `libmagic`, LibreOffice) are required.
- **Session-aware chat** — the last 5 conversation turns are loaded from PostgreSQL and forwarded to the LLM on every request, enabling multi-turn conversations.
- **OpenTelemetry tracing** — LangChain calls are auto-instrumented via `openinference` and exported to Arize Phoenix over OTLP gRPC.
- **Hardware-aware initialisation** — `init.sh` detects GPU vendor and VRAM, installs drivers, and selects the largest Ollama model + context window that fits entirely in VRAM (or RAM).

---

## Documentation

| Page | Description |
|---|---|
| [Getting Started](docs/getting-started.md) | Prerequisites, first-time `init.sh` bootstrap, Docker Compose quick start |
| [Local Development](docs/local-development.md) | Per-service dev setup, `start.sh` / `build.sh` scripts |
| [API Reference](docs/api-reference.md) | All REST endpoints and the gRPC service contract |
| [Ingestion Pipeline, Session History & Reflexion](docs/ingestion-pipeline.md) | How documents are indexed, how conversation history flows, and how the self-correction loop works |
| [Data Model](docs/data-model.md) | PostgreSQL schema for conversations and document metadata |
| [Configuration](docs/configuration.md) | All environment variables and key Spring Boot YAML settings |
| [Observability & Security](docs/observability-security.md) | Arize Phoenix tracing and the pre-production security checklist |
| [Project Structure, Tech Stack & Roadmap](docs/project-structure.md) | File tree, technology choices, and known limitations |
