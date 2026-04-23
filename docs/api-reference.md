# API Reference

[← Back to README](../README.md)

The system exposes two API surfaces:

- **REST API** — served by the Spring Boot control plane on port `8080`. This is the primary interface for the React frontend and for any external HTTP client.
- **gRPC API** — the internal service boundary between the Java control plane and the Python inference engine on port `50051`. Direct gRPC calls from outside the stack are possible but are mainly useful for low-level debugging.

---

## REST API

### `POST /api/chat`

**Production chat endpoint.** Automatically loads the caller's recent conversation history from PostgreSQL and persists the new turn after a successful response. This is the endpoint used by the React frontend for all chat interactions.

**Request body — `application/json`**

```json
{
  "query":  "What is the refund policy?",
  "userId": "alice"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | ✓ | The user's natural-language message |
| `userId` | string | ✓ | Opaque user identifier used to scope conversation history |

**Response — `200 OK`**

```json
{
  "answer":          "Based on the uploaded policy document, refunds are...",
  "sourceDocuments": ["refund-policy.pdf", "faq.txt"],
  "confidenceScore": 0.9,
  "latencyMs":       342
}
```

| Field | Type | Description |
|---|---|---|
| `answer` | string | LLM-generated answer; may be prefixed with `[Reflexion Self-Corrected]` if the self-correction loop rewrote it |
| `sourceDocuments` | string[] | Filenames of the Qdrant chunks used to ground the answer |
| `confidenceScore` | float | Currently hardcoded to `0.9`; dynamic scoring from Qdrant cosine distances is planned |
| `latencyMs` | integer | Total round-trip inference time as reported by the Python engine |

**Error responses**

| Status | Condition |
|---|---|
| `422 Unprocessable Entity` | `query` or `userId` is blank or missing |
| `503 Service Unavailable` | The Python inference engine is unreachable via gRPC |

---

### `POST /api/v1/query`

**Stateless debug endpoint.** Does **not** load or persist conversation history. Useful for smoke-testing the gRPC pipeline in isolation, scripted integration tests, or one-off queries where you want to supply your own session context.

**Request body — `application/json`**

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
| `query` | string | ✓ | The user's natural-language message |
| `userId` | string | ✓ | Opaque user identifier |
| `sessionHistory` | string[] | — | Ordered list of prior turns in `"role: message"` format. Passed directly to the LLM without database involvement |

**Response** — same schema as `POST /api/chat`.

---

### `POST /api/documents`

**Upload and index a document.** Saves document metadata to PostgreSQL immediately with status `PENDING`, then triggers the indexing pipeline asynchronously via `@Async` and a gRPC call to the Python engine. Returns `202 Accepted` straight away — the caller should poll `GET /api/documents/{userId}` to check when indexing completes.

**Request — `multipart/form-data`**

| Field | Type | Required | Description |
|---|---|---|---|
| `file` | file | ✓ | The document to index. Supported types: `.pdf`, `.txt`, `.md` |
| `userId` | string | ✓ | The owner of the document; used to scope retrieval later |

**Response — `202 Accepted`**

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
| `500 Internal Server Error` | Could not read file bytes from the multipart payload |

---

### `GET /api/documents/{userId}`

**List a user's documents.** Returns all documents uploaded by the given user, sorted newest-first. Use this endpoint to poll indexing status after a `POST /api/documents` upload.

**Path parameter:** `userId` — the same identifier supplied at upload time.

**Response — `200 OK`**

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

**`status` field values**

| Value | Meaning |
|---|---|
| `PENDING` | Upload received; indexing not yet started |
| `INDEXING` | gRPC `IndexDocument` call in progress |
| `INDEXED` | All chunks successfully stored in Qdrant; document is ready for retrieval |
| `FAILED` | An error occurred during the gRPC call or chunking step |

---

## gRPC API

The gRPC service boundary is defined in `shared-protos/ai_service.proto`. This file is the **single source of truth** — Java and Python stubs are both generated from it, so the two services are always guaranteed to share the same contract.

```protobuf
service AiAgentService {
  rpc ProcessQuery   (AgentQuery)   returns (AgentResponse) {}
  rpc IndexDocument  (IndexRequest) returns (IndexResponse) {}
}
```

The `ProcessQuery` RPC is called by `AiClient.java` and `AiQueryService.java` for every chat request. The `IndexDocument` RPC is called by `DocumentService.java` (via the `@Async` virtual-thread pool) for every document upload.

### Message Reference

| Message | Field | Type | Description |
|---|---|---|---|
| `AgentQuery` | `query` | string | User's natural-language question |
| | `user_id` | string | Opaque caller identifier |
| | `session_history` | repeated string | Ordered prior turns (`"role: message"`) |
| `AgentResponse` | `answer` | string | LLM-generated answer (may be Reflexion-corrected) |
| | `source_documents` | repeated string | Filenames of retrieved Qdrant chunks |
| | `confidence_score` | float | Currently hardcoded `0.9`; dynamic scoring planned |
| | `latency_ms` | int64 | Total inference time in milliseconds |
| `IndexRequest` | `document_id` | string | UUID assigned by PostgreSQL |
| | `filename` | string | Original file name as uploaded |
| | `user_id` | string | Uploader identifier |
| | `file_content` | bytes | Raw file bytes forwarded from the HTTP multipart payload |
| `IndexResponse` | `chunk_count` | int32 | Number of chunks stored in Qdrant |
| | `status` | string | `"SUCCESS"` or `"FAILED"` |

> **Version pinning:** `grpcio` and `grpcio-tools` in `requirements.txt` are pinned to `1.62.2` to match the Java-side `grpc.version` in `pom.xml`. Both must be updated together if the gRPC version is bumped — a version mismatch will cause serialisation failures at the boundary.

---

## Related Pages

- [Ingestion Pipeline, Session History & Reflexion](ingestion-pipeline.md) — what happens inside the Python engine after a `ProcessQuery` or `IndexDocument` call
- [Data Model](data-model.md) — PostgreSQL schema for conversations and document metadata
- [Configuration](configuration.md) — how to override REST and gRPC addresses
