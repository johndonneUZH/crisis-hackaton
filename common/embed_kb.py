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

# Attempt to load environment variables from .env
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
        # This is the modern way to initialize the client
        client = genai.Client(api_key=api_key)
        print("[INFO] Gemini Client initialized successfully.")
        return client
    except Exception as e:
        print(f"[ERROR] Could not initialize genai client: {e}")
        return None

# --- Model and File Paths ---
# Use the official model string
EMBEDDING_MODEL = 'models/text-embedding-004' 
BATCH_SIZE = 100 # Number of chunks to embed in a single API call
RETRY_DELAY_SECONDS = 3 # Start with 3 seconds for exponential backoff

# NOTE: Make sure this points to your FINAL, clean output file
INPUT_CHUNKS_FILE = Path("data/processed/chunks_sample.jsonl") # Or chunks.jsonl
OUTPUT_INDEX_FILE = Path("data/processed/legal_index.faiss")
OUTPUT_METADATA_FILE = Path("data/processed/metadata.json")

# ======== CORE FUNCTIONS ========

def load_chunks(file_path: Path) -> List[Dict[str, Any]]:
    """Loads the processed chunks from the JSONL file."""
    chunks = []
    if not file_path.exists():
        print(f"[ERROR] Chunks file not found at: {file_path}")
        return []
        
    with open(file_path, 'r', encoding='utf-8') as f:
        for line in f:
            chunks.append(json.loads(line))
    print(f"[INFO] Loaded {len(chunks)} chunks from {file_path}.")
    return chunks

def get_embeddings(client: genai.Client, texts: List[str]) -> Optional[List[List[float]]]:
    """Generate embeddings for a list of texts in batches, compatible with different google-genai versions."""
    all_embeddings = []

    for i in range(0, len(texts), BATCH_SIZE):
        batch_texts = texts[i:i + BATCH_SIZE]
        print(f"[INFO] Processing batch {i//BATCH_SIZE + 1}/{(len(texts)-1)//BATCH_SIZE + 1}...")

        for attempt in range(3):
            try:
                # Try most common version first (modern client)
                response = client.models.embed_content(
                    model=EMBEDDING_MODEL,
                    contents=batch_texts   # note: plural
                )
            except TypeError as e1:
                msg = str(e1)
                try:
                    if "unexpected keyword argument 'contents'" in msg:
                        # Try singular "content"
                        response = client.models.embed_content(
                            model=EMBEDDING_MODEL,
                            content=batch_texts
                        )
                    elif "unexpected keyword argument 'content'" in msg:
                        # Try using generic .embed() if .embed_content doesn't work
                        if hasattr(client.models, "embed"):
                            response = client.models.embed(
                                model=EMBEDDING_MODEL,
                                content=batch_texts
                            )
                        else:
                            raise e1
                    else:
                        raise e1
                except Exception as e2:
                    print(f"[WARNING] Alternate call also failed: {e2}")
                    if attempt < 2:
                        delay = RETRY_DELAY_SECONDS * (2 ** attempt)
                        print(f"[INFO] Retrying in {delay}s...")
                        time.sleep(delay)
                        continue
                    else:
                        print(f"[ERROR] Batch {i//BATCH_SIZE + 1} failed after 3 attempts. Aborting.")
                        return None

            except Exception as e:
                print(f"[WARNING] Batch {i//BATCH_SIZE + 1} failed: {e}")
                if attempt < 2:
                    delay = RETRY_DELAY_SECONDS * (2 ** attempt)
                    print(f"[INFO] Retrying in {delay}s...")
                    time.sleep(delay)
                    continue
                else:
                    print(f"[ERROR] Batch {i//BATCH_SIZE + 1} failed after 3 attempts. Aborting.")
                    return None

            # Normalize output regardless of client version
            if isinstance(response, dict):
                embeddings = [
                    e.get("values") if isinstance(e, dict) else e
                    for e in response.get("embeddings", [])
                ]
            elif hasattr(response, "embeddings"):
                embeddings = [
                    e.values if hasattr(e, "values") else e
                    for e in response.embeddings
                ]
            else:
                embeddings = response

            all_embeddings.extend(embeddings)
            time.sleep(1)
            break

    if len(all_embeddings) == len(texts):
        print(f"[INFO] Successfully generated {len(all_embeddings)} embeddings.")
        return all_embeddings
    else:
        print("[ERROR] Embedding count mismatch. Check API response format.")
        return None

def build_and_save_index(vectors: np.ndarray, output_path: Path):
    """Builds a FAISS index and saves it to disk."""
    if not vectors.size:
        print("[ERROR] No vectors to index.")
        return

    dimension = vectors.shape[1] # Get the vector dimension (e.g., 768)
    
    # Initialize a simple, flat L2 (Euclidean distance) index
    index = faiss.IndexFlatL2(dimension)
    
    # Add the vectors to the index
    index.add(vectors)
    
    # Save the index to disk
    faiss.write_index(index, str(output_path))
    print(f"[SUCCESS] FAISS index with {index.ntotal} vectors saved to {output_path}")

def save_metadata(chunks: List[Dict[str, Any]], output_path: Path):
    """Saves the metadata (text, article_number) for index-to-text lookup."""
    
    metadata_lookup = []
    for i, chunk in enumerate(chunks):
        metadata_lookup.append({
            "index_id": i, # This corresponds to the FAISS vector ID
            "id": chunk.get("id"),
            "article_number": chunk.get("article_number"),
            "text": chunk.get("text"),
            "title": chunk.get("title"),
            "jurisdiction": chunk.get("jurisdiction")
        })
        
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(metadata_lookup, f, indent=2, ensure_ascii=False)
    
    print(f"[SUCCESS] Metadata lookup file saved to {output_path}")

# ======== MAIN EXECUTION ========

def main():
    """Main function to run the embedding and indexing pipeline."""
    
    gemini_client = initialize_gemini_client()
    if not gemini_client:
        return
        
    # 1. Load Chunks
    chunks = load_chunks(INPUT_CHUNKS_FILE)
    if not chunks:
        return
        
    # 2. Get Texts
    texts_to_embed = [chunk['text'] for chunk in chunks]
    
    # 3. Generate Embeddings
    embeddings = get_embeddings(gemini_client, texts_to_embed)
    if not embeddings:
        return
        
    # 4. Convert to NumPy array
    vectors = np.array(embeddings).astype("float32")
    
    # 5. Build and Save FAISS Index
    build_and_save_index(vectors, OUTPUT_INDEX_FILE)
    
    # 6. Save Metadata Lookup File
    save_metadata(chunks, OUTPUT_METADATA_FILE)
    
    print("\n[INFO] Knowledge Base embedding and indexing complete!")

if __name__ == "__main__":
    main()