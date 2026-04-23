"""
Cross-encoder reranker for the RAG retrieval pipeline.

After the vector store returns a broad candidate set, this module scores each
(query, chunk) pair with a lightweight cross-encoder model and returns only the
top-N most-relevant documents.  Running fully on-device, it is orders of
magnitude cheaper than an LLM relevance call while delivering far better
precision than cosine-distance alone.
"""
import os
from typing import List, Tuple

from langchain_core.documents import Document
from sentence_transformers import CrossEncoder

# Model is downloaded once on first use and cached by sentence-transformers.
_RERANKER_MODEL = os.getenv(
    "RERANKER_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2"
)
_TOP_N = int(os.getenv("RERANKER_TOP_N", "3"))

_cross_encoder: CrossEncoder | None = None


def _get_cross_encoder() -> CrossEncoder:
    """Lazy-load the cross-encoder so the model is only downloaded on first use."""
    global _cross_encoder
    if _cross_encoder is None:
        print(f"✓ Reranker loading model '{_RERANKER_MODEL}' …")
        _cross_encoder = CrossEncoder(_RERANKER_MODEL)
        print(f"✓ Reranker ready — model='{_RERANKER_MODEL}' top_n={_TOP_N}")
    return _cross_encoder


def rerank(
    query: str,
    candidates: List[Tuple[Document, float]],
    top_n: int | None = None,
) -> List[Tuple[Document, float]]:
    """Score *candidates* with the cross-encoder and return the top-*n* results.

    Parameters
    ----------
    query:
        The user query text.
    candidates:
        List of ``(Document, vector_score)`` pairs as returned by
        ``QdrantVectorStore.similarity_search_with_score``.
    top_n:
        Number of results to return after reranking.  Defaults to the
        ``RERANKER_TOP_N`` environment variable (fallback: 3).

    Returns
    -------
    List of ``(Document, reranker_score)`` pairs, highest score first, capped at
    *top_n* entries.
    """
    if not candidates:
        return []

    n = top_n if top_n is not None else _TOP_N
    encoder = _get_cross_encoder()
    pairs = [(query, doc.page_content) for doc, _ in candidates]
    scores = encoder.predict(pairs)

    scored = sorted(
        zip([doc for doc, _ in candidates], scores),
        key=lambda x: x[1],
        reverse=True,
    )
    return scored[:n]
