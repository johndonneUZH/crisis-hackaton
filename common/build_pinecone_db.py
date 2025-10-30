import os
import time
import uuid
import json
import tempfile
from pathlib import Path
from typing import Dict, Any, List, Optional

import pymupdf4llm
import google.genai as genai
import pinecone
from dotenv import load_dotenv

# ==========================================================
# === CONFIGURATION ========================================
# ==========================================================

# Load environment variables from .env
try:
    repo_root = Path(__file__).resolve().parents[1]
    dotenv_path = repo_root / '.env'
    if dotenv_path.exists():
        load_dotenv(dotenv_path)
    else:
        load_dotenv()
except Exception:
    pass

DATA_DIR = repo_root / "data"
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
PINECONE_API_KEY = os.environ.get("PINECONE_API_KEY")
PINECONE_INDEX_NAME = os.environ.get("PINECONE_INDEX_NAME", "legal-kb")

TEXT_CHUNK_MODEL = "gemini-2.5-flash"
MAX_SEGMENT_SIZE = 1500
RETRY_DELAY_SECONDS = 3

# ==========================================================
# === INIT CLIENTS =========================================
# ==========================================================

try:
    gemini_client = genai.Client(api_key=GEMINI_API_KEY)
    pinecone_client = pinecone.Pinecone(api_key=PINECONE_API_KEY)
    pinecone_index = pinecone_client.Index(PINECONE_INDEX_NAME)
    print("[INFO] Gemini and Pinecone clients initialized.")
except Exception as e:
    print(f"[ERROR] Failed to initialize global clients: {e}")
    gemini_client = None
    pinecone_index = None

# ==========================================================
# === SCHEMAS ==============================================
# ==========================================================

MetadataSchema = genai.types.Schema(
    type=genai.types.Type.OBJECT,
    description="The core metadata required for a legal document.",
    properties={
        "title": {"type": genai.types.Type.STRING},
        "jurisdiction": {"type": genai.types.Type.STRING},
        "language": {"type": genai.types.Type.STRING}
    },
    required=["title", "jurisdiction", "language"]
)

ChunkSchema = genai.types.Schema(
    type=genai.types.Type.OBJECT,
    description="A single, complete, and coherent legal segment.",
    properties={
        "title": {"type": genai.types.Type.STRING},
        "jurisdiction": {"type": genai.types.Type.STRING},
        "language": {"type": genai.types.Type.STRING},
        "article_number": {"type": genai.types.Type.STRING},
        "text": {"type": genai.types.Type.STRING}
    },
    required=["title", "jurisdiction", "language", "article_number", "text"]
)

ResponseSchema = genai.types.Schema(
    type=genai.types.Type.OBJECT,
    properties={
        "chunks": {"type": genai.types.Type.ARRAY, "items": ChunkSchema},
        "remainder": {"type": genai.types.Type.STRING, "description": "Any incomplete text cut off mid-sentence or mid-article."}
    },
    required=["chunks", "remainder"]
)

# ==========================================================
# === PROMPTS ==============================================
# ==========================================================

def get_metadata_prompt(text_sample: str) -> str:
    return f"""
    You are a Legal Document Analyst. Your task is to accurately identify the core metadata from the provided sample text, which comes from the beginning of a legal document.
    ### CRITERIA:
    1.  **Jurisdiction:** Determine if the text is from **SWISS** law or **INTERNATIONAL** law. **DO NOT return any other jurisdiction.**
    2.  **Title:** Extract the official, full name of the law.
    3.  **Language:** Identify the main language(s) (e.g., 'ENG', 'FR', 'DE').
    ### TEXT SAMPLE:
    {text_sample}
    ### INSTRUCTION
    Return a strict JSON object that conforms to the metadata schema.
    """

def get_system_prompt() -> str:
    return (
        "You are a specialized Legal Text Segmentation Assistant. Your ONLY function is to split the raw Markdown text "
        "into the *smallest possible* semantically complete and coherent legal sections. "
        "Split by numbered paragraphs and update 'article_number'. "
        "IGNORE legal footnotes, cross-references, or registry numbers. "
        "Return the remainder if text is incomplete."
    )

def get_user_prompt(text_segment: str, metadata: Dict[str, str]) -> str:
    return f"""
    You are a legal text segmenter.

    Task:
    Split the following legal text into coherent articles or sections.

    Each response MUST be a JSON object with exactly these keys:
    - "chunks": a list of chunk objects
    - "remainder": a string representing any leftover or incomplete text at the end that should be reprocessed in the next segment.

    Each element in "chunks" MUST have:
      - "article_number" (integer, starting from 1)
      - "title" (string, short descriptive title)
      - "jurisdiction" (string; use "{metadata.get('jurisdiction', 'UNKNOWN')}")
      - "language" (string; use "{metadata.get('language', 'UNKNOWN')}")
      - "text" (string; full text of this chunk)

    Example valid output:
    {{
      "chunks": [
        {{
          "article_number": 1,
          "title": "Definition of Terms",
          "jurisdiction": "SWISS",
          "language": "DE",
          "text": "Der Vertrag gilt ab dem..."
        }},
        {{
          "article_number": 2,
          "title": "Geltungsbereich",
          "jurisdiction": "SWISS",
          "language": "DE",
          "text": "Dieses Gesetz betrifft..."
        }}
      ],
      "remainder": ""
    }}

    Now segment this text:

    ---
    {text_segment}
    ---
    """

# ==========================================================
# === PDF EXTRACTION =======================================
# ==========================================================

def extract_full_text_from_pdf(pdf_path: Path) -> str:
    try:
        text = pymupdf4llm.to_markdown(str(pdf_path))
        print(f"[INFO] Extracted text from {pdf_path.name}")
        return text
    except Exception as e:
        print(f"[ERROR] Could not extract from {pdf_path}: {e}")
        return ""


# ==========================================================
# === GEMINI HELPERS =======================================
# ==========================================================

def extract_metadata(client: genai.Client, text_sample: str) -> Optional[Dict[str, str]]:
    print("[INFO] Calling Gemini to extract metadata...")
    for attempt in range(3):
        try:
            response = client.models.generate_content(
                model=TEXT_CHUNK_MODEL,
                contents=[get_metadata_prompt(text_sample)],
                config=genai.types.GenerateContentConfig(
                    response_mime_type="application/json",
                    response_schema=MetadataSchema,
                    temperature=0.0
                )
            )
            metadata = json.loads(response.text)
            if all(k in metadata for k in ["title", "jurisdiction", "language"]):
                print(f"[SUCCESS] Metadata extracted: {metadata}")
                return metadata
            print("[ERROR] Metadata structure invalid.")
            return None
        except Exception as e:
            print(f"[WARNING] Attempt {attempt+1} failed: {e}")
            time.sleep(RETRY_DELAY_SECONDS * (2 ** attempt))
    return None

# ==========================================================
# === SEGMENTER CLASS ======================================
# ==========================================================

class LegalTextSegmenter:
    def __init__(self, client: genai.Client, initial_metadata: Dict[str, str]):
        self.client = client
        self.initial_metadata = initial_metadata
        self.all_final_chunks: List[Dict[str, Any]] = []

    def _reinit_client(self):
        """Attempt to reinitialize Gemini client if something goes wrong."""
        global gemini_client
        try:
            print("[INFO] Reinitializing Gemini client...")
            gemini_client = genai.Client(api_key=GEMINI_API_KEY)
            self.client = gemini_client
            print("[INFO] Gemini client reinitialized successfully.")
        except Exception as e:
            print(f"[ERROR] Failed to reinitialize Gemini client: {e}")

    def _call_gemini_segment(self, text_segment: str) -> Dict[str, Any]:
        user_prompt = get_user_prompt(text_segment, self.initial_metadata)
        for attempt in range(3):
            try:
                response = self.client.models.generate_content(
                    model=TEXT_CHUNK_MODEL,
                    contents=[get_system_prompt(), user_prompt],
                    config=genai.types.GenerateContentConfig(
                        response_mime_type="application/json",
                        temperature=0.0
                    )
                )
                return json.loads(response.text)
            except Exception as e:
                print(f"[WARNING] Segment attempt {attempt+1} failed: {e}")
                time.sleep(RETRY_DELAY_SECONDS * (2 ** attempt))

        # If all attempts failed, try reinitializing Gemini
        print("[ERROR] All attempts failed for this segment. Trying to reinitialize Gemini and continue...")
        self._reinit_client()
        try:
            response = self.client.models.generate_content(
                model=TEXT_CHUNK_MODEL,
                contents=[get_system_prompt(), user_prompt],
                config=genai.types.GenerateContentConfig(
                    response_mime_type="application/json",
                    temperature=0.0
                )
            )
            return json.loads(response.text)
        except Exception as e:
            print(f"[ERROR] Gemini failed again after reinitialization: {e}")
            # Return empty so we can safely skip this segment and continue
            return {"chunks": [], "remainder": text_segment}

    def process_document(self, full_raw_text: str):
        cursor, segment_index, current_remainder = 0, 0, ""
        total_length = len(full_raw_text)

        while cursor < total_length:
            segment_index += 1
            segment_start = cursor + len(current_remainder)
            new_segment = full_raw_text[segment_start: segment_start + MAX_SEGMENT_SIZE]
            input_text = current_remainder + new_segment
            if not input_text:
                break

            print(f"[INFO] Processing segment {segment_index} ({len(input_text)} chars)...")
            result = self._call_gemini_segment(input_text)

            consumed_length = len(input_text) - len(result.get("remainder", ""))
            cursor += consumed_length
            current_remainder = result.get("remainder", "")

            for chunk in result.get("chunks", []):
                chunk["id"] = str(uuid.uuid4())
                self.all_final_chunks.append(chunk)

        # Final leftover
        if current_remainder:
            print("[INFO] Processing final leftover text...")
            final_result = self._call_gemini_segment(current_remainder)
            for chunk in final_result.get("chunks", []):
                chunk["id"] = str(uuid.uuid4())
                self.all_final_chunks.append(chunk)

# ==========================================================
# === PINECONE UPLOAD ======================================
# ==========================================================

def upsert_to_pinecone(chunks: List[Dict[str, Any]], file_path: str):
    if not pinecone_index:
        print("[ERROR] Pinecone index not initialized.")
        return

    records = []
    for chunk in chunks:
        record = {
            "_id": chunk["id"],                 # unique ID
            "text": chunk["text"],        # Pinecone will embed this automatically
            "article_number": chunk.get("article_number"),
            "title": chunk.get("title"),
            "jurisdiction": chunk.get("jurisdiction"),
            "language": chunk.get("language"),
            "pdf_source_path": file_path
        }
        records.append(record)

    try:
        # Use a namespace if desired; "__default__" is the default
        pinecone_index.upsert_records("__default__", records)
        print(f"[INFO] Upserted {len(records)} chunks into Pinecone.")
    except Exception as e:
        print(f"[ERROR] Failed to upsert records: {e}")

# ==========================================================
# === MAIN LOOP ============================================
# ==========================================================

def process_folder(folder_path: Path):
    pdfs = list(folder_path.glob("*.pdf"))
    print(f"[INFO] Found {len(pdfs)} PDFs in {folder_path}")
    for pdf in pdfs:
        print(f"\n=== Processing {pdf.name} ===")
        full_text = extract_full_text_from_pdf(pdf)
        if not full_text:
            continue

        metadata = extract_metadata(gemini_client, full_text[:4000])
        if not metadata:
            print(f"[ERROR] Metadata extraction failed for {pdf}")
            continue

        segmenter = LegalTextSegmenter(gemini_client, metadata)
        segmenter.process_document(full_text)
        upsert_to_pinecone(segmenter.all_final_chunks, str(pdf))


def main():
    for folder_name in ["swiss", "international"]:
        folder = DATA_DIR / folder_name
        if folder.exists():
            process_folder(folder)
        else:
            print(f"[WARN] Folder not found: {folder}")

    print("\n✅ Pinecone database build complete.")


if __name__ == "__main__":
    main()