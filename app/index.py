from fastapi import FastAPI, File, UploadFile, HTTPException, BackgroundTasks
from fastapi.responses import JSONResponse
from supabase import create_client, Client as SupabaseClient
import os
import tempfile
import asyncio
from dotenv import load_dotenv

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