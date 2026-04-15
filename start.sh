#!/bin/bash
# start.sh — Starts the full Polyglot RAG stack in one command.
#
# Usage:  ./start.sh
# Stop:   Ctrl+C  (kills the Java process and the background Python process)
#
# Requires: docker, java, mvn, python3 (with venv already set up)
# Ollama must be running separately: ollama serve

set -e

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

ROOT="$(cd "$(dirname "$0")" && pwd)"
PYTHON_PID=""

# ── Cleanup on exit ───────────────────────────────────────────────────────────
cleanup() {
    echo ""
    echo -e "${YELLOW}Shutting down...${NC}"
    [ -n "$PYTHON_PID" ] && kill "$PYTHON_PID" 2>/dev/null && \
        echo -e "${GREEN}✓ Inference engine stopped${NC}"
    [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null && \
        echo -e "${GREEN}✓ Frontend dev server stopped${NC}"
    echo -e "${GREEN}✓ Done. Postgres container left running (use 'docker stop rag-postgres' to stop it).${NC}"
}
trap cleanup INT TERM EXIT

# ── Header ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
echo "  ╔═══════════════════════════════════════╗"
echo "  ║   Polyglot RAG System — Local Dev     ║"
echo "  ╚═══════════════════════════════════════╝"
echo -e "${NC}"

# ── Step 1: Databases (Postgres & Qdrant) ───────────────────────────────────
echo -e "${YELLOW}[1/3] Starting Databases...${NC}"

# Start Postgres
if docker ps --filter name=rag-postgres --filter status=running -q | grep -q .; then
    echo -e "${GREEN}✓ Postgres already running${NC}"
else
    if docker start rag-postgres 2>/dev/null; then
        echo -e "${GREEN}✓ Restarted Postgres container${NC}"
    else
        echo "    Starting fresh Postgres container..."
        docker run -d --name rag-postgres \
            -p 5432:5432 \
            -e POSTGRES_DB=orchestrator \
            -e POSTGRES_USER=orchestrator \
            -e POSTGRES_PASSWORD=secret \
            pgvector/pgvector:pg16 > /dev/null
        echo -e "${GREEN}✓ Started Postgres${NC}"
    fi
fi

# Wait for Postgres
printf "    Waiting for Postgres to be ready"
until docker exec rag-postgres pg_isready -U orchestrator -q 2>/dev/null; do
    printf "."
    sleep 1
done
echo -e " ${GREEN}ready on :5432${NC}"

# Start Qdrant
if docker ps --filter name=rag-qdrant --filter status=running -q | grep -q .; then
    echo -e "${GREEN}✓ Qdrant already running${NC}"
else
    if docker start rag-qdrant 2>/dev/null; then
        echo -e "${GREEN}✓ Restarted Qdrant container${NC}"
    else
        echo "    Starting fresh Qdrant container..."
        docker run -d --name rag-qdrant \
            -p 6333:6333 -p 6334:6334 \
            qdrant/qdrant:latest > /dev/null
        echo -e "${GREEN}✓ Started Qdrant${NC}"
    fi
fi

# Start Phoenix
if docker ps --filter name=rag-phoenix --filter status=running -q | grep -q .; then
    echo -e "${GREEN}✓ Phoenix already running${NC}"
else
    if docker start rag-phoenix 2>/dev/null; then
        echo -e "${GREEN}✓ Restarted Phoenix container${NC}"
    else
        echo "    Starting fresh Phoenix container..."
        docker run -d --name rag-phoenix \
            -p 6006:6006 -p 4317:4317 -p 4318:4318 \
            arizephoenix/phoenix:latest > /dev/null
        echo -e "${GREEN}✓ Started Phoenix${NC}"
    fi
fi

# Wait for Qdrant (REST API on 6333)
printf "    Waiting for Qdrant to be ready"
for i in $(seq 1 60); do
    if curl -s http://localhost:6333/readyz >/dev/null; then
        break
    fi
    printf "."
    sleep 0.5
done
echo -e " ${GREEN}ready on :6333${NC}"

# ── Step 2: Python Inference Engine ──────────────────────────────────────────
echo -e "${YELLOW}[2/3] Inference Engine (Ollama + gRPC)...${NC}"

cd "$ROOT/inference-engine"

if [ ! -d "venv" ]; then
    echo -e "${RED}ERROR: venv not found.${NC}"
    echo "       Run: python3 -m venv venv && ./venv/bin/pip install -r requirements.txt"
    exit 1
fi

. venv/bin/activate
python3 main.py > "$ROOT/inference-engine.log" 2>&1 &
PYTHON_PID=$!

# Wait until the gRPC port is open (bash built-in TCP, no nc required)
printf "    Waiting for gRPC on :50051"
for i in $(seq 1 60); do
    if bash -c 'echo >/dev/tcp/localhost/50051' 2>/dev/null; then
        break
    fi
    printf "."
    sleep 0.5
done
echo -e " ${GREEN}ready on :50051 (PID $PYTHON_PID)${NC}"
echo "    Logs → $ROOT/inference-engine.log"

# ── Step 3: Rosetta Frontend ──────────────────────────────────────────────────
echo -e "${YELLOW}[3/4] Rosetta Frontend...${NC}"
cd "$ROOT"
if [ -d "frontend" ]; then
    cd frontend
    npm run dev > "$ROOT/frontend.log" 2>&1 &
    FRONTEND_PID=$!
    echo -e "${GREEN}✓ Frontend dev server started on http://localhost:5173${NC}"
    echo "    Logs → $ROOT/frontend.log"
    cd ..
else
    echo -e "${RED}WARN: Frontend directory not found.${NC}"
fi

# ── Step 4: Java Control Plane ───────────────────────────────────────────────
echo -e "${YELLOW}[4/4] Control Plane (Spring Boot)...${NC}"
echo ""
cd "$ROOT/control-plane"

# Run Maven in the foreground — logs stream to the terminal.
# Ctrl+C here triggers cleanup() which kills the Python and Frontend background processes.
mvn spring-boot:run -q
