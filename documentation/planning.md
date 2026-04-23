To build a **Polyglot Agentic RAG system** in 2026, you need to treat it like a professional microservices project. Instead of one big mess of code, you are building a **Control Plane (Java)** and an **AI Engine (Python)**.

Here is your step-by-step task list to go from zero to a portfolio-ready system. Items marked ✅ are complete in the current codebase.

---

## Phase 1: The Blueprint & Infrastructure ✅
*Goal: Set up the communication "bridge" between the two languages.*

1.  ✅ **Define the Contract (Protobuf):** Created `shared-protos/ai_service.proto` defining `AgentQuery` / `AgentResponse` (query, user_id, session_history / answer, source_documents, confidence_score, latency_ms) and `IndexRequest` / `IndexResponse`.
2.  ✅ **Orchestrate with Docker Compose:** `compose.yaml` defines six services: `java-api`, `inference-engine`, `postgres`, `qdrant`, `phoenix`, and `frontend`.
3.  ✅ **The gRPC Bridge:** Python asyncio gRPC server (`main.py`) and Java Spring Boot gRPC client (`AiClient.java`). Java sends queries to Python and receives structured AI responses over gRPC on port 50051.

---

## Phase 2: The Java "Control Plane" ✅
*Goal: Manage users, data, and stability.*

4.  ✅ **Persistent Storage:** Spring Data JPA with `Conversation` entity (stores chat history per user) and `DocumentMetadata` entity (tracks indexed files with `IndexingStatus`: PENDING → INDEXING → INDEXED / FAILED).
5.  ✅ **Virtual Thread Optimization:** Java 21 Virtual Threads enabled in Spring Boot. `@Async` document indexing runs on lightweight virtual threads without blocking the web thread pool.
6.  ✅ **The Gateway API:** REST endpoints at `POST /api/chat` (production path — loads session history, calls gRPC, persists turn) and `POST /api/v1/query` (debug pass-through). Document management at `POST /api/documents` and `GET /api/documents/{userId}`.

---

## Phase 3: The Python "AI Engine" ✅
*Goal: Implement the intelligence.*

7.  ✅ **The Ollama LLM Integration:** Use **LangChain + langchain-ollama**. System prompt positions the model as a technical expert. The model runs locally via Ollama (default: `llama3.2`).
8.  ✅ **The Indexing Pipeline:** `IndexDocument` gRPC handler loads PDFs (`PyPDFLoader`) or plain text (`TextLoader`), chunks with `RecursiveCharacterTextSplitter` (1 000 tokens, 200 overlap), embeds via `nomic-embed-text` (768-dim), and upserts into Qdrant.
9.  ✅ **The Retrieval Logic (RAG):** `ProcessQuery` performs a cosine-similarity search in Qdrant for the top 3 chunks and injects them as grounded context into the Ollama LLM prompt.

---

## Phase 4: Senior-Level Polish (The "Wow" Factor) ✅
*Goal: Move from "Tutorial" to "Engineer."*

10. ✅ **Agentic Self-Correction:** Reflexion loop in `ProcessQuery` — after the draft answer is generated, a second LLM pass evaluates it against the retrieved context. Hallucinated answers are overwritten with a corrected response; correct answers pass through unchanged.
11. ✅ **Observability (OpenTelemetry):** LangChain calls auto-instrumented via `openinference-instrumentation-langchain` and exported to Arize Phoenix over OTLP gRPC (`PHOENIX_OTLP_ENDPOINT`). Dashboard at http://localhost:6006.
12. ✅ **The "Rosetta" Frontend:** React + Vite + TypeScript UI (`App.tsx`) with chat interface, document upload (PDF, Markdown, TXT), typing indicator, and source citation display per message.

---

### Pro-Tip for your CV
As you complete these, don't just check them off. Document your **Architectural Decisions**.

> **Example:** *"I chose gRPC over REST for internal service communication to reduce serialization overhead and enforce strict typing across the Java-Python boundary."*
