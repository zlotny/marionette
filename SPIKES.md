# Spikes (Phase 0)

Go/no-go: **GO** (user decision, 2026-10-09). Wake word: detector C. Threshold tuning and battery measurement move to the real app.

User decisions:

1. Hands-free navigation from a locked phone uses Extend Unlock with the intercom as a trusted device (V6).
2. The intercom stays out of call mode during wake word listening (normal intercom audio and music). In the helmet, the wake path is the intercom assistant button or "Hey Cardo" (`VOICE_COMMAND`).
3. The custom wake word is for the phone mic only. Android Auto is parked.
4. The user speaks Spanish 90 to 100% of the time. Wake detection must be language independent. Command STT must be multilingual (Whisper, per `SPEC.md`).

## S1. Start Google Maps navigation from a locked phone

Variants are in `spike/src/main/java/dev/marionette/spike/launch/NavLauncher.kt`. V6 adds a keyguard dismiss request, which passes with no PIN only when a trust agent (Smart Lock trusted device) keeps the phone unlocked.

### Emulator (Android 17, sdk_gphone64_arm64, PIN set, screen off)

| Variant | Maps activity created | Navigation started while locked | Unlock prompt | Nav after unlock (fallback) |
|---|---|---|---|---|
| V1 assistant activity + trampoline | yes, behind keyguard | no | no (screen turns on) | yes |
| V2 session startActivity | yes, behind keyguard | no | no | yes |
| V3 full-screen intent | yes, behind keyguard | no | no | yes |
| V4 overlay + startActivity | yes, behind keyguard | no | no | yes |
| V5 service startActivity | yes, behind keyguard | no | no | yes |
| V6 dismiss keyguard | no (dismiss cancelled) | no | yes, PIN bouncer | yes |

No background-launch block happens: the assistant role exempts the app. Maps processes the navigation intent only when its activity resumes. After unlock, Maps resumes without navigating, so the fallback resends the intent on `ACTION_USER_PRESENT`.

### Phone

| Variant | Maps started | Asked for unlock | Guidance audio in headphones | Notes |
|---|---|---|---|---|
| V1 | activity behind keyguard, no navigation | no | no | nav starts after unlock (fallback) |
| V2 | activity behind keyguard, no navigation | no | no | same |
| V3 | activity behind keyguard, no navigation | no | no | same |
| V4 | activity behind keyguard, no navigation | no | no | same |
| V5 | activity behind keyguard, no navigation | no | no | same |
| V6 (no trusted device) | no | yes, PIN bouncer | no | dismiss cancelled |
| V6 (intercom as Extend Unlock trusted device) | **yes, navigation running** | **no** | **yes** | keyguard dismissed with no PIN; Maps holds `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` focus |

Phone model: Pixel 10 Pro. Android version: 17 (API 37). Intercom: Cardo (Bluetooth name "Andres").

Extend Unlock path on this phone: Settings > Security & privacy > More security & privacy > Extend Unlock > Trusted devices.

The intercom's assistant trigger arrives as `android.intent.action.VOICE_COMMAND`, handled by `VoiceCommandActivity`, which fired V6, and Maps navigated (tested with the keyguard not showing).

## S2. Custom wake word

### Detector A: sherpa-onnx KWS

Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (int8), sherpa-onnx 1.13.8. The phrase is tokenized on the device; it changes with no restart.

### Emulator (synthesized speech, `DEBUG_AUDIO`)

- "Okay. Hey Marionette. Take me home." gives 1 detection.
- "The marionette show starts at nine. Hey Mary, are you on the net?" gives 0 detections.
- Threshold 0.25, boost 1.5. Model load 250 ms.

### Phone

Phrase "Hey Marionette", 5 utterances per session, recordings in `build/captures/` (local, gitignored).

| Mic | On device (boost 1.5, threshold 0.25) | Best offline sweep (boost 3 to 4, threshold 0.05) |
|---|---|---|
| Phone mic, near mouth | 1 of 5 | 3 of 5 |
| Intercom mic (Bluetooth SCO) | 0 of 5 | 2 of 5 |

- The intercom mic is narrowband: 0.3% of energy above 4 kHz, against 10.3% on the phone mic. SCO capture works only with `MODE_IN_COMMUNICATION`, `VOICE_COMMUNICATION` source, `setCommunicationDevice`, and the `MODIFY_AUDIO_SETTINGS` permission. The intercom stayed in normal mode during capture.
- A 20M English streaming ASR transcribed the phone session as "Y MADION HEY MARIONETTE HAY MARIONETTE HAY MARIANNET HEY MARIONET". Pronunciation varies between spellings. Registering 6 spelling variants as keywords did not improve detection.
- S2 targets (90% detection) fail with this model. Not yet measured: false accepts per hour, battery per hour.
- With enrolled spelling variants: 0 detections.

### Detector B: streaming ASR + fuzzy match

Model: sherpa-onnx streaming zipformer en-20M. Letter fuzzy match against enrolled transcripts.

- User verdict: "more or less decent".
- The English ASR transcribes "Hey Marionette" as "A MODUMENT", "HE MONUMENT" and similar. Common words match these transcripts, so false accepts are likely. English only.

### Detector C: query-by-example (lead candidate)

openWakeWord `melspectrogram.onnx` + `embedding_model.onnx` (96-d vector every 80 ms). Enrolled utterances become templates. Subsequence DTW with cosine cost. Language independent, speaker biased, no training, offline. Code: `audio/SpeechEmbedder.kt`, `audio/AcousticWakeDetector.kt`. Offline tool: `tools/qbe_eval.py`.

- Offline, on the user's recordings (21-utterance enrollment, 3 positive sessions): threshold 0.10 to 0.16 gives 26 to 27 of 29 detections. Threshold 0.14: 27 of 29.
- False accepts on 0.8 min of synthetic EN/ES speech (`say`): 0.
- Phone, 22 templates, threshold 0.14: 10 of 10 detections (scores 0.095 to 0.139). With a loud video playing for 1.2 min: the one spoken phrase detected (0.122), 0 false triggers. Best non-phrase score was 0.152, so the margin above the threshold is small.
- Not yet measured: a long false-accept run (1 h of talk and media) and battery per hour.
- `onnxruntime-android` 1.31.0. `scripts/fetch-deps.sh` strips the ORT 1.28.2 copy from the sherpa AAR. Sherpa's JNI needs `OrtGetApiBase@VERS_1.28.2`, so A and B no longer load (Maven has no ORT 1.28.2). The spike logs `KWS/ASR unavailable` and enrollment records with no per-utterance beep.

## S3. Android Auto microphone routing

| Check | Result |
|---|---|
| Input device while Android Auto connected (S2 card, "Input") | not tested |
| Wake word works through the car mic | not tested |
| Maps navigation intent shows on the car screen | not tested |

## Phone test protocol

1. Install: `./gradlew :spike:installDebug` with the phone on adb.
2. Readiness card: grant mic and notifications, set Marionette Spike as the default digital assistant, allow draw over apps and full-screen intents. Start the service.
3. S1: pick a variant, set the delay to 15 s, set a destination, tap Arm, lock the phone, put on the headphones. Note what happens. Unlock and confirm the fallback starts navigation. Repeat per variant.
4. S1 V6 with Smart Lock: add the helmet as a trusted device (Settings > Security > Extend Unlock / Smart Lock), connect it, repeat V6.
5. S1 by assist gesture: lock the phone, long-press power. The session fires the selected variant.
6. S2 detector C: Bluetooth mic off. Enroll about 20 utterances. Select C, Listen, say the phrase 10 times. Then listen 2 to 5 min of normal talk and media; count false triggers. The log prints `S2 C best score` every 5 s.
7. S2 + S1: turn on "Detection fires the S1 variant", lock the phone, say the phrase.
8. Pull the log: `adb shell run-as dev.marionette.spike cat files/spike-log.txt`.
