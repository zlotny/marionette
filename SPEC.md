# Marionette

Hands-free Android voice assistant. A custom wake word starts it. It sends each command to an LLM that the user chooses. The LLM operates the phone through tools that the app defines and executes.

Primary use: phone locked in a pocket, user on a motorcycle or in a car, Bluetooth headphones on.

## 1. Product principles

1. **Install, run the wizard, use it.** No Tailscale, no relay server, no MCP server, no extra service. The only external dependency is the LLM endpoint the user enters.
2. **Backend-agnostic.** Any LLM API that accepts client-defined tools works. The app owns the tool loop.
3. **Hands-free first.** Every core feature works with the screen off and the phone locked. A feature that needs the screen is a fallback, not a core path.
4. **Custom wake word.** The user types any phrase. No vendor wake word, no per-word model training.
5. **Personal app.** Distribution is sideload (GitHub releases) or F-Droid. Play Store policy does not constrain the design.

## 2. Phase 0: feasibility spikes (go/no-go)

Run these on the **real phone** before building features. Record results in `SPIKES.md`. If S1 fails in every variant, the hands-free agent concept does not work on current Android, and the project stops or changes scope.

### S1. Start Google Maps navigation from a locked phone

Setup: app is the default assistant (`VoiceInteractionService`). Phone screen off, secure lock (PIN or fingerprint) on. Bluetooth headphones connected. A foreground service with type `microphone` triggers the launch.

Goal: Maps starts turn-by-turn navigation and voice guidance plays in the headphones, with no unlock.

Try in this order, stop at the first that works:
1. `VoiceInteractionSession.startAssistantActivity()` with an activity that sets `setShowWhenLocked(true)` and `setTurnScreenOn(true)`, which then starts the Maps intent.
2. Start the Maps intent directly from the voice interaction session.
3. Full-screen-intent notification (`USE_FULL_SCREEN_INTENT`) that opens a show-when-locked activity, which then starts the Maps intent.
4. `SYSTEM_ALERT_WINDOW` with a visible overlay, then start the Maps intent.
5. Plain `startActivity` from the foreground service.

For each variant, record: did Maps start, did it ask for unlock, did guidance audio play, Android version, phone model.

Degraded fallback if all fail: Marionette says "Unlock to start navigation". It listens for `ACTION_USER_PRESENT` and starts navigation right after the unlock.

### S2. Custom wake word quality

Engine: sherpa-onnx keyword spotter (KWS). It accepts keywords at runtime: `KeywordSpotter.createStream(keywords)`. Reference: `android/SherpaOnnxKws` in `k2-fsa/sherpa-onnx`.

Measure with the user's chosen phrase:
- Detection rate: 20 utterances at normal voice, in a quiet room, and with road or wind noise played back. Target 90% or more.
- False accepts: 1 hour of podcast or conversation audio. Target 1 or fewer per hour.
- Battery: drain per hour with the screen off while listening. Target 3% or less per hour.
- Phrase language: test an English phrase and a Spanish phrase. The available KWS models are English or Chinese-English. A Spanish phrase maps to English BPE tokens, so measure it separately.

Tune `keywordsThreshold` and `keywordsScore`. If the targets fail, evaluate openWakeWord with an on-device fine-tune as the alternative.

### S3. Android Auto microphone routing

With Android Auto connected, check which mic the app's `AudioRecord` captures (car or phone) and whether the wake word still works. Check that a Maps navigation intent shows on the car screen.

## 3. Architecture

```
[Mic] -> WakeWordEngine (sherpa KWS, always on, foreground service)
      -> VAD + STT (captures one utterance)
      -> AgentLoop
           builds: system prompt + context block + history + tool schemas
           calls:  LlmBackend (streaming)
           on tool_call: ToolRegistry.execute() -> result -> loop
           on final text: TTS
      -> TTS (ducks other audio, routes to Bluetooth)
      -> follow-up window: listen 6 s without wake word
```

Android components:
- `MarionetteVoiceInteractionService` + `VoiceInteractionSessionService`: default assistant role. The long-press of power or home opens Marionette. It also gives the background-start exemptions that S1 depends on.
- `ListenerService`: foreground service, `foregroundServiceType="microphone"`, persistent notification. Runs the wake word and the voice pipeline. Starts from the UI or from the assistant session, never from `BOOT_COMPLETED` (Android 14+ forbids microphone services there).
- `MarionetteNotificationListener`: `NotificationListenerService` for reading and replying to notifications.
- `MarionetteAccessibilityService`: last-resort UI automation. Disabled by default.
- One `AudioRecord` owner. The wake word engine and the STT share one capture stream. Two components that open the mic at the same time fail ("microphone in use"). This is a known failure in comparable apps.

Stack: Kotlin, Jetpack Compose, coroutines, kotlinx.serialization, OkHttp (SSE streaming), DataStore for settings, Android Keystore to encrypt API keys, sherpa-onnx Android AAR. Use the latest stable versions. Target the latest SDK. minSdk 29.

## 4. LLM backends

### Contract

The core contract is OpenAI Chat Completions with function tools:
- Request: `messages`, `tools` (`type: "function"`), `tool_choice: "auto"`, `stream: true`.
- Response: `tool_calls` with `id`, `function.name`, `function.arguments`.
- Tool results go back as `role: "tool"` with `tool_call_id`.
- Loop until the model returns text with no tool calls. Cap at 8 tool rounds per turn.

`LlmBackend` interface with adapters:

| Provider | Adapter | Wizard input |
|---|---|---|
| OpenAI | Chat Completions | API key, model |
| Anthropic | Native Messages API (`tool_use` / `tool_result` blocks) | API key, model |
| OpenRouter | Chat Completions | API key, model |
| Google Gemini | Chat Completions (OpenAI-compatible endpoint) | API key, model |
| Custom OpenAI-compatible | Chat Completions | Base URL, optional key, model |

"Custom" covers Ollama, LM Studio, llama.cpp server (on the phone or on a PC) and OpenClaw.

**OpenClaw:** its `/v1/chat/completions` and `/v1/responses` accept client function tools and return `tool_calls`. Its own server-side tools and skills run on the gateway. The user enables the HTTP endpoint in OpenClaw. Reference: `docs/gateway/openai-http-api.md` in `openclaw/openclaw`.

**Hermes Agent: not supported.** Its API server ignores `tools` in the request. `/v1/capabilities` reports `"tool_execution": "server"`. Client tools wait for a planned "split-runtime mode". The wizard tool-call test (section 5) detects support if Hermes adds it.

## 5. Setup wizard

1. Welcome. One screen on what Marionette does and what data goes to the LLM.
2. Backend: pick a provider, enter the key or URL, pick a model from the provider's model list where the API offers one.
3. Tool-call test: send one request with a `ping` tool and a prompt that requires it. Pass: the response has a valid `tool_call` for `ping`. Fail: show "This backend cannot run phone tools" and block the next step.
4. Wake word: the user types a phrase. The app tokenizes it with the KWS model's tokens. The user says it 3 times. The app reports the detections and adjusts the threshold.
5. Permissions, each with one sentence on why: microphone, location (precise, background), notifications, contacts, phone, SMS, calendar, activity recognition, notification listener access, default assistant role.
6. Voice: STT engine, TTS voice, test sentence.
7. Done. Start the listener service.

## 6. Voice pipeline

- **Wake word:** sherpa-onnx KWS, runtime keyword from the wizard. Earcon on detection.
- **VAD:** Silero VAD through sherpa-onnx. End of speech after 700 ms of silence, configurable.
- **STT,** user choice:
  1. On-device Whisper (multilingual) through sherpa-onnx. Default.
  2. Android `SpeechRecognizer.createOnDeviceSpeechRecognizer` (Android 13+).
  3. Cloud transcription when the backend provider offers one (OpenAI).
- **TTS:** Android `TextToSpeech`. Request audio focus with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Play through the active Bluetooth device. Keep replies short: the system prompt limits spoken replies to 2 sentences.
- **Barge-in:** the wake word interrupts TTS.
- **Session:** history resets after 5 minutes of no interaction.

## 7. Context block

The app adds a context block to the system message of every request, so simple commands need no extra tool round trip. Each field has an on/off toggle in Settings.

```
time: 2026-10-09T18:42:10+02:00 (Thursday)
locale: es-ES
location: 40.4168,-3.7038 accuracy 12m, Madrid, Calle Mayor
speed: 54 km/h, heading 120
activity: in_vehicle (confidence 92)
battery: 64%, not charging
audio_output: Bluetooth "Cardo Packtalk"
android_auto: false
screen: off, locked
```

Sources: `FusedLocationProviderClient`, `Geocoder` for the place name, Activity Recognition API, `BatteryManager`, `AudioManager` output device, `UiModeManager` car mode for Android Auto, `KeyguardManager` and `PowerManager` for the screen state.

Data that costs more or is private (calendar, notifications, contacts) stays behind tools.

## 8. Tools

### Priority tiers

1. **System APIs that work with the phone locked.** Preferred.
2. **Intents** to other apps.
3. **Notification actions** (reply, mark as read).
4. **Accessibility,** only when the screen is on and unlocked. If the phone is locked, the tool returns `{"error":"needs_unlock"}`. Marionette then says "Unlock the phone for that".

### Registry

Each tool is a Kotlin class with `name`, `description`, a JSON Schema for its parameters, a `requiresConfirmation` flag and `suspend fun execute(args): JsonObject`. The registry produces the `tools` array for every backend adapter. Every tool returns structured JSON, never prose.

### Tool set

| Tool | Tier | Implementation |
|---|---|---|
| `find_places(query, near?, radius_m?, limit?)` | 1 | See "Place search" below. Returns name, address, lat, lng, distance_m, sorted by distance. |
| `navigate(lat, lng, label, mode)` | 2 | `google.navigation:q=<lat>,<lng>&mode=<d\|w\|b>`. Launch path from spike S1. |
| `stop_navigation()` | 2 | Maps intent or media/notification action, per spike result |
| `call(contact_or_number)` | 1 | `TelecomManager.placeCall` |
| `send_sms(contact_or_number, text)` | 1 | `SmsManager`. Requires confirmation. |
| `search_contacts(query)` | 1 | Contacts provider |
| `read_notifications(app?, limit?)` | 3 | Notification listener |
| `reply_notification(key, text)` | 3 | `RemoteInput` reply action. Requires confirmation. |
| `media_control(action, query?)` | 1/2 | `MediaController` from active sessions. `query` uses `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`. |
| `set_alarm(time, label?)` / `set_timer(seconds, label?)` | 2 | `AlarmClock` intents with `EXTRA_SKIP_UI` |
| `create_event(title, start, end?, location?)` / `list_events(from, to)` | 1 | Calendar provider |
| `device_setting(name, value)` | 1 | Volume, Do Not Disturb, flashlight, Bluetooth |
| `open_app(name)` | 2 | Launcher intent. Returns `needs_unlock` when locked. |
| `ui_read()` / `ui_act(action, target)` | 4 | Accessibility node tree. Tap, type, scroll, back. |

### Place search

`find_places` needs no API key by default:
- Default: OpenStreetMap Overpass API. Query by `name`/`brand` regex and by amenity type within `radius_m` (default 10 km) of the current location. Compute `distance_m` on the device with the Haversine formula.
- Fallback for addresses and free text: Nominatim. One request per second at most. Send a descriptive `User-Agent`.
- Optional: a Google Places API key in Settings replaces OSM when set.

### Confirmation

Tools with `requiresConfirmation` read the action aloud ("Send 'I'm 10 minutes late' to Ana?") and wait for a spoken yes or no. The user can disable confirmation per tool.

### Safety

- Accessibility block list: banking, payment and 2FA apps by default.
- Action log: every tool call with arguments, result and timestamp. Viewable in the app.

## 9. System prompt rules

The prompt is a versioned asset file, `assets/prompts/system.md`. It contains these rules:

- You are Marionette, a voice assistant that runs on the user's Android phone. Your replies are spoken. Keep them to 2 short sentences. No lists, no markdown, no URLs.
- Use the context block. Do not ask for data that it already contains.
- Act, do not ask, when the intent is clear.
- Places: for "the nearest X" or "take me to X", call `find_places` first and pick the nearest result. Then call `navigate` with its coordinates. Say the name and the distance.
- Transport mode: default `d` (driving). The user rides a motorcycle or drives. Never assume bicycle. If the destination is 800 m or closer, ask "It is N meters away. Walk or drive?" and use `w` for walk.
- If a tool returns `needs_unlock`, tell the user to unlock the phone.
- Confirm messages and calls to contacts before sending, unless confirmation is off.
- Reply in the user's language (`locale` in the context block).

## 10. Development loop (emulator)

Use an Android emulator with a **Google Play** system image, so Google Maps and other apps can be installed.

- Build and install: `./gradlew installDebug`
- Logs: `adb logcat -s Marionette:*`
- Screenshot: `adb exec-out screencap -p > shot.png`
- UI tree: `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`
- Input: `adb shell input tap X Y`, `adb shell input text "..."`
- Fake location: `adb emu geo fix <lng> <lat>`
- Fake SMS and calls: `adb emu sms send <number> <text>`, `adb emu gsm call <number>`
- Lock the screen: `adb shell input keyevent KEYCODE_SLEEP`. Set a PIN on the emulator to test the locked paths.
- Host mic: emulator Extended controls > Microphone > "Virtual microphone uses host audio input".

Debug-only entry points (debug build only):
- Text command, skips wake word and STT: `adb shell am broadcast -a dev.marionette.DEBUG_COMMAND --es text "take me to the nearest McDonald's"`
- Audio file into the pipeline, for STT and wake word tests: `adb shell am broadcast -a dev.marionette.DEBUG_AUDIO --es path /sdcard/test.wav`
- Every turn writes a JSON trace (prompt, context block, tool calls, results, reply, timings) to logcat and to the app's files directory.

Unit tests: backend adapters against recorded fixtures, tool argument parsing, Haversine, context block builder. Instrumented tests: each tool on the emulator.

## 11. Milestones

| # | Scope | Done when |
|---|---|---|
| 0 | Spikes S1, S2, S3 on the real phone | `SPIKES.md` has results and a go/no-go |
| 1 | Wizard (backend + tool-call test), agent loop, `find_places`, `navigate`, `set_timer`, context block, debug text command | Acceptance A1, A2, A6 pass on the emulator |
| 2 | Voice: wake word, VAD, STT, TTS, foreground service, default assistant role | A3, A4 pass on the emulator. A3 passes on the real phone. |
| 3 | Tier 1 to 3 tools, confirmation, action log | A5, A7, A8 pass |
| 4 | Accessibility tools, block list | A9 passes |
| 5 | Android Auto checks, battery tuning, release build | A10 passes. Battery target from S2 holds. |

## 12. Acceptance criteria

Each criterion names where it must pass: **E** = emulator, **P** = real phone.

- **A1 (E):** Wizard with an OpenAI key and with an Anthropic key: the tool-call test passes. With a backend that ignores tools: the test fails and shows the message.
- **A2 (E):** Location fixed at a known point. Debug command "take me to the nearest McDonald's". Trace shows `find_places` before `navigate`, `navigate` uses the coordinates of the nearest result, `mode=d`. Maps opens in navigation. Spoken reply has the name and distance.
- **A3 (E, P):** Same as A2 by voice, phone locked, screen off, from the wake word. On P: guidance audio plays in Bluetooth headphones and no unlock is needed (or the S1 fallback runs, if S1 failed).
- **A4 (E, P):** The custom wake word typed in the wizard triggers the assistant. Changing the phrase in Settings takes effect with no restart.
- **A5 (E):** "Text Ana I'm 10 minutes late": confirmation is spoken, "yes" sends the SMS, the action log has the call.
- **A6 (E):** Location 300 m from a McDonald's. "Take me to McDonald's": the assistant asks walk or drive. "Walk" gives `mode=w`.
- **A7 (E):** "Read my last WhatsApp messages" with test notifications posted: the reply reads them. "Reply OK": the reply action fires.
- **A8 (E):** "Pause the music" with a media session active: playback pauses.
- **A9 (E):** Phone locked, a command that needs `ui_act`: the reply asks to unlock. Phone unlocked: the action runs. A blocked banking app: the tool refuses.
- **A10 (P):** Android Auto connected: the wake word works, and navigation shows on the car screen.
- **A11 (E, P):** The context block in the trace matches the device state (location, battery, screen, audio output).

## 13. Reference projects

Read for patterns. Do not fork.
- `k2-fsa/sherpa-onnx`, `android/SherpaOnnxKws`: runtime keywords on Android.
- `yuga-hashimoto/openclaw-assistant`: `VoiceInteractionService` registration and default-assistant setup.
- `Codename-11/hermes-relay`: sherpa KWS wake word in a Kotlin app. Its issue #681 shows the shared-microphone failure that section 3 avoids.
- Android docs: "Restrictions on starting activities from the background", "Foreground service types", "Google Maps Intents for Android".
