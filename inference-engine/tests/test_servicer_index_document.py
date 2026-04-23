"""
Unit tests for AiAgentServicer.IndexDocument.

PyPDFLoader, TextLoader, RecursiveCharacterTextSplitter, and
rag.vector_store.add_documents are mocked.
"""
import os
import sys
import types
from unittest.mock import AsyncMock, MagicMock, call, patch

import pytest


# ---------------------------------------------------------------------------
# Reuse proto stubs from test_servicer_process_query (or recreate if needed)
# ---------------------------------------------------------------------------

def _ensure_proto_stubs():
    if "ai_service_pb2" not in sys.modules:
        mod = types.ModuleType("ai_service_pb2")
        sys.modules["ai_service_pb2"] = mod

        class AgentResponse:
            def __init__(self, answer="", source_documents=None,
                         confidence_score=0.0, latency_ms=0):
                self.answer = answer
                self.source_documents = source_documents or []
                self.confidence_score = confidence_score
                self.latency_ms = latency_ms

        class IndexResponse:
            def __init__(self, chunk_count=0, status=""):
                self.chunk_count = chunk_count
                self.status = status

        mod.AgentResponse = AgentResponse
        mod.IndexResponse = IndexResponse

    if "ai_service_pb2_grpc" not in sys.modules:
        mod2 = types.ModuleType("ai_service_pb2_grpc")
        sys.modules["ai_service_pb2_grpc"] = mod2

        class AiAgentServiceServicer:
            pass

        mod2.AiAgentServiceServicer = AiAgentServiceServicer
        mod2.add_AiAgentServiceServicer_to_server = MagicMock()


_ensure_proto_stubs()

for pkg in ["langchain_ollama", "langchain_qdrant", "qdrant_client",
            "qdrant_client.http", "qdrant_client.http.models", "dotenv",
            "langchain_community", "langchain_community.document_loaders",
            "langchain_text_splitters", "sentence_transformers"]:
    if pkg not in sys.modules:
        m = types.ModuleType(pkg)
        sys.modules[pkg] = m

sys.modules["dotenv"].load_dotenv = MagicMock()
sys.modules["langchain_ollama"].OllamaEmbeddings = MagicMock()
sys.modules["langchain_qdrant"].QdrantVectorStore = MagicMock()
sys.modules["qdrant_client"].QdrantClient = MagicMock()
_dist = MagicMock(); _dist.COSINE = "Cosine"
sys.modules["qdrant_client.http.models"].Distance = _dist
sys.modules["qdrant_client.http.models"].VectorParams = MagicMock()
sys.modules["sentence_transformers"].CrossEncoder = MagicMock()

import rag as rag_module  # noqa: E402

rag_module.vector_store = MagicMock()

import main as main_module  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

class _FakeIndexRequest:
    def __init__(self, filename="doc.pdf", document_id="abc-123",
                 user_id="user1", file_content=b"bytes"):
        self.filename = filename
        self.document_id = document_id
        self.user_id = user_id
        self.file_content = file_content


class _FakeContext:
    def __init__(self):
        self._code = None
        self._details = None

    def set_code(self, c): self._code = c

    def set_details(self, d): self._details = d


def _make_mock_loader(chunks):
    """Returns a constructor that yields a loader whose load() returns chunks."""
    loader_instance = MagicMock()
    loader_instance.load.return_value = chunks
    return MagicMock(return_value=loader_instance)


def _make_splitter(chunks):
    splitter = MagicMock()
    splitter.split_documents.return_value = chunks
    return MagicMock(return_value=splitter)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestIndexDocument:

    @pytest.fixture(autouse=True)
    def reset(self):
        rag_module.vector_store.reset_mock()

    @pytest.mark.asyncio
    async def test_pdf_file_uses_pyPdfLoader(self):
        from langchain_core.documents import Document

        doc = Document(page_content="pdf content", metadata={})
        mock_loader_cls = _make_mock_loader([doc])
        mock_splitter_cls = _make_splitter([doc])

        with patch.dict(sys.modules, {
            "langchain_community.document_loaders": MagicMock(
                PyPDFLoader=mock_loader_cls, TextLoader=MagicMock()
            ),
        }), patch("main.PyPDFLoader", mock_loader_cls), \
             patch("main.TextLoader", MagicMock()), \
             patch("main.RecursiveCharacterTextSplitter", mock_splitter_cls), \
             patch("os.remove"):

            resp = await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(filename="report.pdf"), _FakeContext()
            )

        mock_loader_cls.assert_called_once()
        assert resp.status == "SUCCESS"

    @pytest.mark.asyncio
    async def test_txt_file_uses_text_loader(self):
        from langchain_core.documents import Document

        doc = Document(page_content="text content", metadata={})
        mock_loader_cls = _make_mock_loader([doc])
        mock_splitter_cls = _make_splitter([doc])

        with patch("main.PyPDFLoader", MagicMock()), \
             patch("main.TextLoader", mock_loader_cls), \
             patch("main.RecursiveCharacterTextSplitter", mock_splitter_cls), \
             patch("os.remove"):

            resp = await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(filename="notes.txt"), _FakeContext()
            )

        mock_loader_cls.assert_called_once()
        assert resp.status == "SUCCESS"

    @pytest.mark.asyncio
    async def test_metadata_attached_to_each_chunk(self):
        from langchain_core.documents import Document

        chunk = Document(page_content="chunk", metadata={})
        mock_loader_cls = _make_mock_loader([chunk])
        mock_splitter_cls = _make_splitter([chunk])

        with patch("main.PyPDFLoader", mock_loader_cls), \
             patch("main.TextLoader", MagicMock()), \
             patch("main.RecursiveCharacterTextSplitter", mock_splitter_cls), \
             patch("os.remove"):

            await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(filename="test.pdf", document_id="did-1",
                                  user_id="userX"),
                _FakeContext()
            )

        assert chunk.metadata["source"] == "test.pdf"
        assert chunk.metadata["document_id"] == "did-1"
        assert chunk.metadata["uploaded_by"] == "userX"

    @pytest.mark.asyncio
    async def test_success_response_has_correct_chunk_count(self):
        from langchain_core.documents import Document

        chunks = [Document(page_content=f"c{i}", metadata={}) for i in range(4)]
        mock_loader_cls = _make_mock_loader(chunks)
        mock_splitter_cls = _make_splitter(chunks)

        with patch("main.PyPDFLoader", mock_loader_cls), \
             patch("main.TextLoader", MagicMock()), \
             patch("main.RecursiveCharacterTextSplitter", mock_splitter_cls), \
             patch("os.remove"):

            resp = await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(), _FakeContext()
            )

        assert resp.chunk_count == 4
        assert resp.status == "SUCCESS"

    @pytest.mark.asyncio
    async def test_exception_sets_internal_grpc_status_and_returns_failed(self):
        import grpc

        with patch("main.PyPDFLoader", side_effect=Exception("loader crash")), \
             patch("main.TextLoader", side_effect=Exception("loader crash")), \
             patch("os.remove"):

            ctx = _FakeContext()
            resp = await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(), ctx
            )

        assert ctx._code == grpc.StatusCode.INTERNAL
        assert resp.status == "FAILED"

    @pytest.mark.asyncio
    async def test_temp_file_removed_in_finally_on_success(self):
        from langchain_core.documents import Document

        chunk = Document(page_content="x", metadata={})
        mock_loader_cls = _make_mock_loader([chunk])
        mock_splitter_cls = _make_splitter([chunk])

        with patch("main.PyPDFLoader", mock_loader_cls), \
             patch("main.TextLoader", MagicMock()), \
             patch("main.RecursiveCharacterTextSplitter", mock_splitter_cls), \
             patch("os.remove") as mock_remove:

            await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(), _FakeContext()
            )

        mock_remove.assert_called_once()

    @pytest.mark.asyncio
    async def test_temp_file_removed_in_finally_on_exception(self):
        with patch("main.PyPDFLoader", side_effect=Exception("boom")), \
             patch("main.TextLoader", side_effect=Exception("boom")), \
             patch("os.remove") as mock_remove:

            await main_module.AiAgentServicer().IndexDocument(
                _FakeIndexRequest(), _FakeContext()
            )

        mock_remove.assert_called_once()
