"""
Unit tests for AiAgentServicer.ProcessQuery.

All external I/O (Qdrant similarity search, Ollama LLM) is mocked.
"""
import sys
import types
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Minimal proto stubs so tests don't need generated files
# ---------------------------------------------------------------------------
def _make_proto_module():
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
    return mod


def _make_grpc_module():
    mod = types.ModuleType("ai_service_pb2_grpc")
    sys.modules["ai_service_pb2_grpc"] = mod

    class AiAgentServiceServicer:
        pass

    mod.AiAgentServiceServicer = AiAgentServiceServicer
    mod.add_AiAgentServiceServicer_to_server = MagicMock()
    return mod


_make_proto_module()
_make_grpc_module()

# Stub heavy deps before importing main
for pkg in ["langchain_ollama", "langchain_qdrant", "qdrant_client",
            "qdrant_client.http", "qdrant_client.http.models", "dotenv",
            "langchain_community", "langchain_community.document_loaders",
            "langchain_text_splitters"]:
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
sys.modules["langchain_community.document_loaders"].PyPDFLoader = MagicMock()
sys.modules["langchain_community.document_loaders"].TextLoader = MagicMock()
sys.modules["langchain_text_splitters"].RecursiveCharacterTextSplitter = MagicMock()

import importlib
import rag as rag_module  # noqa: E402

# Patch rag.vector_store before importing main
rag_module.vector_store = MagicMock()

import main as main_module  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

class _FakeRequest:
    def __init__(self, query="test query", user_id="u1", session_history=None):
        self.query = query
        self.user_id = user_id
        self.session_history = session_history or []


class _FakeContext:
    def __init__(self):
        self._code = None
        self._details = None

    def set_code(self, code):
        self._code = code

    def set_details(self, details):
        self._details = details


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestProcessQuery:

    @pytest.fixture(autouse=True)
    def reset_mocks(self):
        rag_module.vector_store.reset_mock()
        main_module._llm.ainvoke = AsyncMock()

    # -- happy path ----------------------------------------------------------

    @pytest.mark.asyncio
    async def test_happy_path_approve(self):
        from langchain_core.documents import Document

        doc = Document(page_content="The answer is 42", metadata={"source": "wiki.pdf"})
        rag_module.vector_store.similarity_search_with_score = MagicMock(
            return_value=[(doc, 0.95)]
        )

        draft_msg = MagicMock(); draft_msg.content = "42"
        approve_msg = MagicMock(); approve_msg.content = "APPROVE"
        main_module._llm.ainvoke = AsyncMock(side_effect=[draft_msg, approve_msg])

        ctx = _FakeContext()
        resp = await main_module.AiAgentServicer().ProcessQuery(_FakeRequest(), ctx)

        assert resp.answer == "42"
        assert "wiki.pdf" in resp.source_documents
        assert ctx._code is None  # no error

    @pytest.mark.asyncio
    async def test_reflection_detects_hallucination_overrides_answer(self):
        from langchain_core.documents import Document

        doc = Document(page_content="Context text", metadata={"source": "src.pdf"})
        rag_module.vector_store.similarity_search_with_score = MagicMock(
            return_value=[(doc, 0.8)]
        )

        draft_msg = MagicMock(); draft_msg.content = "hallucinated answer"
        corrected_msg = MagicMock(); corrected_msg.content = "corrected answer"
        main_module._llm.ainvoke = AsyncMock(side_effect=[draft_msg, corrected_msg])

        resp = await main_module.AiAgentServicer().ProcessQuery(_FakeRequest(), _FakeContext())

        assert resp.answer.startswith("[Reflexion Self-Corrected]")
        assert "corrected answer" in resp.answer

    @pytest.mark.asyncio
    async def test_rag_retrieval_failure_falls_back_to_empty_context(self):
        rag_module.vector_store.similarity_search_with_score = MagicMock(
            side_effect=Exception("Qdrant down")
        )
        draft_msg = MagicMock(); draft_msg.content = "best guess"
        approve_msg = MagicMock(); approve_msg.content = "APPROVE"
        main_module._llm.ainvoke = AsyncMock(side_effect=[draft_msg, approve_msg])

        ctx = _FakeContext()
        resp = await main_module.AiAgentServicer().ProcessQuery(_FakeRequest(), ctx)

        # LLM call still happens despite RAG failure
        assert main_module._llm.ainvoke.call_count == 2
        assert ctx._code is None

    @pytest.mark.asyncio
    async def test_llm_failure_sets_internal_grpc_status(self):
        import grpc
        rag_module.vector_store.similarity_search_with_score = MagicMock(return_value=[])
        main_module._llm.ainvoke = AsyncMock(side_effect=Exception("LLM crashed"))

        ctx = _FakeContext()
        resp = await main_module.AiAgentServicer().ProcessQuery(_FakeRequest(), ctx)

        assert ctx._code == grpc.StatusCode.INTERNAL
        assert resp.answer == ""

    # -- session history parsing ---------------------------------------------

    @pytest.mark.asyncio
    async def test_session_history_user_and_assistant_messages_parsed(self):
        from langchain_core.messages import HumanMessage, AIMessage

        rag_module.vector_store.similarity_search_with_score = MagicMock(return_value=[])
        approve_msg = MagicMock(); approve_msg.content = "APPROVE"
        captured_msgs = []

        async def capture(msgs):
            captured_msgs.extend(msgs)
            return approve_msg

        main_module._llm.ainvoke = AsyncMock(side_effect=capture)

        req = _FakeRequest(session_history=["user: hello", "assistant: hi there"])
        await main_module.AiAgentServicer().ProcessQuery(req, _FakeContext())

        content_types = {m.content: type(m).__name__ for m in captured_msgs}
        assert "hello" in content_types
        assert "hi there" in content_types
