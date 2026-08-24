# Hey Bro AI - End-to-End Project Overview

This document provides a comprehensive overview of the "Hey Bro" AI Voice Assistant project, detailing its architecture, end-to-end workflows, file structure, key variables, and API configurations. It is designed to help any developer quickly understand how the entire system functions from the moment a user speaks to the moment the device acts.

---

## 🏗 Architecture Overview

"Hey Bro" is a low-latency, dual-component AI voice assistant:
1. **Frontend / Mobile App (Flutter & Native Android)**: 
   - Handles UI (Dashboard, glow animations, chat history).
   - Manages OS permissions and offline wake word detection.
   - Operates a powerful Android Accessibility Service (`DeviceOperatorService`) to tap UI elements, scroll, input text, and navigate the device globally.
   - Streams live audio to the backend via WebSockets.
2. **Backend (Python / FastAPI)**: 
   - Receives audio streams via WebSockets.
   - Orchestrates Speech-to-Text (Vosk/Whisper/Groq) to transcribe the audio.
   - Routes the transcript and conversational history to a Large Language Model (DeepSeek, Groq, or Gemini).
   - Steers the LLM output into standardized JSON tool-calling actions.
   - Synthesizes Text-to-Speech (TTS) concurrently and streams chunked PCM audio back to the mobile device for ultra-low latency response times.

---

## 🔄 End-to-End Workflow: How a Command is Processed

Here is the exact sequence of events when a user interacts with Hey Bro:

1. **Wake Word Detection (Offline)**: 
   - The Android device runs a background `WakeWordManager` utilizing Picovoice (Porcupine).
   - When the user says *"Hey Bro"*, `HeyBroPhraseWakeEngine` triggers.
   - `CoreVoiceService` randomly plays a greeting ("Hi Sandip, what can I do for you?") via local TTS to provide instant feedback.
2. **Listening Phase**: 
   - `CoreVoiceService` activates the microphone and starts capturing raw 16kHz PCM audio.
   - It connects to the Python backend via WebSocket (`/ws/audio`) and streams the audio buffer.
3. **Speech-to-Text (STT)**: 
   - The backend `process_speech_command` receives the audio bytes.
   - `transcribe_audio` attempts Vosk, OpenAI Whisper, and Groq STT in a waterfall pattern to convert the speech to text.
   - The recognized transcript is sent back to Android (`{"type": "TRANSCRIPT"}`) for immediate UI display.
4. **LLM Decision Engine (Streaming)**:
   - The backend passes the transcript and chat history to `get_llm_decision`.
   - The system queries DeepSeek (primary), Groq (secondary), or Gemini (fallback).
   - The LLM interprets the intent and generates a standardized JSON response in one of three shapes:
     - `CONVERSATION`: Just chat (`{"reply": "..."}`)
     - `ACTION`: A specific device operation (`{"action": "openApp", "params": {"appName": "YouTube"}, "reply": "Opening YouTube"}`)
     - `MULTI_ACTION`: Compound device operations executed sequentially.
5. **Simultaneous TTS & Execution**:
   - As the LLM streams its JSON response, `JSONReplyStreamer` intercepts tokens belonging to the `"reply"` key.
   - It chunks the reply by sentences (e.g. at punctuation `. ! ?`) and feeds them to `TTSManager`.
   - `TTSManager` generates speech (using Groq's Fritz-PlayAI) and streams the 16kHz audio chunks back to Android *while the LLM is still finishing the JSON structure*.
   - Once the full JSON is parsed, the backend dispatches the `ACTION` payload to the Android device.
6. **Native Android Execution**:
   - `CoreVoiceService` receives the `ACTION` JSON and passes it to `dispatchDeviceCommand`.
   - `DeviceOperatorService` uses Android's Accessibility APIs to execute the command (e.g., launching an app, turning on the flashlight, or scrolling the screen).
   - For `MULTI_ACTION` payloads, the service splits the actions and iterates over them with a safe 150ms delay.
7. **Reset**:
   - The backend sends a `RESPONSE_COMPLETE` signal, returning the Android app to the `IDLE` state to listen for the next wake word.

---

## 📂 File Structure

### Backend (`/backend/`)
- **`main.py`**: The core FastAPI application. Contains the logic for WebSocket audio streaming, streaming LLM routing (DeepSeek, Groq, Gemini), TTS chunking (`JSONReplyStreamer`, `TTSManager`), and tool definitions.
- **`run.py`**: Uvicorn server runner that starts `main.py` on port 8000.
- **`.env`**: Contains sensitive API keys for backend processing.

### Frontend (`/lib/`)
- **`main.dart`**: The Flutter UI entry point. Manages the Dashboard Screen, the glowing orb animation, state transitions (`idle`, `listening`, `processing`, `playing`), and permission checks.

### Android Native (`/android/app/src/main/kotlin/com/example/hey_bro/`)
- **`MainActivity.kt`**: Bridges Flutter UI and native Android services. Handles permission requests (Mic, Camera, Accessibility).
- **`CoreVoiceService.kt`**: The heart of the native background worker. Manages the state machine, handles audio focus, connects to the WebSocket, and coordinates local STT/TTS fallback engines.
- **`DeviceOperatorService.kt`**: Android Accessibility Service that parses `ACTION` and `MULTI_ACTION` payloads to globally tap buttons, open apps, swipe, and type text on behalf of the user.
- **`LocalCommandProcessor.kt`**: Parses offline transcripts to trigger local device commands without relying on the cloud.
- **`HeyBroPhraseWakeEngine.kt` / `WakeWordManager.kt`**: Manages the offline acoustic models that listen for the "Hey Bro" wake word.

---

## 🔑 API Keys & Environment Variables (Backend)

The backend (`main.py`) expects the following environment variables (defined in `/backend/.env`):

- `GROQ_API_KEY`: Fast LLM processing (`groq/compound`) and ultra-fast TTS (`Fritz-PlayAI`).
- `GEMINI_API_KEY`: Fallback routing (`gemini-2.5-flash`).
- `DEEPSEEK_API_KEY`: Primary decision engine (`deepseek-chat`).
- `OPENAI_LOCAL_API_KEY`: Optional key for a locally hosted Whisper instance.
- `VOSK_MODEL_PATH`: Local path to a Vosk STT model.

*Note: Android frontend uses a `PICOVOICE_ACCESS_KEY` inside `CoreVoiceService.kt` for wake word activation.*

---

## ⚙️ Core Functions & Keywords

### Backend Functions (`main.py`)
- `process_speech_command(audio_bytes, websocket, history)`: The primary orchestrator handling STT -> LLM -> TTS -> Dispatch.
- `get_llm_decision(transcript, history, tts_manager)`: LLM router. Tries DeepSeek, then Groq, then Gemini, and finally falls back to a deterministic rule-based matcher (`rule_based_fallback`).
- `_standardize_decision(parsed)`: Normalizes all LLM outputs into a standardized format.
- `TTSManager` & `JSONReplyStreamer`: Classes responsible for identifying "reply" tokens in an LLM stream, synthesizing sentences, and chunking PCM audio for real-time playback.

### Android Native Functions (`CoreVoiceService.kt` / `DeviceOperatorService.kt`)
- `onWakeWordDetected()`: Triggered when "Hey Bro" is heard, replying with a dynamic greeting.
- `dispatchDeviceCommand(payload)`: Receives a JSON action command from the backend or local rule engine.
- `executeCommand(payload)`: Executed by `DeviceOperatorService`, handles deep system actions and unrolls `MULTI_ACTION` payloads.

### Important Variables / Constants
- **`AssistantState` (Dart)**: Tracks UI states -> `idle`, `listening`, `processing`, `playing`, `disconnected`.
- **`SAMPLE_RATE = 16000`**: Crucial audio setting. Both Android's `AudioRecord` and the Backend's WAV processing must match this exact frequency to avoid pitched/garbled audio.

---

## 🛠️ Actions & Capabilities

The AI currently understands and executes the following core device actions via Android Accessibility APIs:
- **`openApp`**: Launch any installed application by name.
- **`sendWhatsApp`**: Send a direct message to a specific contact.
- **`controlHardware`**: Toggle flashlight, WiFi, Bluetooth, or adjust volume and brightness.
- **`setAlarm` / `setTimer`**: Set system clocks and countdowns.
- **`takePhoto` / `screenshot`**: Trigger the camera or capture the current screen.
- **`click` / `setText`**: Advanced accessibility actions to physically interact with any app UI on the screen.
- **`globalAction`**: Navigate `home` or go `back`.
- **`MULTI_ACTION`**: Combine any of the above operations sequentially (e.g. "Turn off the flashlight, go home, and open Spotify").
