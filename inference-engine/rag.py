import os
from dotenv import load_dotenv

from langchain_ollama import OllamaEmbeddings
from langchain_qdrant import QdrantVectorStore
from qdrant_client import QdrantClient
from qdrant_client.http.models import Distance, VectorParams

load_dotenv()

OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
QDRANT_HOST = os.getenv("QDRANT_HOST", "localhost")
QDRANT_PORT = int(os.getenv("QDRANT_PORT", "6333"))

# The default embedding model we downloaded
EMBED_MODEL = "nomic-embed-text"
COLLECTION_NAME = "agentic_rag"

# 1. Initialize Embeddings via Ollama
embeddings = OllamaEmbeddings(
    model=EMBED_MODEL,
    base_url=OLLAMA_HOST
)

# 2. Initialize Qdrant Client
qdrant_client = QdrantClient(host=QDRANT_HOST, port=QDRANT_PORT)

# 3. Ensure Collection Exists
def init_vector_store() -> QdrantVectorStore:
    # Nomic embedding generates 768 dimensions by default. Check if collection exists:
    if not qdrant_client.collection_exists(COLLECTION_NAME):
        print(f"Creating Qdrant collection: {COLLECTION_NAME}")
        qdrant_client.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=VectorParams(size=768, distance=Distance.COSINE),
        )
    else:
        print(f"✓ Qdrant collection '{COLLECTION_NAME}' ready.")

    return QdrantVectorStore(
        client=qdrant_client,
        collection_name=COLLECTION_NAME,
        embedding=embeddings,
    )

vector_store = init_vector_store()

print(f"✓ RAG Module initialized — connected to Qdrant at {QDRANT_HOST}:{QDRANT_PORT}")
