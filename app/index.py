from fastapi import FastAPI, File, UploadFile, HTTPException, BackgroundTasks
from fastapi.responses import JSONResponse
from supabase import create_client, Client as SupabaseClient
import os
import tempfile
import asyncio
from dotenv import load_dotenv
from app.pipeline import process_pdf_upload

load_dotenv()

# --- Supabase configuration ---
SUPABASE_URL = os.environ.get("SUPABASE_URL")
SUPABASE_KEY = os.environ.get("SUPABASE_SERVICE_KEY")
SUPABASE_BUCKET = os.environ.get("SUPABASE_BUCKET", "pdf-uploads")

# Initialize Supabase client
supabase: SupabaseClient = create_client(SUPABASE_URL, SUPABASE_KEY)

# --- FastAPI app ---
app = FastAPI()

@app.get("/")
def read_root():
    return {"message": "Hello from FastAPI on Vercel!"}

@app.post("/upload_pdf")
async def upload_pdf(file: UploadFile = File(...), background_tasks: BackgroundTasks = None):
    if file.content_type != "application/pdf":
        raise HTTPException(status_code=400, detail="Only PDF files are allowed.")

    # --- 1. Save to a temporary file (cross-platform) ---
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as tmp:
            temp_file_path = tmp.name
            tmp.write(await file.read())
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save temporary file: {e}")

    # --- 2. Upload to Supabase Storage ---
    try:
        result = supabase.storage.from_(SUPABASE_BUCKET).upload(file.filename, temp_file_path, {'upsert': 'true'})
        if not result:
            raise Exception("Supabase upload failed")
    except Exception as e:
        os.remove(temp_file_path)
        raise HTTPException(status_code=500, detail=f"Failed to upload to Supabase: {e}")

    # --- 3. Trigger pipeline in background ---
    try:
        event = {"bucket": SUPABASE_BUCKET, "file_path": file.filename}
        context = {}
        if background_tasks:
            background_tasks.add_task(run_pipeline, event, context)
        else:
            # fallback: run asynchronously without blocking
            asyncio.create_task(run_pipeline(event, context))
    except Exception as e:
        os.remove(temp_file_path)
        raise HTTPException(status_code=500, detail=f"Failed to trigger pipeline: {e}")

    # --- 4. Clean up temporary file ---
    os.remove(temp_file_path)

    return JSONResponse(content={"status": "success", "file": file.filename})


async def run_pipeline(event, context):
    """Wrapper for your existing pipeline function."""
    process_pdf_upload(event, context)
