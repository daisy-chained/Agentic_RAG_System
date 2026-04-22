# ── Stage 1: Generate gRPC stubs from the shared proto contract ───────────────
# Build context is the project root so we can access shared-protos/
FROM python:3.12-slim AS builder

WORKDIR /build

RUN pip install --no-cache-dir grpcio-tools==1.62.2 protobuf==4.25.3

COPY shared-protos/ai_service.proto ./shared-protos/

RUN python3 -m grpc_tools.protoc \
    -I ./shared-protos \
    --python_out=. \
    --grpc_python_out=. \
    ./shared-protos/ai_service.proto

# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM python:3.12-slim AS final

WORKDIR /app

# Install runtime dependencies before copying app code for better layer caching
COPY inference-engine/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy generated stubs from the builder stage
COPY --from=builder /build/ai_service_pb2.py /build/ai_service_pb2_grpc.py ./

# Copy application source
COPY inference-engine/main.py inference-engine/rag.py ./

# Run as a non-privileged user
ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser
USER appuser

EXPOSE 50051

CMD ["python3", "main.py"]
