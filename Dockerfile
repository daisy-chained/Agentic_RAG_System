# This file is intentionally left as a placeholder.
#
# Each service has its own Dockerfile:
#   control-plane/Dockerfile   — Java 21 / Spring Boot (multi-stage Maven → JRE)
#   inference-engine/Dockerfile — Python 3.12 / gRPC server (multi-stage stub gen → runtime)
#   frontend/Dockerfile        — React/Vite → nginx (multi-stage Node → nginx)
#
# To build and run the full stack use Docker Compose from the project root:
#
#   docker compose up --build
