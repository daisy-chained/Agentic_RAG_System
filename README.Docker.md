## Docker

### Building and running the full stack

Start all services (Java API, Python inference engine, PostgreSQL, Qdrant, Arize Phoenix, and the React frontend) with a single command from the project root:

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| REST API | http://localhost:8080 |
| React Frontend | http://localhost:3000 |
| Arize Phoenix UI | http://localhost:6006 |
| Qdrant Dashboard | http://localhost:6333/dashboard |

### Build contexts

Two services require the project root as their build context so that the `shared-protos/` directory is accessible to both Dockerfiles:

```yaml
# compose.yaml
java-api:
  build:
    context: .                        # project root
    dockerfile: control-plane/Dockerfile

inference-engine:
  build:
    context: .                        # project root
    dockerfile: inference-engine/Dockerfile
```

The `frontend` service uses `./frontend` as its build context and is served by nginx on port 3000 inside the container.

### Ollama (host machine)

The inference engine calls Ollama, which runs **on the host machine** (not inside Docker). The container reaches it via `host.docker.internal`:

```yaml
inference-engine:
  environment:
    - OLLAMA_HOST=http://host.docker.internal:11434
  extra_hosts:
    - "host.docker.internal:host-gateway"   # Linux only
```

Pull the required models before starting the stack:

```bash
ollama pull llama3.2          # chat model
ollama pull nomic-embed-text  # embedding model
```

### Individual image builds

```bash
# Inference engine
docker build -f inference-engine/Dockerfile -t agentic-rag/inference-engine .

# Control plane
docker build -f control-plane/Dockerfile -t agentic-rag/control-plane .

# Frontend
docker build -f frontend/Dockerfile -t agentic-rag/frontend ./frontend
```

### Deploying to the cloud

If your cloud provider uses a different CPU architecture (e.g., you develop on Apple Silicon and deploy to amd64):

```bash
docker build --platform=linux/amd64 \
  -f inference-engine/Dockerfile \
  -t registry.example.com/agentic-rag/inference-engine:latest .

docker push registry.example.com/agentic-rag/inference-engine:latest
```

See Docker's [getting started guide](https://docs.docker.com/go/get-started-sharing/) for more detail on building and pushing multi-platform images.
