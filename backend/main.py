import asyncio
import io
import json
import logging
import os
import wave
import httpx
import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from groq import AsyncGroq, Groq
from google import genai
from google.genai import types
from dotenv import load_dotenv

load_dotenv()

# ── Logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("hey_bro")

# ── App ───────────────────────────────────────────────────────────────────────
app = FastAPI(title="Hey Bro Brain Backend", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── API Clients ───────────────────────────────────────────────────────────────
GROQ_API_KEY   = os.environ.get("GROQ_API_KEY", "")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")

groq_async_client = AsyncGroq(api_key=GROQ_API_KEY) if GROQ_API_KEY else None
groq_sync_client  = Groq(api_key=GROQ_API_KEY) if GROQ_API_KEY else None
gemini_client     = genai.Client(api_key=GEMINI_API_KEY) if GEMINI_API_KEY else None

# ── Audio Config ──────────────────────────────────────────────────────────────
SAMPLE_RATE = 16000  # Hz – must match AudioRecord on Android
VOSK_MODEL_PATH = os.environ.get("VOSK_MODEL_PATH", "").strip()
OPENAI_LOCAL_URL = (
    os.environ.get("OPENAI_LOCAL_URL", "").strip()
    or os.environ.get("WHISPER_LOCAL_URL", "").strip()
)
OPENAI_LOCAL_API_KEY = os.environ.get("OPENAI_LOCAL_API_KEY", "").strip()
OPENAI_LOCAL_MODEL = os.environ.get("OPENAI_LOCAL_MODEL", "whisper-1").strip()

# ── System Prompt ─────────────────────────────────────────────────────────────
SYSTEM_PROMPT = (
    "You are 'Hey Bro', a friendly Android voice assistant. "
    "When the user wants to do something on their phone, call the matching tool. "
    "For general questions, answer concisely in 1-2 sentences. "
    "Never refuse a task; always attempt it. "
    "Keep spoken responses short and natural."
)

# ── Tool Definitions ──────────────────────────────────────────────────────────
ANDROID_TOOLS_DICT = [
    {
        "name": "tap_ui_element",
        "description": "Taps a UI element on screen matched by visible text or resource ID.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "targetText": {"type": "STRING", "description": "Visible label / button text."},
                "targetId":   {"type": "STRING", "description": "Android resource ID (optional)."}
            }
        }
    },
    {
        "name": "set_text_input",
        "description": "Types text into a focused or identified EditText field.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "textValue":  {"type": "STRING", "description": "Text to type."},
                "targetText": {"type": "STRING", "description": "Hint or current value of the field."}
            },
            "required": ["textValue"]
        }
    },
    {
        "name": "global_action",
        "description": "System-wide action: back or home.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "action": {"type": "STRING", "enum": ["globalBack", "globalHome"]}
            },
            "required": ["action"]
        }
    },
    {
        "name": "open_app",
        "description": "Launches an installed app by name (e.g. WhatsApp, YouTube, Spotify).",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "appName": {"type": "STRING", "description": "Name of the app to open."}
            },
            "required": ["appName"]
        }
    },
    {
        "name": "set_alarm",
        "description": "Sets a phone alarm for the given time.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "hour":    {"type": "INTEGER", "description": "Hour (0-23)."},
                "minute":  {"type": "INTEGER", "description": "Minute (0-59)."},
                "message": {"type": "STRING",  "description": "Optional alarm label."}
            },
            "required": ["hour", "minute"]
        }
    },
    {
        "name": "make_call",
        "description": "Initiates a phone call to a number.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "number": {"type": "STRING", "description": "Phone number to call."}
            },
            "required": ["number"]
        }
    },
    {
        "name": "send_whatsapp_message",
        "description": "Opens WhatsApp and sends a message to a contact.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "contact": {"type": "STRING", "description": "Contact name."},
                "message": {"type": "STRING", "description": "Message to send."}
            },
            "required": ["contact", "message"]
        }
    },
    {
        "name": "search_web",
        "description": "Opens the browser and searches Google for a query.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "query": {"type": "STRING", "description": "Search query."}
            },
            "required": ["query"]
        }
    },
    {
        "name": "play_media",
        "description": "Plays music, video, or podcast on an app like YouTube or Spotify.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "query": {"type": "STRING", "description": "Song, artist, or video name."},
                "app":   {"type": "STRING", "description": "App to use (YouTube, Spotify, etc)."}
            },
            "required": ["query"]
        }
    },
    {
        "name": "take_screenshot",
        "description": "Takes a screenshot of the current screen.",
        "parameters": {
            "type": "OBJECT",
            "properties": {}
        }
    }
]

# ── Rule-based Fallback ────────────────────────────────────────────────────────
def rule_based_fallback(transcript: str) -> dict:
    t = transcript.lower()
    if "whatsapp" in t:
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "WhatsApp"},   "spoken_text": "Opening WhatsApp."}
    if "youtube" in t:
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "YouTube"},    "spoken_text": "Opening YouTube."}
    if "spotify" in t:
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "Spotify"},    "spoken_text": "Opening Spotify."}
    if "alarm" in t:
        return {"type": "ACTION", "action": "setAlarm",  "params": {"hour": 7, "minute": 0, "message": "Wake up"}, "spoken_text": "Alarm set for 7 AM."}
    if "home" in t:
        return {"type": "ACTION", "action": "globalHome","params": {},                        "spoken_text": "Going home."}
    if "back" in t:
        return {"type": "ACTION", "action": "globalBack","params": {},                        "spoken_text": "Going back."}
    if "call" in t:
        return {"type": "ACTION", "action": "makeCall",  "params": {"number": ""},            "spoken_text": "Who would you like to call?"}
    return {"type": "CONVERSATION", "spoken_text": f"I heard: {transcript}. How can I help?"}

# ── STT ───────────────────────────────────────────────────────────────────────
def _as_wav_bytes(audio_bytes: bytes) -> bytes:
    wav_io = io.BytesIO()
    with wave.open(wav_io, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(audio_bytes)
    return wav_io.getvalue()


async def _transcribe_with_vosk(audio_bytes: bytes) -> str | None:
    if not VOSK_MODEL_PATH or not os.path.isdir(VOSK_MODEL_PATH):
        return None

    try:
        from vosk import KaldiRecognizer, Model
    except Exception:
        logger.warning("Vosk is not installed or model path is unavailable.")
        return None

    try:
        model = Model(VOSK_MODEL_PATH)
        recognizer = KaldiRecognizer(model, SAMPLE_RATE)
        recognizer.AcceptWaveform(audio_bytes)
        final = recognizer.FinalResult()
        parsed = json.loads(final) if isinstance(final, str) else final
        text = str(parsed.get("text", "")).strip()
        if text:
            logger.info(f"Vosk STT → '{text}'")
            return text
    except Exception as e:
        logger.warning(f"Vosk STT failed: {e}")
    return None


async def _transcribe_with_openai_local(audio_bytes: bytes) -> str | None:
    if not OPENAI_LOCAL_URL:
        return None

    try:
        headers = {"Accept": "application/json"}
        if OPENAI_LOCAL_API_KEY:
            headers["Authorization"] = f"Bearer {OPENAI_LOCAL_API_KEY}"

        async with httpx.AsyncClient(timeout=90) as client:
            candidate_urls = [OPENAI_LOCAL_URL]
            if not OPENAI_LOCAL_URL.rstrip("/").endswith("/audio/transcriptions"):
                candidate_urls.append(f"{OPENAI_LOCAL_URL.rstrip('/')}/v1/audio/transcriptions")

            for url in candidate_urls:
                try:
                    response = await client.post(
                        url,
                        files={"file": ("audio.wav", _as_wav_bytes(audio_bytes), "audio/wav")},
                        data={"model": OPENAI_LOCAL_MODEL},
                        headers=headers,
                    )
                    if response.status_code >= 400:
                        logger.warning(f"OpenAI Local Whisper failed on {url}: {response.status_code} {response.text[:200]}")
                        continue

                    payload = response.json()
                    text = str(payload.get("text") or payload.get("transcript") or "").strip()
                    if text:
                        logger.info(f"OpenAI Local Whisper STT → '{text}'")
                        return text
                except Exception as e:
                    logger.warning(f"OpenAI Local Whisper request failed for {url}: {e}")
    except Exception as e:
        logger.warning(f"OpenAI Local Whisper setup failed: {e}")
    return None


async def _transcribe_with_groq(audio_bytes: bytes) -> str | None:
    if not groq_async_client:
        return None

    try:
        wav_bytes = _as_wav_bytes(audio_bytes)
        transcription = await groq_async_client.audio.transcriptions.create(
            file=("audio.wav", wav_bytes, "audio/wav"),
            model="whisper-large-v3-turbo",
            response_format="text",
            language="en",
        )
        text = (transcription.strip()
                if isinstance(transcription, str)
                else str(transcription).strip())
        if text:
            logger.info(f"Groq STT → '{text}'")
            return text
    except Exception as e:
        logger.error(f"Groq STT error: {e}")
    return None


async def transcribe_audio(audio_bytes: bytes) -> str:
    min_bytes = SAMPLE_RATE  # 0.5 seconds at 16kHz 16-bit
    if len(audio_bytes) < min_bytes:
        logger.warning(f"Audio buffer too small ({len(audio_bytes)} bytes), skipping STT.")
        return ""

    for name, func in (
        ("vosk", _transcribe_with_vosk),
        ("openai_local_whisper", _transcribe_with_openai_local),
        ("groq_whisper", _transcribe_with_groq),
    ):
        try:
            text = await func(audio_bytes)
            if text and text.strip():
                return text.strip()
        except Exception as e:
            logger.error(f"STT fallback engine '{name}' crashed: {e}")

    logger.warning("All STT engines failed or are unavailable.")
    return ""

# ── LLM ───────────────────────────────────────────────────────────────────────
def _build_spoken_text(call_name: str, args: dict) -> str:
    """Generate a concise spoken confirmation for an action."""
    match call_name:
        case "open_app":             return f"Opening {args.get('appName', 'the app')}."
        case "set_alarm":            return f"Alarm set for {args.get('hour', 0)}:{args.get('minute', 0):02d}."
        case "make_call":            return "Calling now."
        case "global_action":        return "Going back." if args.get("action") == "globalBack" else "Going home."
        case "search_web":           return f"Searching for {args.get('query', 'that')}."
        case "play_media":           return f"Playing {args.get('query', 'that')} on {args.get('app', 'your app')}."
        case "send_whatsapp_message":return f"Sending message to {args.get('contact', 'them')}."
        case "take_screenshot":      return "Screenshot taken."
        case _:                      return "Done."

def _run_gemini(contents: list, config) -> object:
    """Synchronous Gemini call – run in thread executor."""
    return gemini_client.models.generate_content(
        model="gemini-3.6-flash",
        contents=contents,
        config=config
    )

async def get_llm_decision(transcript: str, history: list) -> dict:
    if not gemini_client:
        return rule_based_fallback(transcript)

    try:
        # Build multi-turn history (last 3 exchanges = 6 messages)
        contents = []
        for turn in history[-3:]:
            contents.append({"role": "user",  "parts": [{"text": turn["user"]}]})
            if turn.get("model"):
                contents.append({"role": "model", "parts": [{"text": turn["model"]}]})
        contents.append({"role": "user", "parts": [{"text": transcript}]})

        config = types.GenerateContentConfig(
            tools=[types.Tool(function_declarations=ANDROID_TOOLS_DICT)],
            temperature=0.0,
            system_instruction=SYSTEM_PROMPT
        )

        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(None, _run_gemini, contents, config)

        # ── Parse function calls ──────────────────────────────────────────────
        function_call = None
        try:
            # Try the convenience accessor first
            if response.function_calls:
                function_call = response.function_calls[0]
        except Exception:
            pass

        if function_call is None:
            # Fallback: walk candidate parts
            try:
                for part in response.candidates[0].content.parts:
                    if hasattr(part, "function_call") and part.function_call:
                        function_call = part.function_call
                        break
            except Exception:
                pass

        if function_call:
            name = function_call.name
            args = dict(function_call.args) if function_call.args else {}

            # Map function name → Android action string
            if name == "global_action":
                action_str = args.pop("action", "globalHome")
                action_params = {}
            elif name == "tap_ui_element":
                action_str, action_params = "click",   args
            elif name == "set_text_input":
                action_str, action_params = "setText",  args
            elif name == "open_app":
                action_str, action_params = "openApp",  args
            elif name == "set_alarm":
                action_str, action_params = "setAlarm", args
            elif name == "make_call":
                action_str, action_params = "makeCall", args
            elif name == "send_whatsapp_message":
                action_str, action_params = "sendWhatsApp", args
            elif name == "search_web":
                action_str, action_params = "searchWeb",  args
            elif name == "play_media":
                action_str, action_params = "playMedia",  args
            elif name == "take_screenshot":
                action_str, action_params = "screenshot",  {}
            else:
                action_str, action_params = name, args

            return {
                "type":         "ACTION",
                "action":       action_str,
                "params":       action_params,
                "spoken_text":  _build_spoken_text(name, args)
            }

        # Conversational response
        spoken = (response.text or "I'm not sure about that.").strip()
        return {"type": "CONVERSATION", "spoken_text": spoken}

    except Exception as e:
        logger.error(f"LLM error: {e}")
        return rule_based_fallback(transcript)

# ── TTS ───────────────────────────────────────────────────────────────────────
def _generate_speech_sync(text: str) -> bytes | None:
    """
    Synchronous Groq PlayAI TTS call.
    Returns raw PCM bytes at SAMPLE_RATE (16kHz), 16-bit mono.
    Run this in a thread executor – never call directly in async context.
    """
    if not groq_sync_client:
        return None
    try:
        response = groq_sync_client.audio.speech.create(
            model="playai-tts",
            voice="Fritz-PlayAI",
            input=text[:4096],
            response_format="wav"
        )
        wav_bytes = response.content

        # Parse WAV header to get sample rate and PCM data
        with io.BytesIO(wav_bytes) as bio:
            with wave.open(bio, "rb") as wf:
                orig_sr  = wf.getframerate()
                n_ch     = wf.getnchannels()
                pcm_raw  = wf.readframes(wf.getnframes())

        audio = np.frombuffer(pcm_raw, dtype=np.int16)

        # Stereo → mono
        if n_ch == 2:
            audio = audio.reshape(-1, 2).mean(axis=1).astype(np.int16)

        # Resample to SAMPLE_RATE if needed (linear interpolation)
        if orig_sr != SAMPLE_RATE:
            target_len = int(len(audio) * SAMPLE_RATE / orig_sr)
            audio = np.interp(
                np.linspace(0, len(audio) - 1, target_len),
                np.arange(len(audio)),
                audio
            ).astype(np.int16)

        return audio.tobytes()

    except Exception as e:
        logger.error(f"Groq TTS error: {e}")
        return None


async def stream_tts(text: str, websocket: WebSocket) -> None:
    """Stream TTS audio to the WebSocket client as raw 16kHz PCM chunks."""
    if not text or not text.strip():
        return

    logger.info(f"TTS → '{text}'")

    pcm_bytes: bytes | None = None

    if GROQ_API_KEY:
        try:
            loop = asyncio.get_event_loop()
            pcm_bytes = await loop.run_in_executor(None, _generate_speech_sync, text)
        except Exception as e:
            logger.error(f"TTS executor error: {e}")

    if pcm_bytes:
        # Stream in 2 KB chunks
        chunk_size = 2048
        for i in range(0, len(pcm_bytes), chunk_size):
            await websocket.send_bytes(pcm_bytes[i : i + chunk_size])
            await asyncio.sleep(0.02)
    else:
        # Silence fallback – duration proportional to word count
        words    = max(1, len(text.split()))
        duration = min(words * 0.4, 6.0)  # ~0.4 s/word, max 6 s
        silence  = np.zeros(int(SAMPLE_RATE * duration), dtype=np.int16).tobytes()
        for i in range(0, len(silence), 1024):
            await websocket.send_bytes(silence[i : i + 1024])
            await asyncio.sleep(0.01)


# ── Full Pipeline ─────────────────────────────────────────────────────────────
async def process_speech_command(
    audio_bytes: bytes,
    websocket:   WebSocket,
    history:     list
) -> None:
    """
    STT → LLM → (optional action dispatch) → TTS → RESPONSE_COMPLETE.
    Runs as an independent asyncio Task so the WebSocket receive loop
    can keep accepting WAKE_WORD_TRIGGERED / audio without blocking.
    """
    try:
        # 1. STT
        transcript = await transcribe_audio(audio_bytes)
        if not transcript:
            logger.warning("Empty transcript – aborting pipeline.")
            await websocket.send_text(json.dumps({"type": "RESPONSE_COMPLETE"}))
            return

        # 2. Notify Android so the UI can show the transcript immediately
        await websocket.send_text(json.dumps({"type": "TRANSCRIPT", "text": transcript}))

        # 3. LLM decision
        decision     = await get_llm_decision(transcript, history)
        spoken_text  = decision.get("spoken_text", "")
        logger.info(f"Decision: {decision}")

        # 4. Send spoken text for UI display before TTS plays
        if spoken_text:
            await websocket.send_text(json.dumps({"type": "SPOKEN_TEXT", "text": spoken_text}))

        # 5. Dispatch device action to Android
        if decision["type"] == "ACTION":
            payload = {"action": decision["action"], **decision.get("params", {})}
            await websocket.send_text(json.dumps(payload))

        # 6. Stream TTS
        if spoken_text:
            await stream_tts(spoken_text, websocket)

        # 7. Signal Android to reset to IDLE
        await websocket.send_text(json.dumps({"type": "RESPONSE_COMPLETE"}))

        # 8. Update per-connection conversation history
        history.append({"user": transcript, "model": spoken_text})
        if len(history) > 10:
            history.pop(0)

    except Exception as e:
        logger.error(f"Pipeline error: {e}")
        try:
            await websocket.send_text(json.dumps({"type": "RESPONSE_COMPLETE"}))
        except Exception:
            pass


# ── HTTP Endpoints ────────────────────────────────────────────────────────────
@app.get("/")
async def root():
    stt_chain = []
    if VOSK_MODEL_PATH:
        stt_chain.append("Vosk")
    if OPENAI_LOCAL_URL:
        stt_chain.append("Whisper (OpenAI Local)")
    if groq_async_client:
        stt_chain.append("Groq Whisper")

    return {
        "status":    "Hey Bro Backend is running!",
        "version":   "2.0.0",
        "websocket": "/ws/audio",
        "stt":       " → ".join(stt_chain) if stt_chain else "Unavailable",
        "tts":       "Groq PlayAI"  if GROQ_API_KEY else "Silence fallback",
        "llm":       "Gemini 3.6 Flash" if GEMINI_API_KEY else "Rule-based fallback"
    }

@app.get("/health")
async def health():
    return {"status": "ok"}


# ── WebSocket Endpoint ────────────────────────────────────────────────────────
@app.websocket("/ws/audio")
async def websocket_audio_endpoint(websocket: WebSocket):
    await websocket.accept()
    logger.info(f"Client connected: {websocket.client}")

    audio_buffer:      bytearray = bytearray()
    awaiting_command:  bool      = False
    is_processing:     bool      = False   # debounce guard
    history:           list      = []      # per-connection conversation memory

    try:
        while True:
            message = await websocket.receive()

            # ── Binary: PCM audio chunk ───────────────────────────────────────
            if "bytes" in message:
                chunk = message["bytes"]
                if awaiting_command:
                    audio_buffer.extend(chunk)

            # ── Text: control signals ─────────────────────────────────────────
            elif "text" in message:
                try:
                    data     = json.loads(message["text"])
                    msg_type = data.get("type", "")

                    if msg_type == "WAKE_WORD_TRIGGERED":
                        logger.info("WAKE_WORD_TRIGGERED received – arming buffer.")
                        awaiting_command = True
                        is_processing    = False
                        audio_buffer.clear()

                    elif msg_type == "END_OF_SPEECH":
                        if awaiting_command and not is_processing:
                            buf_size = len(audio_buffer)
                            logger.info(f"END_OF_SPEECH received. Buffer: {buf_size} bytes.")

                            if buf_size >= SAMPLE_RATE:  # at least 0.5 s
                                audio_to_process = bytes(audio_buffer)
                                audio_buffer.clear()
                                awaiting_command = False
                                is_processing    = True

                                # Fire-and-forget pipeline task
                                asyncio.create_task(
                                    _pipeline_wrapper(
                                        audio_to_process, websocket, history,
                                        on_done=lambda: None
                                    )
                                )
                            else:
                                logger.warning(
                                    f"Buffer too small ({buf_size} bytes) – resetting."
                                )
                                audio_buffer.clear()
                                awaiting_command = False
                                await websocket.send_text(
                                    json.dumps({"type": "RESPONSE_COMPLETE"})
                                )
                        else:
                            logger.warning(
                                f"END_OF_SPEECH ignored "
                                f"(awaiting={awaiting_command}, processing={is_processing})"
                            )

                    elif msg_type == "SCREEN_CONTEXT":
                        # Reserved for future screen-context injection into LLM
                        logger.debug(f"Screen context: {str(data.get('content', ''))[:80]}")

                except json.JSONDecodeError:
                    logger.warning(f"Non-JSON text message received: {message['text'][:80]}")
                except Exception as e:
                    logger.error(f"Error handling text message: {e}")

    except WebSocketDisconnect:
        logger.info(f"Client disconnected: {websocket.client}")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
    finally:
        audio_buffer.clear()


async def _pipeline_wrapper(
    audio_bytes: bytes,
    websocket:   WebSocket,
    history:     list,
    on_done
) -> None:
    """Thin wrapper so is_processing resets even on exceptions."""
    try:
        await process_speech_command(audio_bytes, websocket, history)
    finally:
        on_done()
