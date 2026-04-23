# Getting Started

[← Back to README](../README.md)

This guide covers everything you need to get the Agentic RAG System running for the first time — from checking prerequisites through to a fully operational Docker Compose stack. If you have already bootstrapped the machine once and just want to restart the stack, jump straight to [Quick Start — Docker Compose](#quick-start--docker-compose).

---

## Prerequisites

Before running anything, make sure the following are available on your machine. `init.sh` will install most of these automatically on Ubuntu; the table below notes what is handled and what must be in place first.

| Requirement | Version | Notes |
|---|---|---|
| Ubuntu Linux | 20.04 / 22.04 / 24.04 | Required for `init.sh`; the Docker Compose path works on any OS that has Docker |
| Docker & Docker Compose | v2.20+ | Required for the containerised stack |
| Ollama | latest | [ollama.com](https://ollama.com) — runs on the **host machine**, not inside Docker |
| Java (local dev only) | 21+ | Installed automatically by `init.sh` if absent |
| Python (local dev only) | 3.11+ | Installed automatically by `init.sh` if absent |
| Node.js (local dev only) | 20+ | Installed automatically by `init.sh` if absent |

> **Non-Ubuntu users:** `init.sh` is Ubuntu-only. If you are on macOS or Windows, install the above dependencies manually, then skip to the [Quick Start](#quick-start--docker-compose) section and run `docker compose up --build`.

---

## First-Time Setup — `init.sh`

`init.sh` is a one-shot Ubuntu bootstrap script that automates everything needed to run the project from a clean machine. It only needs to be run **once per machine** — it is fully idempotent, so re-running it is always safe.

```bash
git clone <repo-url>
cd Agentic_RAG_System
./init.sh
```

The script walks through 15 sequential steps, each guarded by a check so it skips work that is already done:

| Step | What Happens |
|---|---|
| **1. Preflight** | Verifies Ubuntu OS, checks for `sudo`, caches credentials |
| **2. Hardware detection** | Uses `lspci` to detect NVIDIA/AMD GPU, queries VRAM via `nvidia-smi` / `rocm-smi` / sysfs, reads total RAM with `free -m` |
| **3. GPU drivers** | **NVIDIA**: runs `ubuntu-drivers autoinstall` + installs `nvidia-cuda-toolkit`. **AMD**: downloads and runs `amdgpu-install` with ROCm. Emits a reboot warning if drivers were freshly installed |
| **4. Model selection** | Selects the highest-capability Ollama model + context window that fits in VRAM (or RAM) without spilling — see [VRAM / RAM Model Selection](#vram--ram-model-selection) below |
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

The script budgets **90 % of available VRAM** (GPU path) or **55 % of total RAM** (CPU-only path) to leave headroom for Ollama overhead and the OS. It accounts for both the chat model weights and the `nomic-embed-text` embedding model (~274 MB) running simultaneously. The **first profile below that fits** wins:

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

The selected model and context window are written to `.env` as `OLLAMA_MODEL` and `OLLAMA_NUM_CTX`. Docker Compose reads this file automatically, so both the Java and Python containers pick up the same values without further configuration.

### `init.sh` Flags

| Flag | Effect |
|---|---|
| *(none)* | Interactive mode — prompts for `sudo` once if needed |
| `--ci` | Non-interactive; assumes passwordless `sudo` (useful in CI/CD pipelines) |
| `--dry-run` | Prints every command without executing anything — useful for auditing what the script would do |
| `--force` | Re-creates the Python venv, re-runs `npm install`, and overwrites `.env` |
| `--skip-stubs` | Skips gRPC stub generation (saves time on subsequent runs when the `.proto` has not changed) |

---

## Quick Start — Docker Compose

Once `init.sh` has been run at least once, you can bring the full stack up with three commands.

### 1. Bootstrap (first time only, Ubuntu)

```bash
./init.sh          # installs deps, selects model, writes .env
```

### 2. Pull required Ollama models (if not already pulled by `init.sh`)

Ollama runs on the **host machine**, not inside Docker. Make sure the required models are present before starting the stack:

```bash
ollama pull llama3.2          # or whichever model was written to .env
ollama pull nomic-embed-text  # embedding model — always required
```

### 3. Start the full stack

```bash
docker compose up --build
```

Once all containers are healthy, the following endpoints are available:

| Endpoint | URL |
|---|---|
| REST API (Spring Boot) | http://localhost:8080 |
| Arize Phoenix UI | http://localhost:6006 |
| Qdrant Dashboard | http://localhost:6333/dashboard |

### 4. Run the frontend (dev mode)

The React frontend is not part of the Docker Compose stack — start it separately:

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

The Vite dev server proxies all `/api/*` requests to the Spring Boot control plane at `localhost:8080`, so no additional CORS configuration is needed in development.

---

## Next Steps

- **Develop locally without Docker** — see [Local Development](local-development.md) for per-service instructions.
- **Upload a document and start chatting** — use the React frontend at `http://localhost:5173`, or call the REST API directly (see [API Reference](api-reference.md)).
- **Understand how ingestion, session history, and self-correction work** — see [Ingestion Pipeline, Session History & Reflexion](ingestion-pipeline.md).
- **Tune environment variables** — see [Configuration](configuration.md).
