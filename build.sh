#!/bin/bash
# build.sh - Automates the gRPC code generation

echo "Generating Python stubs..."
source inference-engine/venv/bin/activate
python3 -m grpc_tools.protoc \
    -I ./shared-protos \
    --python_out=./inference-engine \
    --grpc_python_out=./inference-engine \
    ./shared-protos/ai_service.proto

echo "Building Java Control Plane..."
cd control-plane && mvn clean compile