# GEMINI.md - Hey Bro AI Architecture & Governance Rules

## Architecture: Zero-Server Edge-First
Hey Bro v2 is a 100% serverless, edge-first voice assistant designed for Android (specifically optimized for Vivo X200t with Android 14+).

### Key Directives & Rules
1. **No External Backend Server**: All audio streaming and conversational intelligence communicate directly from the Android native layer to Google's Gemini Multimodal Live API over WebSockets (`wss://generativelanguage.googleapis.com/...`). No Render, Node, or Python middleware servers are used.
2. **Verified Models**:
   - Live Bidirectional Voice + Tool Calling: `models/gemini-3.8-live` (with fallback to `models/gemini-2.5-flash-native-audio-latest`).
   - Offline / REST Text Fallback: `gemini-3.8-flash` / `gemini-3.5-flash`.
   - Never use retired model names (`gemini-2.0-flash-exp`, `gemini-2.0-flash`, `gemini-2.5-flash`).
3. **Local Storage & Memory**:
   - All conversation logs, custom user shortcuts, and command history are persisted on-device in SQLite (`hey_bro.db`) via `sqflite`.
   - API keys and device preferences are securely stored in Android Preferences.
4. **Wake & Voice Stack**:
   - Primary Wake Trigger: System `android.intent.action.ASSIST` intent (Power-button long press / corner swipe) + `HeyBroPhraseWakeEngine` + `SileroVad`.
   - Redundant wake engines are forbidden to prevent microphone resource contention and battery drain.
5. **Deterministic Fast Path**:
   - Spoken phrases are checked against `LocalCommandProcessor` and SQLite user shortcuts first (<50ms).
   - If a local command or shortcut matches (e.g., flashlight, volume, alarm, direct app launch), execute immediately via `DeviceOperatorService` without network round-trip.
