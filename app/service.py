from azure.core.credentials import AzureKeyCredential
from azure.search.documents import SearchClient
from azure.search.documents.models import VectorizedQuery
from openai import AzureOpenAI
import os
from dotenv import load_dotenv
load_dotenv()
# -------------------------------
# Azure Search setup
# -------------------------------
index_name = "rag-1761829457025"

search_client = SearchClient(
    endpoint=os.getenv("SEARCH_ENDPOINT"),
    index_name=index_name,
    credential=AzureKeyCredential(os.getenv("SEARCH_API_KEY"))
)

# -------------------------------
# Azure OpenAI setup (for embeddings)
# -------------------------------
deployment_name = "text-embedding-3-large"

azure_openai_client = AzureOpenAI(
    api_version="2024-12-01-preview",
    azure_endpoint=os.getenv("OPENAI_ENDPOINT"),
    api_key=os.getenv("OPENAI_API_KEY")
)


# -------------------------------
# Step 1: Create embedding for query
# -------------------------------
query_text = "salary and employment conditions for posted employees"

response = azure_openai_client.embeddings.create(
    input=query_text,
    model=deployment_name
)

query_embedding = response.data[0].embedding  # embedding vector

# -------------------------------
# Step 2: Search Azure Search vector index
# -------------------------------
vector_query = VectorizedQuery(
    vector=query_embedding,
    k=5,
    fields="text_vector"
)

results = search_client.search(
    search_text=None,
    vector_queries=[vector_query]
)

# -------------------------------
# Step 3: Display results
# -------------------------------
for r in results:
    print(f"Title: {r['title']}")
    print(f"Chunk preview: {r['chunk'][:500]}...")
    print("---")
