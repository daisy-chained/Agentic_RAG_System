This is the "Senior Engineer" move. By building a polyglot system, you're proving you aren't just a coder—you're an **architect**. You are demonstrating that you know how to use the right tool for the job: **Java** for system stability and **Python** for AI velocity.

Here is the blueprint for this project: **"The Polyglot AI Agent Orchestrator."**

---

## 🏗️ The Architecture
We split the responsibilities so each language plays to its strengths.

### 1. The Java Service (The "Control Plane")
* **Role:** The Gateway & Orchestrator.
* **Tech:** Spring Boot 3.2, Spring Data JPA, Spring Security, PostgreSQL.
* **Responsibility:**
    * **State Management:** Saving conversation history and document metadata in PostgreSQL.
    * **Orchestration:** Receiving a REST request, validating it, and forwarding it to the Python inference engine via **gRPC**.
    * **Async Indexing:** Handing off document ingestion to a virtual-thread-backed `@Async` pipeline so the HTTP thread is never blocked.

### 2. The Python Service (The "Inference Engine")
* **Role:** The AI Specialist.
* **Tech:** Python 3.12, asyncio gRPC server, LangChain, Ollama (llama3.2).
* **Responsibility:**
    * **RAG Pipeline:** Embedding documents with `nomic-embed-text` (768-dim) and storing them in Qdrant; retrieving the top-3 cosine-similar chunks at query time.
    * **Reflexion Self-Correction:** Running a second LLM pass to detect and rewrite hallucinated answers before returning the response.
    * **Observability:** Exporting LangChain traces to Arize Phoenix via OpenTelemetry.

---

## 🛠️ The Actual Tech Stack

| Component | Java Side (Stability) | Python Side (Intelligence) |
| :--- | :--- | :--- |
| **Framework** | Spring Boot 3.2 | asyncio gRPC (`grpc.aio`) |
| **Vector Storage** | — | Qdrant (cosine, 768-dim) |
| **LLM** | — | Ollama (`llama3.2` via `langchain-ollama`) |
| **Embeddings** | — | `nomic-embed-text` via Ollama |
| **Data Storage** | PostgreSQL 16 + pgvector | Qdrant |
| **Communication** | **gRPC** (`grpc-client-spring-boot-starter`) | **gRPC** / Protobuf |
| **Testing** | JUnit 5 / Testcontainers | pytest + pytest-asyncio |
| **Observability** | — | Arize Phoenix, OpenTelemetry, OpenInference |

---

## 🚀 The Build Roadmap (As Built)

### Phase 1: The Bridge ✅
* Single `.proto` contract in `shared-protos/ai_service.proto` generates type-safe stubs for both Java (Maven plugin) and Python (`grpcio-tools`).
* `build.sh` automates stub regeneration.

### Phase 2: The Data Layer ✅
* **Python:** `rag.py` initialises the Qdrant collection and `langchain-qdrant` vector store at startup.
* **Java:** `DocumentMetadata` and `Conversation` JPA entities with status tracking and conversation history.

### Phase 3: The "Agentic" Twist ✅
* `ProcessQuery` retrieves top-3 Qdrant chunks, injects them as context, generates a draft answer, then runs a Reflexion loop — a second LLM call that either approves the answer or rewrites it to eliminate hallucinations.

---

## 📝 How to write this on your CV

> **Polyglot Agentic RAG Platform (Java 21 / Python 3.12)**
> * Architected a distributed system with a **Spring Boot 3** microservice for state management and a **Python asyncio gRPC** service for AI inference, communicating over a shared **Protobuf** contract.
> * Implemented a **Retrieval-Augmented Generation (RAG)** pipeline with **LangChain + Qdrant**, reducing LLM hallucinations through a **Reflexion self-correction** loop that performs a second grading pass before returning any response.
> * Optimized inter-service communication using **gRPC**, achieving sub-second latency for end-to-end RAG queries.
> * Instrumented all LLM calls with **OpenTelemetry / OpenInference**, exporting traces to **Arize Phoenix** for latency and token-usage analysis.
> * Delivered a full **React + Vite + TypeScript** frontend with real-time chat, document upload, and source citation display.
