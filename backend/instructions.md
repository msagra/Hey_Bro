# Hey Bro - FastAPI Backend Brain

## Setup
1. Ensure Python 3.10+ is installed.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Set your API Keys:
   - Copy `backend/.env.example` to `backend/.env` and fill in your real keys:
     ```bash
     Copy-Item backend/.env.example backend/.env
     ```
     Or set them inline (Windows PowerShell):
     ```bash
     $env:GROQ_API_KEY="your-groq-key"
     $env:GEMINI_API_KEY="your-gemini-key"
     ```
   - `GROQ_API_KEY` drives Whisper STT (`whisper-large-v3`), the Groq LLM fallback
     (`llama-3.3-70b-versatile`) and PlayAI TTS.
   - `GEMINI_API_KEY` drives the primary decision engine (`gemini-2.0-flash`).

## Run
```bash
python run.py
```

The server will start on `http://0.0.0.0:8000` with the WebSocket at `ws://localhost:8000/ws/audio`.
