# Data Model

[← Back to README](../README.md)

The system uses **PostgreSQL 16** (with the pgvector extension available) for structured persistence. There are two tables: one for conversation history and one for document lifecycle tracking. Vector data lives separately in Qdrant — PostgreSQL only stores the relational metadata.

---

## `conversations`

Stores one row per completed chat turn. Rows from this table are loaded at the start of every `POST /api/chat` request to reconstruct recent session context for the LLM.

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` (PK) | Auto-generated primary key |
| `user_id` | `VARCHAR` | Identifies whose conversation this row belongs to; used to scope history loads |
| `user_prompt` | `TEXT` | The user's raw message as submitted |
| `ai_response` | `TEXT` | The final answer returned by the inference engine (may include the `[Reflexion Self-Corrected]` prefix) |
| `latency_ms` | `BIGINT` | Round-trip inference time in milliseconds, as reported by the Python engine |
| `created_at` | `TIMESTAMP` | Set automatically by `@PrePersist`; never updated |

The control plane queries this table via `ConversationRepository`, loading the **last 5 turns** per `userId`, sorted oldest-first, and formatting them as `"user: <prompt>"` / `"assistant: <response>"` strings before forwarding them in the `AgentQuery` gRPC message. See [Ingestion Pipeline, Session History & Reflexion](ingestion-pipeline.md) for the full cycle.

---

## `document_metadata`

Tracks the lifecycle of every uploaded document from receipt through to successful indexing (or failure). The `status` column is updated in-place as the async indexing pipeline progresses.

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` (PK) | Auto-generated; also stored as `document_id` in every Qdrant chunk's metadata, linking vector points back to this row |
| `original_filename` | `VARCHAR` | The file name as uploaded by the user |
| `uploaded_by` | `VARCHAR` | The `userId` supplied at upload time |
| `uploaded_at` | `TIMESTAMP` | Set automatically by `@PrePersist`; never updated |
| `status` | `VARCHAR` (enum) | Document lifecycle state — see values below |
| `chunk_count` | `INT` | Number of vector chunks stored in Qdrant; `0` until the document reaches `INDEXED` |

**Status values**

| Value | Meaning |
|---|---|
| `PENDING` | Upload received; indexing has not started yet |
| `INDEXING` | The gRPC `IndexDocument` call to the Python engine is in progress |
| `INDEXED` | All chunks have been successfully stored in Qdrant; document is ready for retrieval |
| `FAILED` | An error occurred during the gRPC call or the chunking/embedding step |

Poll `GET /api/documents/{userId}` to observe status transitions after an upload. See [API Reference](api-reference.md) for the full response schema.

---

## Schema Management

`spring.jpa.hibernate.ddl-auto` is set to `create-drop` in `application.yml`. This means:

- Tables are **created fresh** every time the Spring Boot application starts.
- Tables are **dropped** when the application shuts down gracefully.

This is intentional for the current development phase — it keeps the schema in sync with JPA entity definitions without needing a migration tool. However, it also means **all conversation and document data is lost on every restart**.

> **Before any deployment where data must persist across restarts:** switch `ddl-auto` to `validate` and introduce [Flyway](https://flywaydb.org/) migrations to manage schema changes incrementally. See also the [Known Limitations & Roadmap](project-structure.md#known-limitations--roadmap) section.

---

## Qdrant Vector Store

Vector data is stored separately in Qdrant, not in PostgreSQL. Each chunk stored in Qdrant carries a payload with:

| Payload field | Value |
|---|---|
| `source` | Original filename (e.g. `architecture.pdf`) |
| `document_id` | PostgreSQL UUID from `document_metadata.id` |
| `uploaded_by` | Uploader's `userId` |

The Qdrant collection name is `agentic_rag`. It uses cosine similarity with 768-dimensional vectors (produced by `nomic-embed-text`). The collection is created automatically on first start if it does not exist.

> **Current limitation:** All users share a single Qdrant collection. Per-user or per-document-set namespacing is planned as a future improvement.

---

## Related Pages

- [Ingestion Pipeline, Session History & Reflexion](ingestion-pipeline.md) — how rows are written and read
- [API Reference](api-reference.md) — REST endpoints that expose document status
- [Configuration](configuration.md) — database connection settings
