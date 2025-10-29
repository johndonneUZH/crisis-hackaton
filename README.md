# 🧭 HelpOS

## 👥 Team Members
- Miki Mizuki
- Mattia Leonetti
- Matteo Iulian Adam
---

## 🏆 Challenge
**Hackathon on Crisis Application for GaaP — UNHCR: Virtual Legal Assistance Rights Mapping**

**Challenge Question:**  
How might we determine the legal status of a refugee for pro bono lawyers, so that their advice can be given more quickly and accurately?

**Context:**  
Refugees often face complex and multilingual legal systems when arriving in a host country. Pro bono lawyers must currently sift through lengthy documents in various languages to understand which rights apply, leading to time delays and inconsistencies in the advice provided.  

Our goal is to design a tool that structures, accesses, and presents legal information clearly and efficiently—empowering lawyers to provide faster, more reliable, and more accurate legal assistance.

---

## 💡 Solution Overview
We propose a **guided pipeline application** that assists pro bono lawyers in handling refugee cases efficiently.  
It provides a structured workflow, automatic legal mapping, and document generation to accelerate case preparation and improve legal clarity.

### 🔄 Pipeline Structure

1. **Open a New Case**  
   - Initialize a workspace for the refugee/family case.  
   - Input country of asylum, previous residence, and upload case-related files.

2. **Define Family Members**  
   - Add details such as name, age, nationality, relationship, and relevant conditions (e.g., medical, educational, employment).  
   - Optional: auto-extract information from case descriptions using NLP.

3. **Identify Applicable Legal Frameworks**  
   - Automatically determine relevant laws (Swiss, EU, and international).  
   - Use multilingual semantic search and summarization to highlight key rights and obligations.

4. **Find Blockers and Required Procedures**  
   - Generate a checklist of procedures, eligibility criteria, and required documents.  
   - Identify potential blockers (e.g., Dublin Regulation, missing documents).

5. **Auto-Generate Legal Templates**  
   - Pre-fill legal forms or letters with collected data.  
   - Lawyers can edit and add missing details directly in the browser.

6. **Case Summary and Output**  
   - Display a summary of applicable rights, documents to prepare, and responsible authorities.  
   - Export a concise report in PDF or DOCX format.

---

## 🧠 Core Features
- 🗂️ Structured workflow for managing complex asylum cases  
- 🌍 Multilingual support with automated translation and summarization  
- ⚖️ Legal text mapping via semantic search  
- 🧾 Template-based document generation  
- 📋 Summary table with actionable next steps and deadlines  

---

## 🧰 Tech Stack

| Layer | Technology |
|-------|-------------|
| **Frontend** | React (TypeScript), TailwindCSS |
| **Backend** | Python (FastAPI / Flask) |
| **Database** | SQLite (for simplicity) |
| **Search Engine** | FAISS or ElasticSearch (for semantic legal retrieval) |
| **NLP/AI Models** | HuggingFace Transformers (multilingual summarization + embeddings) |
| **Document Generation** | Jinja2 + python-docx / pdfkit |
| **Translation** | MarianMT / DeepL API |
| **Deployment** | Vercel (frontend), Google Cloud / Render (backend) |

---

## 📊 Example Use Case
**Scenario:**  
A refugee family arrives in Switzerland:  
- Father previously worked in an EU country  
- Mother requires urgent medical treatment  
- Child needs access to school  

**Our tool:**  
1. Collects relevant data about the family  
2. Identifies applicable rights (housing, healthcare, education)  
3. Flags potential legal blockers  
4. Suggests next steps and required documentation  
5. Produces a ready-to-review legal draft for the lawyer  

---

## 🧩 Future Extensions
- Confidence scoring for legal clause relevance  
- “Explain this clause” AI simplification button  
- Refugee-facing simplified summaries (multi-language)  
- Integration with UNHCR case systems  

---

## 🖼️ Screenshots & Demo
*(Add screenshots or link to demo video once available)*

---

## 🗣️ Pitch Materials
All pitch materials are located in the [`/pitch`](./pitch) folder.  
Include your presentation slides and demo video before the final submission.

---

## 📜 License
This project is open source and licensed under the [MIT License](./LICENSE).

---

**Developed for the Hackathon on Crisis Applications (Oct 30 – Nov 1, 2025) at the University of Zurich’s Digital Society Initiative, in collaboration with UNHCR.**
