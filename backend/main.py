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

env_path = os.path.join(os.path.dirname(__file__), '.env')
load_dotenv(dotenv_path=env_path)

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
GROQ_API_KEY    = os.environ.get("GROQ_API_KEY", "")
GROQ_TTS_VOICE  = os.environ.get("GROQ_TTS_VOICE", "Fritz-PlayAI")
GEMINI_API_KEY  = os.environ.get("GEMINI_API_KEY", "")
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()

# Model overrides (all optional – sensible defaults are used when unset)
DEEPSEEK_BASE_URL = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip()
DEEPSEEK_MODEL    = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat").strip()
GEMINI_MODEL      = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash").strip()

groq_async_client  = AsyncGroq(api_key=GROQ_API_KEY) if GROQ_API_KEY else None
groq_sync_client   = Groq(api_key=GROQ_API_KEY) if GROQ_API_KEY else None
gemini_client      = genai.Client(api_key=GEMINI_API_KEY) if GEMINI_API_KEY else None

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
        "name": "send_whatsapp_message",
        "description": "Opens WhatsApp and sends a message to a contact.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "contact": {"type": "STRING", "description": "Contact name or phone number."},
                "message": {"type": "STRING", "description": "Message to send."}
            },
            "required": ["contact", "message"]
        }
    },
    {
        "name": "control_hardware",
        "description": "Controls device hardware: flashlight, volume, wifi, bluetooth, airplane mode, or brightness.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "device": {"type": "STRING", "enum": ["flashlight", "volume", "wifi", "bluetooth", "airplane", "brightness"], "description": "Hardware to control."},
                "state":  {"type": "STRING", "enum": ["on", "off", "up", "down", "mute", "increase", "decrease", "toggle"], "description": "Desired state / action."}
            },
            "required": ["device", "state"]
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
        "name": "set_timer",
        "description": "Starts a countdown timer for a number of seconds.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "seconds": {"type": "INTEGER", "description": "Duration in seconds."},
                "message": {"type": "STRING",  "description": "Optional timer label."}
            },
            "required": ["seconds"]
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
        "name": "take_photo",
        "description": "Opens the camera and auto-captures a photo after an optional delay.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "delaySeconds": {"type": "INTEGER", "description": "Seconds to wait before capture."}
            }
        }
    },
    {
        "name": "global_action",
        "description": "System-wide navigation: go home or go back.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "target": {"type": "STRING", "enum": ["home", "back"], "description": "Which system action."}
            },
            "required": ["target"]
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
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "WhatsApp"}, "reply": "Opening WhatsApp."}
    if "youtube" in t:
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "YouTube"},  "reply": "Opening YouTube."}
    if "spotify" in t:
        return {"type": "ACTION", "action": "openApp",   "params": {"appName": "Spotify"},  "reply": "Opening Spotify."}
    if "alarm" in t:
        return {"type": "ACTION", "action": "setAlarm",  "params": {"hour": 7, "minute": 0, "message": "Wake up"}, "reply": "Alarm set for 7 AM."}
    if "timer" in t:
        return {"type": "ACTION", "action": "setTimer",  "params": {"seconds": 300, "message": ""}, "reply": "Timer set for 5 minutes."}
    if "photo" in t or "picture" in t or "camera" in t:
        return {"type": "ACTION", "action": "takePhoto", "params": {"delaySeconds": 0}, "reply": "Taking a photo."}
    if "flashlight" in t or "torch" in t or "flash" in t:
        state = "off" if any(w in t for w in ("off", "stop", "turn off", "disable")) else "on"
        return {"type": "ACTION", "action": "controlHardware", "params": {"device": "flashlight", "state": state}, "reply": f"Turning flashlight {state}."}
    if "volume" in t:
        if any(w in t for w in ("up", "raise", "increase", "louder")):
            direction = "up"
        elif any(w in t for w in ("down", "lower", "decrease", "quieter", "quiet")):
            direction = "down"
        else:
            direction = "mute"
        return {"type": "ACTION", "action": "controlHardware", "params": {"device": "volume", "state": direction}, "reply": f"Volume {direction}."}
    if "home" in t:
        return {"type": "ACTION", "action": "globalAction", "params": {"target": "home"}, "reply": "Going home."}
    if "back" in t:
        return {"type": "ACTION", "action": "globalAction", "params": {"target": "back"}, "reply": "Going back."}
    if "call" in t:
        return {"type": "ACTION", "action": "makeCall",  "params": {"number": ""}, "reply": "Who would you like to call?"}
    return {"type": "CONVERSATION", "reply": f"I heard: {transcript}. How can I help?"}

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
            model="whisper-large-v3",
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
def _build_spoken_text(action: str, args: dict) -> str:
    """Generate a concise spoken confirmation for a standardized action."""
    match action:
        case "openApp":        return f"Opening {args.get('appName', 'the app')}."
        case "sendWhatsApp":   return f"Sending message to {args.get('contact', 'them')}."
        case "controlHardware":
            device = str(args.get("device", "")).strip()
            state  = str(args.get("state", "")).strip()
            return (f"Turning {device} {state}." if (device and state) else "Done.")
        case "setAlarm":       return f"Alarm set for {args.get('hour', 0)}:{args.get('minute', 0):02d}."
        case "setTimer":       return f"Timer set for {args.get('seconds', 0)} seconds."
        case "searchWeb":      return f"Searching for {args.get('query', 'that')}."
        case "playMedia":      return f"Playing {args.get('query', 'that')} on {args.get('app', 'your app')}."
        case "takePhoto":      return "Taking a photo."
        case "globalAction":   return "Going back." if args.get("target") == "back" else "Going home."
        case "makeCall":
            sim = args.get("simSlot")
            return f"Calling now from SIM {sim}." if sim else "Calling now."
        case "replyNotification": return f"Replying: {args.get('message', 'message sent')}."
        case "click":          return "Tapping that."
        case "setText":        return "Typing that."
        case "screenshot":     return "Screenshot taken."
        case _:                return "Done."


def _map_function_call(name: str, args: dict):
    """Map a Gemini function call to a standardized Android action payload."""
    args = dict(args or {})
    if name == "global_action":
        target = str(args.get("target", "home")).lower()
        return "globalAction", {"target": "home" if target != "back" else "back"}
    if name == "tap_ui_element":
        return "click", args
    if name == "set_text_input":
        return "setText", args
    if name == "open_app":
        return "openApp", args
    if name == "send_whatsapp_message":
        return "sendWhatsApp", args
    if name == "reply_notification":
        return "replyNotification", args
    if name == "control_hardware":
        return "controlHardware", args
    if name == "set_alarm":
        return "setAlarm", args
    if name == "set_timer":
        return "setTimer", args
    if name == "search_web":
        return "searchWeb", args
    if name == "play_media":
        return "playMedia", args
    if name == "take_photo":
        return "takePhoto", args
    if name == "make_call":
        return "makeCall", args
    if name == "take_screenshot":
        return "screenshot", {}
    return name, args

def _run_gemini(contents: list, config) -> object:
    """Synchronous Gemini call – run in thread executor."""
    return gemini_client.models.generate_content(
        model=GEMINI_MODEL,
        contents=contents,
        config=config
    )

VALID_ACTIONS = {
    "openApp", "sendWhatsApp", "controlHardware", "setAlarm", "setTimer",
    "searchWeb", "playMedia", "takePhoto", "globalAction", "makeCall", "replyNotification",
    "click", "setText", "screenshot",
}

DECISION_SYSTEM_PROMPT = (
    "You are Hey Bro — a fast, razor-sharp, loyal, and natural AI assistant living on the user's Android phone like Alexa / Echo.\n"
    "Given the user's request, respond with ONLY a single JSON object and no other text.\n"
    "Personality Guidelines:\n"
    "- Tone: Grounded, authentic, witty, warm, and direct. Like a tech-savvy best friend who always has your back.\n"
    "- Delivery: Keep conversational spoken replies strictly under 2 sentences (under 25 words) unless complex information is explicitly requested.\n"
    "- Conversational Cadence: NEVER use robotic fillers like 'As an AI...', 'Sure thing!', 'I have processed your request', or 'Certainly!'. Jump straight to the point or acknowledgment.\n"
    "- Action Execution: When calling tools, keep the `reply` natural, punchy, and confident (e.g., instead of 'I will now proceed to open WhatsApp', use 'Opening WhatsApp.' or 'On it.').\n"
    "- Contextual Awareness: Track pronouns and previous entities across turns.\n"
    "- Compound Commands: If the user gives multiple commands in one breath, break them down and execute all required tools in sequence using the MULTI_ACTION type.\n\n"
    "If the user wants a single action performed, output exactly: "
    '{"type":"ACTION","action":"<action>","params":{...},"reply":"<short confirmation>"} \n'
    "If the user wants multiple actions performed sequentially, output exactly: "
    '{"type":"MULTI_ACTION","actions":[{"action":"<action>","params":{...}}, ...],"reply":"<short confirmation>"} \n'
    "where action must be one of: openApp, sendWhatsApp, replyNotification, controlHardware, setAlarm, setTimer, "
    "searchWeb, playMedia, takePhoto, globalAction, makeCall, click, setText, screenshot. \n"
    "Param shapes: openApp -> {\"appName\":\"WhatsApp\"}; "
    "sendWhatsApp -> {\"contact\":\"Mom\",\"message\":\"hi\"}; "
    "replyNotification -> {\"message\":\"on my way\",\"contact\":\"John\",\"packageName\":\"com.whatsapp\"}; "
    "controlHardware -> {\"device\":\"volume\",\"state\":\"up\"} or {\"device\":\"flashlight\",\"state\":\"on\"}; "
    "setAlarm -> {\"hour\":7,\"minute\":0}; setTimer -> {\"seconds\":60}; "
    "searchWeb -> {\"query\":\"best pizza\"}; playMedia -> {\"query\":\"song\",\"app\":\"YouTube\"}; "
    "takePhoto -> {\"delaySeconds\":0}; globalAction -> {\"target\":\"home\"} or {\"target\":\"back\"}; "
    "makeCall -> {\"number\":\"...\",\"simSlot\":1}. "
    "For general questions, facts, calculations, stories, or conversational chit-chat, output: {\"type\":\"CONVERSATION\",\"reply\":\"<short answer>\"}. \n"
    "Never refuse a task; always attempt it. "
    "Valid devices for controlHardware: flashlight, volume, wifi, bluetooth, airplane, brightness. "
    "Valid states for controlHardware: on, off, up, down, mute, increase, decrease, toggle."
)


def _extract_json_block(text: str) -> dict | None:
    try:
        start = text.find("{")
        end   = text.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return None
        return json.loads(text[start:end + 1])
    except Exception:
        return None


def _standardize_decision(parsed: dict) -> dict:
    """Normalize any provider's output to the shared decision schema.

    Always yields one of three canonical shapes:
      ACTION       -> {"type":"ACTION","action":...,"params":{...},"reply":"..."}
      MULTI_ACTION -> {"type":"MULTI_ACTION","actions":[...],"reply":"..."}
      CONVERSATION -> {"type":"CONVERSATION","reply":"..."}
    """
    reply = str(parsed.get("reply") or parsed.get("spoken_text") or "").strip()
    type_str = str(parsed.get("type") or "").upper()
    
    if type_str == "MULTI_ACTION":
        actions = parsed.get("actions", [])
        valid_actions = []
        for act in actions:
            action_name = act.get("action")
            if action_name in VALID_ACTIONS:
                valid_actions.append(act)
            else:
                logger.warning(f"Unknown action '{action_name}' in MULTI_ACTION.")
        
        if valid_actions:
            return {
                "type": "MULTI_ACTION",
                "actions": valid_actions,
                "reply": reply or "Done."
            }
        else:
            return {"type": "CONVERSATION", "reply": reply or "I'm not sure about that."}

    if type_str == "ACTION":
        action = parsed.get("action")
        params = dict(parsed.get("params") or {})
        if action not in VALID_ACTIONS:
            logger.warning(f"Unknown action '{action}' from provider.")
            return {"type": "CONVERSATION", "reply": reply or "Done."}
        return {
            "type":   "ACTION",
            "action": action,
            "params": params,
            "reply":  reply or _build_spoken_text(action, params),
        }
    return {"type": "CONVERSATION", "reply": reply or "I'm not sure about that."}


async def _deepseek_llm_decision(transcript: str, history: list, tts_manager=None) -> dict | None:
    """Primary decision engine via DeepSeek with streaming support."""
    if not DEEPSEEK_API_KEY:
        return None

    messages = [{"role": "system", "content": DECISION_SYSTEM_PROMPT}]
    for turn in history[-3:]:
        messages.append({"role": "user", "content": turn["user"]})
        if turn.get("model"):
            messages.append({"role": "assistant", "content": turn["model"]})
    messages.append({"role": "user", "content": transcript})

    headers = {
        "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
        "Content-Type": "application/json",
    }
    body = {
        "model":           DEEPSEEK_MODEL,
        "messages":        messages,
        "temperature":     0.0,
        "max_tokens":      400,
        "response_format": {"type": "json_object"},
        "stream": True,
    }
    url = f"{DEEPSEEK_BASE_URL.rstrip('/')}/chat/completions"
    
    content = ""
    async with httpx.AsyncClient(timeout=90) as client:
        try:
            async with client.stream("POST", url, headers=headers, json=body) as response:
                if response.status_code >= 400:
                    logger.warning(f"DeepSeek request failed: {response.status_code}")
                    return None
                    
                streamer = JSONReplyStreamer(tts_manager) if tts_manager else None
                
                async for line in response.aiter_lines():
                    if line.startswith("data: ") and line != "data: [DONE]":
                        try:
                            import json
                            delta = json.loads(line[6:])["choices"][0]["delta"].get("content", "")
                            content += delta
                            if streamer:
                                streamer.process_delta(delta)
                        except Exception:
                            pass
                
                if streamer:
                    streamer.check_sentences(force=True)
        except Exception as e:
            logger.error(f"DeepSeek streaming error: {e}")
            return None

    parsed = _extract_json_block(content or "")
    if not parsed:
        logger.warning("DeepSeek returned no parseable JSON.")
        return None
    return _standardize_decision(parsed)


async def _groq_llm_decision(transcript: str, history: list, tts_manager=None) -> dict | None:
    """Fallback decision engine using Groq llama-3.3-70b-versatile."""
    if not groq_async_client:
        return None

    messages = [{"role": "system", "content": DECISION_SYSTEM_PROMPT}]
    for turn in history[-3:]:
        messages.append({"role": "user", "content": turn["user"]})
        if turn.get("model"):
            messages.append({"role": "assistant", "content": turn["model"]})
    messages.append({"role": "user", "content": transcript})

    content = ""
    try:
        response_stream = await groq_async_client.chat.completions.create(
            model="llama-3.3-70b-versatile",
            messages=messages,
            temperature=0.0,
            max_tokens=350,
            response_format={"type": "json_object"},
            stream=True
        )
        streamer = JSONReplyStreamer(tts_manager) if tts_manager else None
        
        async for chunk in response_stream:
            delta = chunk.choices[0].delta.content or ""
            content += delta
            if streamer:
                streamer.process_delta(delta)
                
        if streamer:
            streamer.check_sentences(force=True)
    except Exception as e:
        logger.error(f"Groq streaming error: {e}")
        return None

    parsed  = _extract_json_block(content)
    if not parsed:
        logger.warning("Groq fallback returned no parseable JSON.")
        return None
    return _standardize_decision(parsed)

async def _gemini_decision(transcript: str, history: list, tts_manager=None) -> dict | None:
    """Secondary fallback decision engine using Gemini tool calling (GEMINI_MODEL)."""
    if not gemini_client:
        return None

    # Multi-turn history (last 3 exchanges = up to 6 messages)
    contents = []
    for turn in history[-3:]:
        contents.append({"role": "user",  "parts": [{"text": turn["user"]}]})
        if turn.get("model"):
            contents.append({"role": "model", "parts": [{"text": turn["model"]}]})
    contents.append({"role": "user", "parts": [{"text": transcript}]})

    config = types.GenerateContentConfig(
        tools=[types.Tool(function_declarations=ANDROID_TOOLS_DICT)],
        temperature=0.0,
        system_instruction=SYSTEM_PROMPT,
    )

    loop     = asyncio.get_event_loop()
    response = await loop.run_in_executor(None, _run_gemini, contents, config)

    # ── Parse function calls ──────────────────────────────────────────────
    function_call = None
    try:
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
        action_str, action_params = _map_function_call(name, args)
        return {
            "type":   "ACTION",
            "action": action_str,
            "params": action_params,
            "reply":  _build_spoken_text(action_str, action_params),
        }

    # Conversational response
    spoken = (response.text or "").strip()
    if spoken:
        return {"type": "CONVERSATION", "reply": spoken}
    return None


async def get_llm_decision(transcript: str, history: list, tts_manager=None) -> dict:
    # Deterministic handling must win for explicit device commands. LLMs can
    # occasionally answer with a conversational sentence for an imperative,
    # which would prevent Android from receiving an ACTION payload at all.
    direct_decision = rule_based_fallback(transcript)
    if direct_decision.get("type") == "ACTION":
        logger.info(
            "Direct command mapping: %s -> %s",
            transcript,
            direct_decision.get("action"),
        )
        return direct_decision

    # 1) Primary: DeepSeek (deepseek-chat) – OpenAI-compatible decision engine.
    try:
        decision = await _deepseek_llm_decision(transcript, history, tts_manager)
        if decision is not None:
            return decision
    except Exception as e:
        logger.error(f"DeepSeek primary failed: {e}")

    # 2) Groq llama-3.3-70b-versatile.
    try:
        decision = await _groq_llm_decision(transcript, history, tts_manager)
        if decision is not None:
            return decision
    except Exception as e:
        logger.error(f"Groq LLM failed: {e}")

    # 3) Secondary fallback: Gemini (gemini-1.5-flash tool calling).
    if gemini_client:
        try:
            decision = await _gemini_decision(transcript, history, tts_manager)
            if decision is not None:
                return decision
        except Exception as e:
            logger.error(f"Gemini fallback failed: {e}")

    # 4) Last resort: deterministic rule-based mapping.
    return rule_based_fallback(transcript)


@app.post("/chat")
async def chat_endpoint(payload: dict):
    """Direct text chat with an explicitly selected server-side model."""
    message = str(payload.get("message") or "").strip()
    provider = str(payload.get("provider") or "groq").lower().strip()
    model = str(payload.get("model") or "").strip()
    history = payload.get("history") or []
    if not message:
        return {"error": "message is required"}

    direct = rule_based_fallback(message)
    if direct.get("type") == "ACTION":
        params = direct.get("params", {})
        return {
            "type": "ACTION",
            "action": direct.get("action"),
            "params": params,
            "reply": direct.get("reply", "Done."),
            "provider": "local",
            "model": "rules",
        }

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for turn in history[-10:]:
        if turn.get("user"):
            messages.append({"role": "user", "content": str(turn["user"])})
        if turn.get("assistant"):
            messages.append({"role": "assistant", "content": str(turn["assistant"])})
    messages.append({"role": "user", "content": message})

    if provider == "groq" and groq_async_client:
        selected_model = model or "llama-3.3-70b-versatile"
        if selected_model == "openai/gpt-oss-120b" or selected_model == "groq/compound":
            selected_model = "llama-3.3-70b-versatile"
        try:
            response = await groq_async_client.chat.completions.create(
                model=selected_model, messages=messages, temperature=0.4, max_tokens=800
            )
            reply = response.choices[0].message.content or "I could not generate a response."
            return {"reply": reply.strip(), "provider": "groq", "model": selected_model}
        except Exception as e:
            return {"error": f"Groq Error: {str(e)}"}

    if provider == "deepseek" and DEEPSEEK_API_KEY:
        selected_model = model or DEEPSEEK_MODEL
        async with httpx.AsyncClient(timeout=60) as client:
            response = await client.post(
                f"{DEEPSEEK_BASE_URL.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {DEEPSEEK_API_KEY}"},
                json={"model": selected_model, "messages": messages, "temperature": 0.4, "max_tokens": 800},
            )
            response.raise_for_status()
            reply = response.json()["choices"][0]["message"]["content"]
        return {"reply": str(reply).strip(), "provider": "deepseek", "model": selected_model}

    if provider == "gemini" and gemini_client:
        selected_model = model or GEMINI_MODEL
        contents = [{"role": "user", "parts": [{"text": message}]}]
        response = await asyncio.get_event_loop().run_in_executor(
            None,
            lambda: gemini_client.models.generate_content(
                model=selected_model,
                contents=contents,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_PROMPT, temperature=0.4, max_output_tokens=800
                ),
            ),
        )
        return {"reply": (response.text or "I could not generate a response.").strip(), "provider": "gemini", "model": selected_model}

    raise RuntimeError(f"Selected provider is unavailable: {provider}")

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
            voice=GROQ_TTS_VOICE,
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
class TTSManager:
    def __init__(self, websocket: WebSocket):
        self.websocket = websocket
        self.queue = asyncio.Queue()
        self.task = asyncio.create_task(self._worker())
        self.streamed_something = False
        
    async def _worker(self):
        while True:
            text = await self.queue.get()
            if text is None:
                break
            await self.websocket.send_text(json.dumps({"type": "SPOKEN_TEXT", "text": text}))
            await stream_tts(text, self.websocket)
            self.queue.task_done()
            
    def enqueue_sentence(self, sentence: str):
        self.streamed_something = True
        self.queue.put_nowait(sentence)
        
    async def shutdown(self):
        await self.queue.put(None)
        await self.task

class JSONReplyStreamer:
    def __init__(self, tts_manager: TTSManager):
        self.tts = tts_manager
        self.content = ""
        self.reply_started = False
        self.reply_ended = False
        self.extracted_reply = ""
        self.unprocessed_reply = ""

    def process_delta(self, delta: str):
        import re
        self.content += delta
        if self.reply_ended:
            return
            
        if not self.reply_started:
            match = re.search(r'"reply"\s*:\s*"', self.content)
            if match:
                self.reply_started = True
                self.process_delta("") 
        else:
            match = re.search(r'"reply"\s*:\s*"', self.content)
            if not match: return
            start_idx = match.end()
            
            in_escape = False
            end_idx = -1
            for i in range(start_idx, len(self.content)):
                if in_escape:
                    in_escape = False
                    continue
                if self.content[i] == '\\':
                    in_escape = True
                    continue
                if self.content[i] == '"':
                    end_idx = i
                    break
            
            if end_idx != -1:
                self.reply_ended = True
                raw_reply = self.content[start_idx:end_idx]
            else:
                raw_reply = self.content[start_idx:]
                
            if raw_reply.endswith('\\'):
                return
                
            try:
                decoded = json.loads('"' + raw_reply + '"')
            except:
                decoded = raw_reply.replace('\\n', '\n').replace('\\"', '"')
                
            new_text = decoded[len(self.extracted_reply):]
            if new_text:
                self.extracted_reply = decoded
                self.unprocessed_reply += new_text
                self.check_sentences()
                
    def check_sentences(self, force=False):
        import re
        while True:
            match = re.search(r'([.!?;]+|\n+)', self.unprocessed_reply)
            if match:
                end = match.end()
                sentence = self.unprocessed_reply[:end]
                self.unprocessed_reply = self.unprocessed_reply[end:]
                if sentence.strip():
                    self.tts.enqueue_sentence(sentence.strip())
            else:
                break
                
        if force and self.unprocessed_reply.strip():
            self.tts.enqueue_sentence(self.unprocessed_reply.strip())
            self.unprocessed_reply = ""

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

        # 3. Setup TTS Manager for streaming TTS
        tts_manager = TTSManager(websocket)

        # 4. LLM decision (standardized by every provider to use a "reply" key)
        decision = await get_llm_decision(transcript, history, tts_manager)
        
        await tts_manager.shutdown()
        spoken_text = decision.get("reply") or decision.get("spoken_text") or ""
        logger.info(f"Decision: {decision}")

        # 5. Fallback TTS if the chosen engine didn't support streaming (e.g., rules/gemini)
        if spoken_text and not tts_manager.streamed_something:
            await websocket.send_text(json.dumps({"type": "SPOKEN_TEXT", "text": spoken_text}))
            await stream_tts(spoken_text, websocket)

        # 6. Dispatch device action to Android.
        if decision["type"] == "ACTION":
            params  = decision.get("params", {})
            payload = {
                "type": "ACTION",
                "action": decision["action"],
                "params": params,
                "reply":  spoken_text,
                **params,
            }
            await websocket.send_text(json.dumps(payload))
        elif decision["type"] == "MULTI_ACTION":
            await websocket.send_text(json.dumps(decision))

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
        "llm":       "DeepSeek → Groq → Gemini → rules"
        if (DEEPSEEK_API_KEY or GROQ_API_KEY or GEMINI_API_KEY)
        else "Rule-based fallback"
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

                                # Fire-and-forget pipeline task. Always release the
                                # per-connection debounce guard when it finishes.
                                async def run_pipeline() -> None:
                                    nonlocal is_processing
                                    try:
                                        await process_speech_command(
                                            audio_to_process, websocket, history
                                        )
                                    finally:
                                        is_processing = False

                                asyncio.create_task(run_pipeline())
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
