# DSH_PHASE_RESULT — ClawMate Android, Phase 31 (Android NFC profile-knock-share adapter)

## Commit

- Working against base `dd49fc4` (Phase 30). The changes are exported as a real
  diff in `/root/phase31.patch` (paths `a/ClawMate-Android/…` `b/ClawMate-Android/…`).

## Context / provenance

Phase 31 migrates the Harmony **一碰分享** (knock-share) *capability* in
`ProfileKnockShareService.ets` + `module.json5`/`profile/utd.json5` to a real
**Android NFC (NDEF)** adapter, closing the last A4 gap. It is a genuine
`NfcAdapter` implementation: honest availability, explicit user-triggered
send/receive sessions, NDEF-MIME + UTF-8/JSON + schema/version + app/type
validation **delegated to the existing `ProfileShareImporter`**, a ≤512 KiB
payload cap both ways, and duplicate-safety across lifecycle rebuilds. No
simulation, no fake device state, no adb/shell/hdc, no coordinate GUI, no
background auto-scan.

The Harmony code is read-only reference (`/root/mobiinfra-oh` was **not**
modified).

## Files changed

New production files (`app/src/main/java/com/clawmate/app/profile/share/nfc/`):

- `ProfileKnockNfcTypes.kt` — status enum (NOT_SUPPORTED / DISABLED / READY /
  SENDING / RECEIVING / SUCCESS / CANCELLED / ERROR), typed error enum,
  `ProfileKnockReceivedOutcome`, `NfcProfileShareGateway` and
  `ProfileKnockDedupeStore` boundaries.
- `ProfileKnockEnvelopeCodec.kt` — pure envelope build/encode (bounded, ≤512 KiB,
  canonical UTD/MIME/`.mobi-profile` contract aligned with Harmony) + incoming
  NDEF validation (MIME/type, size, UTF-8/JSON, schema/version, app/type via the
  importer) + stable message signature.
- `ProfileKnockNfcStateMachine.kt` — pure JVM state machine: availability gating,
  READY→SENDING/RECEIVING, send-write handoff, receive-validate→dedupe→import,
  CANCELLED, typed ERROR, reset/retry.
- `NfcProfileShareViewModel.kt` — the typed ViewModel boundary; exposes
  `uiState: StateFlow<UiState>` and only explicit actions.
- `NfcProfileShareScreen.kt` — Compose screen; real state + explicit controls only.
- `NfcProfileShareActivity.kt` — lifecycle-safe host (reader mode enabled only
  while RESUMED and an explicit SEND/RECEIVE session is armed, released in
  `onPause`/`onDestroy`; never background or on launch).
- `device/AndroidNfcProfileShareGateway.kt` — real NfcAdapter gateway (reader-mode
  receive via `onTagDiscovered`; NDEF write via `Ndef`/`NdefFormatable`).
- `device/PrefsProfileKnockDedupeStore.kt` — persistent duplicate-signature store.

Modified files:

- `app/src/main/AndroidManifest.xml` — `android.permission.NFC` + **non-required**
  `android.hardware.nfc` feature (no-NFC devices still install); registers
  `NfcProfileShareActivity` (not exported, singleTask).
- `app/src/main/java/com/clawmate/app/settings/ui/SettingsScreen.kt` — explicit
  Settings entry card `一碰分享（NFC）` (launch on tap; never auto-starts).

## New test file

- `app/src/test/java/com/clawmate/app/profile/share/nfc/ProfileKnockNfcTest.kt` —
  **21 focused, offline JVM tests** (fake gateway + fake dedupe + fake repo, no
  device/network): payload build + size cap (oversize refused at encode),
  NDEF-MIME / protocol + malformed / oversize / unsupported-version / non-UTF-8
  validation, state-machine transitions, no-NFC / disabled / cancelled / send
  write-error, duplicate dedupe (incl. across a machine rebuild = lifecycle
  restart), and "importer-not-bypassed" merge preservation.

## Verification (serial, `--no-daemon --max-workers=1`, offline)

Toolchain: JDK 17.0.19, Android SDK platform 34 at `/opt/deepseek-harness/android-sdk`
(via `local.properties` `sdk.dir`), Gradle 8.9.

1. Focused new tests:

```
./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest \
    --tests "com.clawmate.app.profile.share.nfc.ProfileKnockNfcTest"
```
Result: `BUILD SUCCESSFUL` — `ProfileKnockNfcTest` **tests=21, failures=0, errors=0**
(`app/build/test-results/testDebugUnitTest/TEST-com.clawmate.app.profile.share.nfc.ProfileKnockNfcTest.xml`).

2. Full unit suite:

```
./gradlew --offline --no-daemon --max-workers=1 :app:testDebugUnitTest
```
Result: `BUILD SUCCESSFUL` — **110 test classes / 832 tests, 0 failures, 0 errors, 0 skipped**
(aggregated from the `app/build/test-results/testDebugUnitTest/TEST-*.xml` reports).

3. Build:

```
./gradlew --offline --no-daemon --max-workers=1 :app:assembleDebug
```
Result: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` produced.
Merged manifest contains `android.permission.NFC`, the non-required NFC
`<uses-feature>`, and the `NfcProfileShareActivity`.

## Safety

- NFC permission is a normal (install-time) permission; the `<uses-feature>` is
  **required=false** so the app installs on no-NFC devices, where the screen
  honestly reports NOT_SUPPORTED.
- Reader mode is enabled only inside the foreground Activity and always released
  in `onPause`/`onDestroy`; no background/launch/recomposition auto-start.
- Send emits only the canonical bounded profile envelope (no URI, image, token,
  or unrelated data); payload capped at 512 KiB both directions.
- Receiving validates via the existing `ProfileShareImporter` (shared merge
  pipeline cannot be bypassed) and fails closed on any invalid input (never
  updates local profile, except after a true successful import).
- Duplicate messages are not re-imported: persistent dedupe store + in-memory
  session cache, bounded.
- No new credentials, no network access added; no secret persisted (only bounded
  message signatures).

## Remaining (honestly not ported / not fully equivalent)

- **Harmony `harmonyShare` knockShare discovery**: peer-targeted `sendCapability` /
  `dataReceive` registration, the system "knock" handshake UX, and the Harmony
  share sandbox inbox have no Android equivalent. Android substitutes explicit
  foreground reader-mode sessions and literal NDEF peer contact.
- **Harmony system share / launch-Want UI**: the Harmony file-open/share system
  surface is covered on Android by the existing `ACTION_VIEW` intent flow in
  `ProfileShareActivity`, not reimplemented in the NFC path.
