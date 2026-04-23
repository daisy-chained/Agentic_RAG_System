"""
Unit tests for the cross-encoder reranker module.

The CrossEncoder model is mocked so tests run without downloading model weights.
"""
import sys
import types
from unittest.mock import MagicMock

import pytest
import numpy as np

# ---------------------------------------------------------------------------
# Stub sentence_transformers before importing reranker
# ---------------------------------------------------------------------------
if "sentence_transformers" not in sys.modules:
    _st = types.ModuleType("sentence_transformers")
    sys.modules["sentence_transformers"] = _st

sys.modules["sentence_transformers"].CrossEncoder = MagicMock()

# ---------------------------------------------------------------------------
# Stub other deps that conftest may not have installed yet in isolated runs
# ---------------------------------------------------------------------------
for _pkg in ["dotenv", "langchain_ollama", "langchain_qdrant", "qdrant_client",
             "qdrant_client.http", "qdrant_client.http.models"]:
    if _pkg not in sys.modules:
        sys.modules[_pkg] = types.ModuleType(_pkg)

sys.modules["dotenv"].load_dotenv = MagicMock()
sys.modules["langchain_ollama"].OllamaEmbeddings = MagicMock()
sys.modules["langchain_qdrant"].QdrantVectorStore = MagicMock()
sys.modules["qdrant_client"].QdrantClient = MagicMock()
_d = MagicMock(); _d.COSINE = "Cosine"
sys.modules["qdrant_client.http.models"].Distance = _d
sys.modules["qdrant_client.http.models"].VectorParams = MagicMock()

import reranker as reranker_module  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_doc(content: str, source: str = "doc.pdf"):
    from langchain_core.documents import Document
    return Document(page_content=content, metadata={"source": source})


def _mock_encoder(scores):
    """Return a CrossEncoder mock whose predict() returns *scores* as an ndarray."""
    enc = MagicMock()
    enc.predict.return_value = np.array(scores)
    return enc


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestRerank:

    def setup_method(self):
        # Reset the lazy-loaded singleton before each test
        reranker_module._cross_encoder = None

    def test_empty_candidates_returns_empty(self):
        result = reranker_module.rerank("query", [])
        assert result == []

    def test_results_are_sorted_by_score_descending(self):
        docs = [_make_doc(f"chunk {i}") for i in range(3)]
        candidates = [(d, 0.5) for d in docs]

        reranker_module._cross_encoder = _mock_encoder([0.1, 0.9, 0.4])

        result = reranker_module.rerank("query", candidates)

        scores = [s for _, s in result]
        assert scores == sorted(scores, reverse=True)

    def test_top_n_limits_output(self):
        docs = [_make_doc(f"chunk {i}") for i in range(5)]
        candidates = [(d, 0.5) for d in docs]

        reranker_module._cross_encoder = _mock_encoder([0.3, 0.9, 0.1, 0.7, 0.5])

        result = reranker_module.rerank("query", candidates, top_n=2)

        assert len(result) == 2

    def test_top_n_respects_env_default(self):
        """top_n should default to _TOP_N (3) when not explicitly provided."""
        docs = [_make_doc(f"chunk {i}") for i in range(6)]
        candidates = [(d, 0.5) for d in docs]

        reranker_module._cross_encoder = _mock_encoder([0.1, 0.2, 0.3, 0.4, 0.5, 0.6])

        result = reranker_module.rerank("query", candidates)

        assert len(result) == reranker_module._TOP_N

    def test_correct_document_is_ranked_first(self):
        docs = [_make_doc("irrelevant chunk"), _make_doc("best answer chunk")]
        candidates = [(d, 0.5) for d in docs]

        reranker_module._cross_encoder = _mock_encoder([0.2, 0.95])

        result = reranker_module.rerank("query", candidates)

        assert result[0][0].page_content == "best answer chunk"

    def test_cross_encoder_receives_correct_pairs(self):
        doc = _make_doc("some content")
        mock_enc = _mock_encoder([0.8])
        reranker_module._cross_encoder = mock_enc

        reranker_module.rerank("my query", [(doc, 0.5)])

        call_args = mock_enc.predict.call_args[0][0]
        assert call_args == [("my query", "some content")]

    def test_top_n_larger_than_candidates_returns_all(self):
        docs = [_make_doc("a"), _make_doc("b")]
        candidates = [(d, 0.5) for d in docs]

        reranker_module._cross_encoder = _mock_encoder([0.6, 0.3])

        result = reranker_module.rerank("query", candidates, top_n=10)

        assert len(result) == 2

    def test_lazy_load_initialises_encoder_once(self):
        """_get_cross_encoder() must only create the model on first call."""
        reranker_module._cross_encoder = None
        mock_instance = MagicMock()
        mock_instance.predict = MagicMock(return_value=np.array([0.5]))
        mock_cls = MagicMock(return_value=mock_instance)

        # Patch the name bound in reranker's own namespace (import-time binding)
        original = reranker_module.CrossEncoder
        reranker_module.CrossEncoder = mock_cls
        try:
            doc = _make_doc("text")
            reranker_module.rerank("q", [(doc, 0.5)])
            reranker_module.rerank("q", [(doc, 0.5)])

            # Constructor called exactly once despite two rerank calls
            mock_cls.assert_called_once_with(reranker_module._RERANKER_MODEL)
        finally:
            reranker_module.CrossEncoder = original
