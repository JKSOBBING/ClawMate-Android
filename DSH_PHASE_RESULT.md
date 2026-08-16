# DSH_PHASE_RESULT — ClawMate Android, Phase 7 (Voice interaction vertical slice)

## Worktree

- Path: `/opt/deepseek-harness/clawmate-phase7-voice`
- Branch: `master`
- Source of truth (reference only, not modified): `/root/mobiinfra-oh`
- This delivery was **built and tested in this worktree** (JDK 17.0.19 OpenJDK,
  Android SDK platform 34 / build-tools 34.0.0 at `/opt/deepseek-harness/android-sdk`,
  Gradle 8.9 at `/opt/deepseek-harness/gradle-dist/gradle-8.9`).
- Build state used: `GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
  `ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home` (discarded, never
  committed). Nothing was written to `/root/ClawMate-Android`; `/root/mobiinfra-oh`
  was not modified. All Phase 1–6 code is preserved.

## Commits

First commit (feature slice):
- Message: `Phase 7 voice interaction vertical slice`
- Hash: **`3372fee`** on `master`, created in this worktree.

The result-file update (this file, with the feature hash filled in) is then
committed as the final docs commit — both the feature commit and the
result-file commit are present in the branch history.

## Scope delivered (Phase 7)

### Typed voice domain models / status / metadata (`chat/voice/`)
- `VoiceInteractionTypes.kt` — port of `pages/chat/VoiceInteractionTypes.ets`:
  `VoiceInteractionStatus`, `ChatVoiceInputKind`, `ChatVoiceMetadataStatus`,
  `TtsVoiceRuntimeStatus`, `VoiceSessionSnapshot`, `ChatVoiceMetadata`,
  `appendVoiceTranscriptToInput`, `normalizeChatVoiceMetadata` /
  `optionalChatVoiceMetadata` / `chatVoiceMetadataPresent`, the truthful
  `createAsrVoiceMetadata` / `createTtsVoiceMetadata` /
  `createTtsVoiceRuntimeMetadata` / `createVoiceErrorMetadata` factories,
  `voiceStatusLabel`, `voiceReplayAvailable` / `voiceReplayGlobalBlockNeeded`,
  and the pure `VoiceSessionController` state machine.
- `VoiceTranscriptLogic.kt` — deterministic ASR transcript normalization
  (port of `HarmonySpeechRecognizerAsrProvider.ets` pure helpers): cumulative /
  committed merge, punctuation compaction, duplicate-final suppression.
- `VoiceOrchestratorLogic.kt` — voice-input start/stop generation guards.
- `VoiceTtsPresets.kt` — MiMO TTS voice whitelist + role-preset normalization.
- `PcmWav.kt` — PCM16 → WAV byte builder + chunk concat (port of `PcmWav.ets`).

### Android ASR adapter (`chat/voice/asr/`)
- `AndroidSpeechRecognizerAsrAdapter.kt` — around `android.speech.SpeechRecognizer`
  with explicit **permission / unavailable / error / cancel** states.
  **No hidden microphone access**: the adapter never requests RECORD_AUDIO; it
  checks `Context` permission and returns `PermissionDenied` until the UI has
  surfaced and the user granted the runtime permission. Partial/final transcripts
  only ever carry bytes from the platform recognizer; cumulative finals merged
  via `VoiceTranscriptLogic`; duplicate-final callbacks suppressed.

### Android TTS adapter (`chat/voice/tts/`)
- `AndroidTextToSpeechTtsAdapter.kt` — lifecycle-safe init (waits for
  `TextToSpeech.onInit` SUCCESS), speak / pause / stop / replay, typed
  unavailable/error states (`TtsInitState` / `TtsSpeakResult`), no permission
  required, `release()` frees the engine.
- `CloudMimoVoiceClient.kt` — **cloud MiMO streaming/TTS is NOT configured** on
  Android: `synthesizeSpeech` always returns `MimoVoiceResult.NotConfigured`,
  `streamingConfigured()` is `false`. It never fabricates audio and never claims
  the cloud is configured. The pure request-body shape is preserved for
  determinism only.

### Orchestration + Chat UI
- `ChatVoiceController.kt` — orchestrator wiring ASR + Android TTS into the chat
  session (port-scope of `ChatVoiceOrchestrator.ets`). Speaking a real **final**
  ASR transcript appends it to the editable chat input via
  `appendVoiceTranscriptToInput`; speak/pause/stop/replay drive the TTS adapter
  with lifecycle-safe init and typed states; `dispose()` releases ASR + TTS.
- `ChatViewModel` / `ChatViewModelFactory` — host the controller (voice state,
  permission/unavailable surfaces, speak/replay/stop actions); created with a
  `Context`.
- `ChatScreen` — mic button (requests `RECORD_AUDIO` **only on the mic tap**,
  then begins input), a hearing/transcribing banner with the live partial
  transcript, and speak/stop/replay controls on assistant bubbles. Existing
  streaming/agent/chat behavior is preserved.

## Truthfulness guarantees (asserted in tests)

- **No fake transcript/audio**: a transcript only enters the chat input after a
  real final ASR result; `CloudMimoVoiceTruthTest` asserts MiMO synthesis always
  returns NOT_CONFIGURED and never returns audio.
- **No hidden mic access**: ASR returns a typed `PermissionDenied` until
  RECORD_AUDIO is granted; the UI surfaces the requirement and requests it on
  the mic tap. Tested via pure unavailable/permission/error mappings.
- **Cloud MiMO not claimed configured**: `streamingConfigured() == false` and
  `synthesizeSpeech` → `NotConfigured` (asserted).
- **Replay guards** (streaming synthesizing blocks replay) asserted in
  `VoiceInteractionTypesTest`.

## Commands run & exit codes

Environment: `ANDROID_HOME=/opt/deepseek-harness/android-sdk`,
`ANDROID_SDK_ROOT=/opt/deepseek-harness/android-sdk`,
`GRADLE_USER_HOME=/opt/deepseek-harness/.gradle-user-home`,
`ANDROID_USER_HOME=/opt/deepseek-harness/.android-user-home`.
Gradle binary: `/opt/deepseek-harness/gradle-dist/gradle-8.9/bin/gradle`.

| # | Command | Result |
| --- | --- | --- |
| 1 | `gradle --no-daemon --max-workers=1 :app:compileDebugKotlin` | **SUCCESS** (exit 0) — early compile check during development |
| 2 | `gradle --no-daemon --max-workers=1 :app:testDebugUnitTest --tests 'com.clawmate.app.chat.voice.*'` | **SUCCESS** (exit 0) — 55 Phase-7 voice tests green |
| 3 | `gradle --no-daemon --max-workers=1 :app:assembleDebug :app:testDebugUnitTest` | **SUCCESS** (exit code `0`) — full build + **254** JVM unit tests (0 failures / 0 errors) |
| 4 | `gradle --no-daemon --max-workers=1 :app:connectedAndroidTest` | **not run** — no emulator/device available (same as Phases 1–6). Instrumented ASR/TTS voice tests are therefore documented (in code + docs) as unavailable without a device. |

## Test count

- Total JVM unit tests green: **254** (199 prior Phases 1–6 + **55 new Phase-7
  voice tests**); **0 failures / 0 errors** across `:app:testDebugUnitTest`.
- New Phase-7 test classes:
  - `VoiceInteractionTypesTest` — metadata normalization (kind/status/provider/
    transcript/confidence clamp), transcript append-to-input, replay guards,
    status labels, ASR metadata factory.
  - `VoiceSessionControllerTest` — idle / begin-listening / partial /
    complete / save-to-idle / pause/resume / fail transitions and busy semantics.
  - `VoiceTranscriptLogicTest` — cumulative-final replacement, clause append,
    punctuation compaction, partial merge, duplicate-final suppression.
  - `VoiceOrchestratorLogicTest` — voice-input start/stop generation guards.
  - `VoiceTtsPresetsTest` — MiMO voice whitelist + normalization, role presets.
  - `VoiceUnavailableMappingTest` — unavailable/permission/error mapping into
    typed pure statuses (error metadata, TTS synthesizing→ready, error labels).
  - `PcmWavTest` — PCM16→WAV header + chunk concat correctness.
  - `CloudMimoVoiceTruthTest` — cloud MiMO always NOT_CONFIGURED / never faked.

## Changed files (this phase)

```
app/src/main/java/com/clawmate/app/chat/ui/ChatViewModel.kt          (modified — hosts ChatVoiceController, voice state/actions)
app/src/main/java/com/clawmate/app/chat/ui/ChatViewModelFactory.kt   (modified — wires ChatVoiceController(context))
app/src/main/java/com/clawmate/app/chat/ui/ChatScreen.kt             (modified — mic/permission, listening/transcript, speak/stop/replay)
app/src/main/java/com/clawmate/app/chat/voice/VoiceInteractionTypes.kt      (new)
app/src/main/java/com/clawmate/app/chat/voice/VoiceTranscriptLogic.kt      (new)
app/src/main/java/com/clawmate/app/chat/voice/VoiceOrchestratorLogic.kt    (new)
app/src/main/java/com/clawmate/app/chat/voice/VoiceTtsPresets.kt           (new)
app/src/main/java/com/clawmate/app/chat/voice/PcmWav.kt                    (new)
app/src/main/java/com/clawmate/app/chat/voice/ChatVoiceController.kt       (new)
app/src/main/java/com/clawmate/app/chat/voice/asr/AndroidSpeechRecognizerAsrAdapter.kt (new)
app/src/main/java/com/clawmate/app/chat/voice/tts/AndroidTextToSpeechTtsAdapter.kt      (new)
app/src/main/java/com/clawmate/app/chat/voice/tts/CloudMimoVoiceClient.kt               (new)
app/src/test/java/com/clawmate/app/chat/voice/*Test.kt                  (8 new test classes, 55 tests)
README.md                                                              (modified)
MIGRATION_STATUS.md                                                    (modified)
DSH_PHASE_RESULT.md                                                    (rewritten — this file)
```

No Phase 1–6 source file other than the three chat-ui files listed above was
modified; existing streaming/agent/chat behavior is preserved.

## Limitations & platform blockers

- **Instrumented voice tests not run** — `connectedAndroidTest` requires a device
  or emulator; none is available in this worktree. `assembleDebug` and all
  **254** JVM unit tests pass in this worktree.
- **Cloud MiMO streaming/TTS voice is NOT configured on Android.** There is no
  voice-TTS baseUrl/API-key setting yet, so cloud streaming voice is exposed only
  through the explicit NOT_CONFIGURED interface and never claimed or faked;
  output voice on-device uses Android `TextToSpeech`.
- **Digital avatar** remains DISABLED / NOT_CONFIGURED (the Home avatar section
  from Phase 6 is unchanged; this phase is voice interaction only).
- **Audio bytes & permissions** are bounded: ASR/TTS resources are released in
  `dispose()`/`release()` (`ChatViewModel.onCleared`), playback is stopped on
  toggle/stop, and RECORD_AUDIO is only requested when the user taps the mic.
- Benign Kotlin warnings remain (a few unused params in pre-existing scaffolding
  and the intentionally-signature-only params of the NOT_CONFIGURED cloud seam),
  non-fatal; no compilation errors.

## Next phase

1. **Set up a real Android voice-output provider** when a cloud MiMO/config seam
   lands (or keep the on-device TextToSpeech path) — wire the currently explicit
   NOT_CONFIGURED `CloudMimoVoiceClient` behind an actual configured client once
   voice-TTS endpoints/keys exist, preserving the typed metadata/replay contract.
2. **Digital avatar real generator** — replace the honest DISABLED avatar section
   with a real image-generation client gated by the existing Settings avatar keys.
3. Optionally instrumented ASR/TTS smoke tests once a device/emulator is
   available (documented as unavailable without a device).
