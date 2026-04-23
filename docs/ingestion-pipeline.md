# Ingestion Pipeline, Session History & Reflexion

[← Back to README](../README.md)

This page covers the three core intelligence flows in the system:

1. **Document Ingestion Pipeline** — how an uploaded file travels from the browser to Qdrant.
2. **Session History & Conversation Persistence** — how multi-turn context is loaded, forwarded, and saved.
3. **Reflexion Self-Correction Loop** — how the inference engine audits its own draft answers for hallucinations.

These three flows are closely related: ingestion populates the vector store that RAG retrieval draws on, session history feeds the LLM conversation window, and Reflexion provides a quality gate on every response.

---

## Document Ingestion Pipeline

When a user uploads a file through the React frontend, the system immediately acknowledges the request and processes the document asynchronously in the background. This keeps the HTTP response fast regardless of how large or complex the document is.

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

### Key design decisions

**Asynchronous Java layer.** Spring Boot's `@Async` annotation, combined with Java 21 virtual threads, offloads the gRPC indexing call to a background task. The HTTP thread is freed immediately, so the client receives `202 Accepted` in milliseconds even for large PDFs. The caller can then poll `GET /api/documents/{userId}` to track progress through the `PENDING → INDEXING → INDEXED / FAILED` lifecycle (see [API Reference](api-reference.md)).

**RecursiveCharacterTextSplitter.** Chunking with a 200-token overlap ensures that sentences spanning a chunk boundary are preserved in at least one chunk, reducing retrieval misses on multi-sentence facts.

**nomic-embed-text embeddings.** The `nomic-embed-text` model produces 768-dimensional vectors and runs entirely locally via Ollama — no external API calls or data egress. The Qdrant collection `agentic_rag` uses cosine similarity and is created automatically on first start if it does not already exist.

**Metadata on every chunk.** Each Qdrant point carries `source` (original filename), `document_id` (the PostgreSQL UUID), and `uploaded_by` (the uploader's userId). This allows the system to surface which specific document a retrieved chunk came from in the API response's `sourceDocuments` field.

**Supported file types:** `.pdf` (via `PyPDFLoader`), `.txt` and `.md` (via `TextLoader`).

---

## Session History & Conversation Persistence

Every call to `POST /api/chat` goes through a three-step history cycle that keeps the LLM aware of recent conversation without unbounded token growth.

### The cycle

1. **Load** — the last **5 conversation turns** for the given `userId` are fetched from the `conversations` PostgreSQL table, sorted oldest-first, and formatted as `"user: <prompt>"` / `"assistant: <response>"` strings.

2. **Infer** — the history array is forwarded to the Python inference engine as the `session_history` field in the `AgentQuery` proto. The Python layer prepends these messages to the message list sent to Ollama, giving the model the context of prior turns before it sees the new question.

3. **Persist** — after a successful gRPC response, the new turn (prompt + response + latency) is saved as a new row in the `conversations` table, becoming part of the history window for the next request.

### Rolling window

The rolling window of 5 turns means the LLM context always contains **at most 10 messages** (5 user + 5 assistant) of conversation history in addition to the new query and the retrieved RAG chunks. This keeps token usage bounded and predictable regardless of how long a conversation runs.

> **Stateless endpoint:** `POST /api/v1/query` is a pass-through that does **not** load or persist conversation history. It is useful for debugging, testing, or scripted queries. Pass `sessionHistory` manually in the request body if prior context is needed. See [API Reference](api-reference.md).

---

## Reflexion Self-Correction Loop

After generating a draft answer, the inference engine immediately runs a second LLM call that acts as a strict grader. This "Reflexion" pass checks whether the draft answer is grounded in the retrieved context — catching hallucinations before they reach the user.

### How it works

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

### Design notes

Both LLM calls use the **same Ollama model** (selected at startup via `OLLAMA_MODEL`) and the **same context window** (`OLLAMA_NUM_CTX`). This means the Reflexion pass has the same capabilities as the initial generation pass — it is genuinely capable of catching errors the first pass could make.

When the Reflexion pass rewrites an answer, the returned string is prefixed with `[Reflexion Self-Corrected]`. This prefix is visible in the API response and in the Arize Phoenix trace for the request, making it easy to identify how frequently self-correction is triggered.

The Reflexion pass does add a second full LLM call to every query. On a capable GPU this overhead is typically small relative to the first call; on a CPU-only machine with a context window in the thousands it may double total latency. The trade-off is intentional — correctness is prioritised over raw throughput in this phase.

---

## Related Pages

- [API Reference](api-reference.md) — REST endpoints for uploads and chat
- [Data Model](data-model.md) — PostgreSQL schema for conversations and document metadata
- [Observability & Security](observability-security.md) — tracing Reflexion corrections in Arize Phoenix
