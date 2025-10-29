import google.genai as genai
import numpy as np
import faiss
import json
import os
import time
from pathlib import Path
from dotenv import load_dotenv
from typing import List, Dict, Any, Optional

# ======== CONFIGURATION ========

# --- Load Environment Variables ---
try:
    repo_root = Path(__file__).resolve().parents[1]
    dotenv_path = repo_root / '.env'
    if dotenv_path.exists():
        load_dotenv(dotenv_path)
    else:
        load_dotenv()
except Exception:
    pass

# --- Gemini Client Initialization ---
def initialize_gemini_client() -> Optional[genai.Client]:
    """Initializes and returns the Gemini client."""
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("[ERROR] GEMINI_API_KEY not found.")
        return None
    try:
        client = genai.Client(api_key=api_key)
        print("[INFO] Gemini Client initialized successfully.")
        return client
    except Exception as e:
        print(f"[ERROR] Could not initialize genai client: {e}")
        return None

# --- File Paths ---
EMBEDDING_MODEL = 'models/text-embedding-004'
RETRY_DELAY_SECONDS = 3
INDEX_FILE = Path("data/processed/legal_index.faiss")
METADATA_FILE = Path("data/processed/metadata.json")

# ======== TEST FUNCTIONS ========

def load_kb(index_path: Path, metadata_path: Path):
    """Loads the FAISS index and metadata."""
    if not index_path.exists() or not metadata_path.exists():
        print(f"[ERROR] Missing KB files. Run embed_kb.py first.")
        return None, None
        
    try:
        # Load the FAISS index from disk
        index = faiss.read_index(str(index_path))
        
        # Load the metadata lookup file
        with open(metadata_path, 'r', encoding='utf-8') as f:
            metadata = json.load(f)
            
        print(f"[INFO] Knowledge Base loaded: {index.ntotal} vectors and {len(metadata)} metadata entries.")
        return index, metadata
        
    except Exception as e:
        print(f"[ERROR] Failed to load KB: {e}")
        return None, None

def embed_query(client: genai.Client, query_text: str) -> Optional[np.ndarray]:
    """Generates an embedding for a single query without using unsupported arguments."""
    
    query_payload = [query_text]  # API expects a list
    
    for attempt in range(3):
        try:
            # Modern variant
            response = client.models.embed_content(
                model=EMBEDDING_MODEL,
                contents=query_payload
            )
        except TypeError as e1:
            msg = str(e1)
            try:
                if "unexpected keyword argument 'contents'" in msg:
                    # Older variant
                    response = client.models.embed_content(
                        model=EMBEDDING_MODEL,
                        content=query_payload
                    )
                else:
                    raise e1
            except Exception as e2:
                print(f"[WARNING] Alternate call failed: {e2}")
                if attempt < 2:
                    delay = RETRY_DELAY_SECONDS * (2 ** attempt)
                    print(f"[INFO] Retrying in {delay}s...")
                    time.sleep(delay)
                    continue
                else:
                    print(f"[ERROR] Query embedding failed after 3 attempts. Aborting.")
                    return None
        except Exception as e:
            print(f"[WARNING] Query embedding failed on attempt {attempt + 1}: {e}")
            if attempt < 2:
                delay = RETRY_DELAY_SECONDS * (2 ** attempt)
                print(f"[INFO] Retrying in {delay}s...")
                time.sleep(delay)
                continue
            else:
                print(f"[ERROR] Query embedding failed after 3 attempts. Aborting.")
                return None

        # Normalize the response to a vector
        embedding_vector = None
        if isinstance(response, dict) and "embeddings" in response:
            embedding_vector = response["embeddings"][0]
        elif hasattr(response, "embeddings"):
            embedding_vector = response.embeddings[0]
            if hasattr(embedding_vector, "values"):
                embedding_vector = embedding_vector.values
        
        if embedding_vector is not None:
            return np.array(embedding_vector).astype("float32")
        else:
            print(f"[ERROR] Could not parse embedding from response: {response}")
            return None

    return None

def search_kb(index: faiss.Index, metadata: List[Dict], query_vector: np.ndarray, top_k: int = 3):
    """Searches the KB and prints the top k results."""
    
    # Add a batch dimension (FAISS expects a 2D array)
    query_vector = np.expand_dims(query_vector, axis=0)
    
    # D = distances (how far), I = indices (which vector ID)
    distances, indices = index.search(query_vector, top_k)
    
    print(f"\n--- Top {top_k} results ---")
    
    for i in range(top_k):
        index_id = indices[0][i]
        distance = distances[0][i]
        
        # Look up the metadata using the index ID
        match = metadata[index_id]
        
        print(f"\n🎯 Result {i+1} (Distance: {distance:.4f}):")
        print(f"   Article: {match['article_number']}")
        print(f"   Text: {match['text']}")
        print("--------------------")

# ======== MAIN EXECUTION ========

def main():
    # Your test query
    TEST_QUERY = "what happens if I refuse military service"

    # 1. Initialize Client
    gemini_client = initialize_gemini_client()
    if not gemini_client:
        return

    # 2. Load the Knowledge Base (Index + Metadata)
    index, metadata = load_kb(INDEX_FILE, METADATA_FILE)
    if not index or not metadata:
        return
        
    # 3. Embed the Test Query
    print(f"[INFO] Embedding test query: '{TEST_QUERY}'")
    query_vector = embed_query(gemini_client, TEST_QUERY)
    if query_vector is None:
        return
        
    # 4. Search and Print Results
    search_kb(index, metadata, query_vector, top_k=3)

if __name__ == "__main__":
    main()