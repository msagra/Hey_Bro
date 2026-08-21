# Hey Bro - FastAPI Backend Brain

## Setup
1. Ensure Python 3.10+ is installed.
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Set your API Keys:
   ```bash
   # Windows PowerShell
   $env:GROQ_API_KEY="your-groq-key"
   $env:GEMINI_API_KEY="your-gemini-key"
   ```

## Run
```bash
python run.py
```

The server will start on `http://0.0.0.0:8000` with the WebSocket at `ws://localhost:8000/ws/audio`.
