import google.genai as genai
from google.genai.types import Schema, Type
import json, pymupdf4llm
import fitz
import os
import uuid
import time # <-- Used for synchronous sleep on retry
from typing import List, Dict, Any, Optional
from pathlib import Path
from dotenv import load_dotenv 

# Configuration and Initialization
try:
    repo_root = Path(__file__).resolve().parents[1]
    dotenv_path = repo_root / '.env'
    if dotenv_path.exists():
        load_dotenv(dotenv_path)
    else:
        load_dotenv()
except Exception:
    pass

# ======== CONFIGURATION ========
GENERATION_MODEL = 'gemini-2.5-flash'
TEXT_CHUNK_MODEL = 'gemini-2.5-flash' 
MAX_SEGMENT_SIZE = 2500 # Max characters/tokens to feed Gemini per call
RETRY_DELAY_SECONDS = 2 # Starting delay for exponential backoff

def initialize_gemini_client() -> Optional[genai.Client]:
    """Initializes and returns the Gemini client using the GEMINI_API_KEY environment variable."""
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("[ERROR] GEMINI_API_KEY not found. Please set the environment variable.")
        return None
    try:
        client = genai.Client(api_key=api_key)
        print("[INFO] Gemini Client initialized successfully.")
        return client
    except Exception as e:
        print(f"[ERROR] Could not initialize genai client: {e}")
        return None

# ======== PDF TEXT EXTRACTION (UNCHANGED) ========
def extract_full_text_from_pdf(pdf_path: Path) -> str:
    """Extracts the entire text content as layout-aware Markdown."""
    full_text = ""
    if not pdf_path.exists():
        print(f"[ERROR] PDF file not found at path: {pdf_path}")
        return ""
        
    try:
        # This one line does the magic:
        full_text = pymupdf4llm.to_markdown(str(pdf_path))
        
        print(f"[INFO] Extracted layout-aware Markdown from the PDF: {pdf_path.name}.")
        return full_text
    
    except Exception as e:
        print(f"[ERROR] Failed to process PDF {pdf_path.name}: {e}")
        return ""

# ========= OUTPUT SCHEMAS (UNCHANGED) =========
MetadataSchema = Schema(
    type=Type.OBJECT, description="The core metadata required for a legal document.",
    properties={"title": {"type": Type.STRING}, "jurisdiction": {"type": Type.STRING}, "language": {"type": Type.STRING}},
    required=["title", "jurisdiction", "language"]
)

ChunkSchema = Schema(
    type=Type.OBJECT, description="A single, complete, and coherent legal segment.",
    properties={"title": {"type": Type.STRING}, "jurisdiction": {"type": Type.STRING}, "language": {"type": Type.STRING}, 
                "article_number": {"type": Type.STRING}, "text": {"type": Type.STRING}},
    required=["title", "jurisdiction", "language", "article_number", "text"]
)

ResponseSchema = Schema(
    type=Type.OBJECT,
    properties={"chunks": {"type": Type.ARRAY, "items": ChunkSchema},
                "remainder": {"type": Type.STRING, "description": "Any incomplete text cut off mid-sentence or mid-article."}},
    required=["chunks", "remainder"]
)

# ========= PROMPT TEMPLATES (FIXED FOR JURISDICTION CONSTRAINT) =========
def get_metadata_prompt(text_sample: str) -> str:
    return f"""
    You are a Legal Document Analyst. Your task is to accurately identify the core metadata from the provided sample text, which comes from the beginning of a legal document.

    ### CRITERIA:
    1.  **Jurisdiction:** Determine if the text is from **SWISS** law (e.g., Federal Act, Ordinance) or **INTERNATIONAL** law (e.g., UN Convention, ECHR, Treaty). **DO NOT return any other jurisdiction.**
    2.  **Title:** Extract the official, full name of the law.
    3.  **Language:** Identify the main language(s) of the text and return as 'ENG', 'FR', 'DE', 'IT', or combinations like 'ENG-FR'.

    ### TEXT SAMPLE (First few pages):
    {text_sample}

    ### INSTRUCTION
    Analyze the TEXT SAMPLE and return the analysis as a strict JSON object that conforms to the schema.
    """

def get_system_prompt() -> str:
    return (
        "You are a specialized Legal Text Segmentation Assistant. Your ONLY function is to split the raw Markdown text "
        "into the *smallest possible* semantically complete and coherent legal sections (e.g., individual paragraphs, clauses, or sub-articles). "
        "The text is provided in Markdown format, so headers (e.g., '#', '##') and lists (e.g., '1.', 'a.') are reliable indicators of structure. "
        
        "CRITICALLY: You MUST split by the numbered paragraphs (e.g., '1', '2', '3bis') found within an article's text. "
        "Each numbered paragraph should become its own separate chunk. "
        
        "When you split by a paragraph, update the 'article_number' field to reflect this (e.g., 'Art. 3 para. 1', 'Art. 3 para. 2'). "
        
        "IGNORE and DO NOT include any legal footnotes, cross-references, amendment notices, or registry numbers (e.g., 'SR 0.142.30', 'Amended by...'). "
        "The returned 'text' must contain only the normative legal language. "
        
        "If the text is cut off mid-paragraph, put only the incomplete text into the 'remainder' field. "
        "NEVER comment, explain, or hallucinate text. Strictly adhere to the provided JSON schema."
    )

def get_user_prompt(text_segment: str, initial_metadata: Dict[str, str]) -> str:
    return f"""
    ### DOCUMENT METADATA HINT
    Use these values for all chunks derived from this segment unless explicitly contradicted by the text:
    TITLE: {initial_metadata.get('title')}
    JURISDICTION: {initial_metadata.get('jurisdiction')}
    LANGUAGE: {initial_metadata.get('language')}

    ### RAW LEGAL TEXT SEGMENT
    {text_segment}

    ### INSTRUCTION
    Segment the RAW LEGAL TEXT above into complete, structured chunks. 
    Any incomplete text at the end must be returned in the 'remainder' field. 
    Return only the JSON object.
    """

# ========= SYNCHRONOUS METADATA EXTRACTION FUNCTION (MODIFIED) =========

def extract_metadata_via_gemini(client: genai.Client, text_sample: str) -> Optional[Dict[str, str]]:
    """Calls Gemini synchronously to extract structured metadata from a text sample."""
    print("[INFO] Calling Gemini synchronously to extract structured metadata...")
    
    # Simple retry loop (essential for network stability)
    for attempt in range(3):
        try:
            response = client.models.generate_content(
                model=TEXT_CHUNK_MODEL,
                contents=[get_metadata_prompt(text_sample)],
                config=genai.types.GenerateContentConfig(response_mime_type="application/json", response_schema=MetadataSchema, temperature=0.0)
            )
            metadata = json.loads(response.text)
            print(f"[SUCCESS] Extracted Metadata: {metadata}")
            
            if all(key in metadata for key in ["title", "jurisdiction", "language"]):
                return metadata
            else:
                 print("[ERROR] Metadata structure invalid.")
                 return None
                 
        except Exception as e:
            print(f"[WARNING] Call failed on attempt {attempt + 1}: {e}. Retrying in {RETRY_DELAY_SECONDS}s...")
            if attempt < 2:
                time.sleep(RETRY_DELAY_SECONDS * (2 ** attempt)) # Exponential backoff
            else:
                print("[ERROR] Final attempt failed. Abandoning metadata extraction.")
                return None
    return None

# ========= SYNCHRONOUS SEMANTIC CHUNKER CLASS (FIXED WINDOW LOGIC) =========

class LegalTextSegmenter:
    def __init__(self, client: genai.Client, initial_metadata: Dict[str, str]):
        self.client = client
        self.initial_metadata = initial_metadata
        self.all_final_chunks = []
        
    def _call_gemini_segment(self, text_segment: str) -> Dict[str, Any]: # <-- SYNCHRONOUS
        """Calls Gemini synchronously with retry logic."""
        user_prompt = get_user_prompt(text_segment, self.initial_metadata)
        
        # Simple retry loop (essential for timeout errors)
        for attempt in range(3):
            try:
                response = self.client.models.generate_content(
                    model=TEXT_CHUNK_MODEL,
                    contents=[get_system_prompt(), user_prompt],
                    config=genai.types.GenerateContentConfig(response_mime_type="application/json", response_schema=ResponseSchema, temperature=0.0)
                )
                return json.loads(response.text)
            except Exception as e:
                print(f"[WARNING] Call failed on attempt {attempt + 1}: {e}. Retrying in {RETRY_DELAY_SECONDS}s...")
                if attempt < 2:
                    time.sleep(RETRY_DELAY_SECONDS * (2 ** attempt)) # Exponential backoff
                else:
                    print("[ERROR] Final attempt failed. Abandoning segment.")
                    return {"chunks": [], "remainder": None} # <-- Crucial: Return None on final failure
        return {"chunks": [], "remainder": None}


    def process_document(self, full_raw_text: str, max_segments: Optional[int] = None): # <-- Added max_segments
        """Processes the entire document with robust cursor and remainder chaining."""
        
        cursor = 0
        current_remainder = ""
        total_length = len(full_raw_text)
        segment_index = 0
        
        while cursor < total_length:
            segment_index += 1
            
            # 🛑 STOP HERE: Check if max_segments limit is reached
            if max_segments is not None and segment_index > max_segments:
                print(f"\n[INFO] HALTING PROCESS: Reached maximum segment limit of {max_segments}.")
                break
            
            # --- 1. CONSTRUCT INPUT ---
            segment_start = cursor + len(current_remainder) 
            new_segment = full_raw_text[segment_start : segment_start + MAX_SEGMENT_SIZE]
            input_text = current_remainder + new_segment
            
            if not input_text:
                break
            
            print(f"[INFO] Processing segment {segment_index} (Input size: {len(input_text)} chars)...")
            
            # --- 2. CALL GEMINI ---
            result = self._call_gemini_segment(input_text) 
            
            # --- 3. REMAINDER AND CURSOR MANAGEMENT (THE FIX) ---
            
            if result.get("remainder") is not None:
                # SUCCESS PATH: The model returned a result.
                
                consumed_length = len(input_text) - len(result.get("remainder", ""))
                cursor += consumed_length # Move cursor forward by consumed length
                current_remainder = result.get("remainder", "")
            else:
                # FAILURE PATH: The LLM failed all retries (timeout/server error).
                # To ensure progress, we skip the segment that caused the failure.
                print(f"[ERROR] Segment {segment_index} failed all retries. Forcing cursor forward by {len(new_segment)} to skip failed content.")
                cursor += len(new_segment)
                current_remainder = "" # Reset remainder entirely
            
            # --- 4. COLLECT CHUNKS ---
            for chunk in result.get("chunks", []):
                chunk["id"] = str(uuid.uuid4())
                self.all_final_chunks.append(chunk)

            print(f"-> Chunks collected: {len(result.get('chunks', []))}. Remainder size: {len(current_remainder)} chars.")

        # FINAL CLEANUP: Process any final remainder if the loop finished cleanly
        if current_remainder:
             print("\n[INFO] Final cleanup pass for remaining text...")
             final_result = self._call_gemini_segment(current_remainder)
             
             for chunk in final_result.get("chunks", []):
                 chunk["id"] = str(uuid.uuid4())
                 self.all_final_chunks.append(chunk)
             
             if final_result.get("remainder"):
                 print(f"[WARNING] Unprocessed remainder of {len(final_result['remainder'])} characters remains after cleanup.")

# Main Execution Orchestration (SYNCHRONOUS ENTRY POINT)

def main(): 
    """Main function to orchestrate PDF extraction, metadata gathering, chunking, and saving."""
    
    # 1. Initialize Client
    gemini_client = initialize_gemini_client()
    if not gemini_client:
        return 

    # 2. Define PDF Path
    PDF_FILE_PATH = Path(r"C:\Users\adamm\Desktop\crisis-hackaton\data\swiss\asylum_act_1998.pdf")
    
    # --- 2A: EXTRACT FULL TEXT ONCE (Synchronous) ---
    print("\n[INFO] Starting full document text extraction...")
    full_raw_text = extract_full_text_from_pdf(PDF_FILE_PATH) 

    if not full_raw_text:
        print("Aborting: Full text extraction failed or file was empty.")
        return

    # --- 2B: Sample Text for Metadata ---
    page_break_separator = "\n--[PAGE_BREAK]--\n"
    sample_size_limit = 5 
    pages = full_raw_text.split(page_break_separator)
    metadata_sample_text = page_break_separator.join(pages[:sample_size_limit])
    print(f"[INFO] Sampling text from the first {min(sample_size_limit, len(pages))} pages for metadata.")

    # --- 2C: Extract Metadata using Gemini (SYNCHRONOUS) ---
    initial_metadata = extract_metadata_via_gemini(gemini_client, metadata_sample_text)
    
    if not initial_metadata:
        print("[ERROR] Failed to get structured metadata. Aborting document processing.")
        return

    # 3. Run the Segmenter (SYNCHRONOUS)
    segmenter = LegalTextSegmenter(gemini_client, initial_metadata)

    # 🛑 HALT LOGIC: RUN ONLY THE FIRST 3 SEGMENTS
    SEGMENTS_TO_RUN = 3
    segmenter.process_document(full_raw_text, max_segments=SEGMENTS_TO_RUN) 

    # 4. Save Final Structured Chunks
    final_chunks = segmenter.all_final_chunks
    
    CHUNKS_JSONL_PATH = Path("data/processed/chunks_sample.jsonl") # Saving to a new sample file
    CHUNKS_JSONL_PATH.parent.mkdir(exist_ok=True)
    
    # Writing to a new sample file for inspection
    with open(CHUNKS_JSONL_PATH, "w", encoding='utf-8') as f:
        for chunk in final_chunks:
            if isinstance(chunk, dict):
                f.write(json.dumps(chunk, ensure_ascii=False) + "\n")
            
    print(f"\nSUCCESS: Saved a sample of {len(final_chunks)} semantically coherent chunks to {CHUNKS_JSONL_PATH}")
    
    if final_chunks:
        print("\nExample Chunk (for inspection):")
        print(json.dumps(final_chunks[0], indent=2, ensure_ascii=False))
        
    print("\n[ACTION REQUIRED]: Check the content of 'data/processed/chunks_sample.jsonl' for segmentation quality.")


# ========= ENTRY POINT =========
if __name__ == "__main__":
    main()