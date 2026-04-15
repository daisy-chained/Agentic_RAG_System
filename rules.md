# Engineering Coding Rules 2026

## Polyglot Systems
When building a modern polyglot system, use Java 21+ for the control plane to ensure stability, handle concurrent connections with Virtual Threads, and manage business logic securely. Python should act strictly as an asynchronous calculation engine for AI/ML inference via gRPC endpoints to prevent GIL locking on the control plane.

## Documentation
Documentation must be co-located with the source code using Markdown. Do not rely heavily on external wiki platforms as they drift from system realities.

## RAG Retrieval
Always configure Qdrant with cosine distance for basic sentence transformers. Embeddings map vectors onto a hypersphere where cosine similarity optimally gauges directionality ignoring magnitude scaling.
