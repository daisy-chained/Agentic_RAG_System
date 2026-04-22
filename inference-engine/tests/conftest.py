"""
Shared pytest fixtures for the inference-engine test suite.

Generated proto stubs (ai_service_pb2, ai_service_pb2_grpc) are built by CI
before running tests.  The conftest adds the inference-engine package root to
sys.path so tests can import main.py modules directly.
"""
import sys
import os
import types
from unittest.mock import MagicMock

# ---------------------------------------------------------------------------
# Make `inference-engine/` importable without installing it as a package
# ---------------------------------------------------------------------------
_ENGINE_DIR = os.path.join(os.path.dirname(__file__), "..")
if _ENGINE_DIR not in sys.path:
    sys.path.insert(0, os.path.abspath(_ENGINE_DIR))

# ---------------------------------------------------------------------------
# Stub out heavy runtime dependencies so tests never need real Qdrant/Ollama
# ---------------------------------------------------------------------------

def _make_stub_module(name: str) -> types.ModuleType:
    mod = types.ModuleType(name)
    sys.modules[name] = mod
    return mod


# Stub opentelemetry so importing main.py doesn't fail in the test env
for _pkg in [
    "opentelemetry",
    "opentelemetry.exporter",
    "opentelemetry.exporter.otlp",
    "opentelemetry.exporter.otlp.proto",
    "opentelemetry.exporter.otlp.proto.grpc",
    "opentelemetry.exporter.otlp.proto.grpc.trace_exporter",
    "opentelemetry.sdk",
    "opentelemetry.sdk.trace",
    "opentelemetry.sdk.trace.export",
    "opentelemetry",
    "openinference",
    "openinference.instrumentation",
    "openinference.instrumentation.langchain",
]:
    if _pkg not in sys.modules:
        _make_stub_module(_pkg)

# Provide minimal attrs that main.py actually calls at module level
sys.modules["opentelemetry.exporter.otlp.proto.grpc.trace_exporter"].OTLPSpanExporter = MagicMock()
sys.modules["opentelemetry.sdk.trace"].TracerProvider = MagicMock()
sys.modules["opentelemetry.sdk.trace.export"].SimpleSpanProcessor = MagicMock()
_otel_trace = _make_stub_module("opentelemetry.trace")
_otel_trace.set_tracer_provider = MagicMock()
_otel_trace.get_tracer_provider = MagicMock(return_value=MagicMock())
sys.modules["opentelemetry.instrumentation"] = _make_stub_module("opentelemetry.instrumentation")
sys.modules["openinference.instrumentation.langchain"].LangChainInstrumentor = MagicMock()

# Stub langchain_ollama so that `from langchain_ollama import ChatOllama` in
# main.py succeeds without the real package being installed.
_langchain_ollama = _make_stub_module("langchain_ollama")
_langchain_ollama.OllamaEmbeddings = MagicMock()
_langchain_ollama.ChatOllama = MagicMock()
