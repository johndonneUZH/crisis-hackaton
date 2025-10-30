import google.genai as genai
import pinecone
import json
import os, tempfile
import time
import pymupdf4llm
import uuid
from dotenv import load_dotenv
from pathlib import Path
from typing import List, Dict, Any, Optional
from supabase import create_client, Client as SupabaseClient

# ======== CONFIGURATION ========
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

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
PINECONE_API_KEY = os.environ.get("PINECONE_API_KEY")
PINECONE_INDEX_NAME = os.environ.get("PINECONE_INDEX_NAME", "legal-kb")

# ======== SUPABASE CONFIG ========
SUPABASE_URL = os.environ.get("SUPABASE_URL")
SUPABASE_KEY = os.environ.get("SUPABASE_SERVICE_KEY")
SUPABASE_BUCKET = os.environ.get("SUPABASE_BUCKET", "pdf-uploads")

try:
    supabase: SupabaseClient = create_client(SUPABASE_URL, SUPABASE_KEY)
    print("[INFO] Supabase client initialized.")
except Exception as e:
    print(f"[ERROR] Failed to initialize Supabase client: {e}")
    supabase = None

# ======== MODEL CONFIG ========
TEXT_CHUNK_MODEL = 'gemini-2.5-flash'
MAX_SEGMENT_SIZE = 2500
RETRY_DELAY_SECONDS = 3
UPSERT_BATCH_SIZE = 100

# ======== GEMINI SCHEMAS ========
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

# ======== INITIALIZE CLIENTS ========
try:
    gemini_client = genai.Client(api_key=GEMINI_API_KEY)
    pinecone_client = pinecone.Pinecone(api_key=PINECONE_API_KEY)
    pinecone_index = pinecone_client.Index(PINECONE_INDEX_NAME)
    print("[INFO] Gemini and Pinecone clients initialized.")
except Exception as e:
    print(f"[ERROR] Failed to initialize global clients: {e}")
    gemini_client = None
    pinecone_index = None

# ======== PDF EXTRACTION ========
def extract_full_text_from_pdf(pdf_path: str) -> str:
    try:
        full_text = pymupdf4llm.to_markdown(pdf_path)
        print(f"[INFO] Extracted layout-aware Markdown from PDF.")
        return full_text
    except Exception as e:
        print(f"[ERROR] Failed to process PDF: {e}")
        return ""

# ======== PROMPTS ========
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

def get_user_prompt(text_segment: str, initial_metadata: Dict[str, str]) -> str:
    return f"""
    ### DOCUMENT METADATA HINT
    TITLE: {initial_metadata.get('title')}
    JURISDICTION: {initial_metadata.get('jurisdiction')}
    LANGUAGE: {initial_metadata.get('language')}
    ### RAW LEGAL TEXT SEGMENT
    {text_segment}
    ### INSTRUCTION
    Segment the RAW LEGAL TEXT above into complete, structured chunks. Return only the JSON object.
    """

# ======== METADATA EXTRACTION ========
def extract_metadata_via_gemini_sync(client: genai.Client, text_sample: str) -> Optional[Dict[str, str]]:
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

# ======== SEGMENTER ========
class LegalTextSegmenter:
    def __init__(self, client: genai.Client, initial_metadata: Dict[str, str]):
        self.client = client
        self.initial_metadata = initial_metadata
        self.all_final_chunks = []

    def _call_gemini_segment(self, text_segment: str) -> Dict[str, Any]:
        user_prompt = get_user_prompt(text_segment, self.initial_metadata)
        for attempt in range(3):
            try:
                response = self.client.models.generate_content(
                    model=TEXT_CHUNK_MODEL,
                    contents=[get_system_prompt(), user_prompt],
                    config=genai.types.GenerateContentConfig(
                        response_mime_type="application/json",
                        response_schema=ResponseSchema,
                        temperature=0.0
                    )
                )
                return json.loads(response.text)
            except Exception as e:
                print(f"[WARNING] Segment attempt {attempt+1} failed: {e}")
                time.sleep(RETRY_DELAY_SECONDS * (2 ** attempt))
        return {"chunks": [], "remainder": None}

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

        if current_remainder:
            final_result = self._call_gemini_segment(current_remainder)
            for chunk in final_result.get("chunks", []):
                chunk["id"] = str(uuid.uuid4())
                self.all_final_chunks.append(chunk)

# ======== PINECONE UPSERT ========
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
            "pdf_source_path": file_path
        }
        records.append(record)

    try:
        # Use a namespace if desired; "__default__" is the default
        pinecone_index.upsert_records("__default__", records)
        print(f"[INFO] Upserted {len(records)} chunks into Pinecone.")
    except Exception as e:
        print(f"[ERROR] Failed to upsert records: {e}")


# ======== CLOUD FUNCTION HANDLER ========
def process_pdf_upload(event, context):
    """
    Triggered by a file upload to Supabase Storage.
    'event' must contain 'bucket' and 'file_path'.
    """
    if not all([gemini_client, pinecone_index, supabase]):
        print("[ERROR] Clients not initialized.")
        return

    bucket_name = event['bucket']
    file_path = event['file_path']

    print(f"[INFO] New file trigger: {bucket_name}/{file_path}")

    # --- Download from Supabase into a temporary file ---
    try:
        response = supabase.storage.from_(bucket_name).download(file_path)
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as tmp_file:
            local_pdf_path = tmp_file.name
            tmp_file.write(response)
        print(f"[INFO] Downloaded file to temporary path: {local_pdf_path}")
    except Exception as e:
        print(f"[ERROR] Failed to download file from Supabase: {e}")
        return

    try:
        # --- Extract full text ---
        full_text = extract_full_text_from_pdf(local_pdf_path)
        if not full_text:
            print("[ERROR] Failed to extract text.")
            return

        # --- Extract metadata ---
        metadata_sample_text = full_text[:4000]
        initial_metadata = extract_metadata_via_gemini_sync(gemini_client, metadata_sample_text)
        if not initial_metadata:
            print("[ERROR] Failed to extract metadata.")
            return

        # --- Segment document ---
        segmenter = LegalTextSegmenter(gemini_client, initial_metadata)
        segmenter.process_document(full_text)
        final_chunks = segmenter.all_final_chunks
        if not final_chunks:
            print("[ERROR] No chunks generated.")
            return

        # --- Upsert to Pinecone ---
        supabase_file_url = f"{bucket_name}/{file_path}"
        upsert_to_pinecone(final_chunks, supabase_file_url)

        print(f"[SUCCESS] Pipeline complete for {file_path}.")
    finally:
        # --- Cleanup temporary file ---
        if os.path.exists(local_pdf_path):
            os.remove(local_pdf_path)
            print(f"[INFO] Removed temporary file: {local_pdf_path}")