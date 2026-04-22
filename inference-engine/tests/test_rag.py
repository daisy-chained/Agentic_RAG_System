"""
Unit tests for rag.init_vector_store().
QdrantClient and OllamaEmbeddings are mocked at the langchain boundary.
"""
import sys
import types
from unittest.mock import MagicMock, patch, call

import pytest


# ---------------------------------------------------------------------------
# Stub heavy dependencies before importing rag
# ---------------------------------------------------------------------------

def _stub(name):
    m = types.ModuleType(name)
    sys.modules[name] = m
    return m


for pkg in [
    "langchain_ollama",
    "langchain_qdrant",
    "qdrant_client",
    "qdrant_client.http",
    "qdrant_client.http.models",
    "dotenv",
]:
    if pkg not in sys.modules:
        _stub(pkg)

sys.modules["dotenv"].load_dotenv = MagicMock()
sys.modules["langchain_ollama"].OllamaEmbeddings = MagicMock()
sys.modules["langchain_qdrant"].QdrantVectorStore = MagicMock()
_distance = MagicMock()
_distance.COSINE = "Cosine"
sys.modules["qdrant_client.http.models"].Distance = _distance
sys.modules["qdrant_client.http.models"].VectorParams = MagicMock()
sys.modules["qdrant_client"].QdrantClient = MagicMock()

import importlib
import rag as rag_module  # noqa: E402 — must come after stubs


class TestInitVectorStore:

    def setup_method(self):
        # Reset the module-level qdrant_client mock before each test
        self.mock_client = MagicMock()
        rag_module.qdrant_client = self.mock_client

    def test_creates_collection_when_it_does_not_exist(self):
        self.mock_client.collection_exists.return_value = False

        result = rag_module.init_vector_store()

        self.mock_client.create_collection.assert_called_once()
        call_kwargs = self.mock_client.create_collection.call_args[1]
        assert call_kwargs["collection_name"] == rag_module.COLLECTION_NAME

    def test_skips_create_when_collection_already_exists(self):
        self.mock_client.collection_exists.return_value = True

        rag_module.init_vector_store()

        self.mock_client.create_collection.assert_not_called()

    def test_returns_qdrant_vector_store_with_correct_collection(self):
        self.mock_client.collection_exists.return_value = True
        mock_store = MagicMock()

        # Patch the name inside rag's own namespace so other test files replacing
        # sys.modules["langchain_qdrant"].QdrantVectorStore don't affect us.
        with patch.object(rag_module, "QdrantVectorStore", return_value=mock_store) as mock_vs:
            result = rag_module.init_vector_store()

        mock_vs.assert_called_once()
        kwargs = mock_vs.call_args[1]
        assert kwargs["collection_name"] == rag_module.COLLECTION_NAME
        assert result is mock_store
