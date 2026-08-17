# Phase 27 result: explicit profile affinity report

DSH fixed the focused-test regression and added a real, explicit-user-action
"同频报告" (profile-affinity report) to the existing ProfileShareActivity import
flow, faithfully porting Harmony `runProfileShareAffinityAnalysis` /
`buildProfileAffinityMessages` onto the shared OpenAI-compatible Planner chat
backend — with all safety boundaries preserved.

## Fixed regression (focused test)

`ProfileAffinityAnalyzerTest` previously reported **14 tests, 1 failed**:
`buildLocalAffinityProfileText is capped to the input bound and never raw`
asserted `text.contains("summary0")` but the production mapping prefers
`domain.summary` over `shortSummary`, and the fixture set a non-blank `summary`
that shadowed the populated `shortSummary`. Real local profiles only ever
populate `shortSummary` (the editor and `ProfileShareImporter` write that
field; `summary` stays blank), so the fixture was corrected to mirror that real
state (populate `shortSummary`, leave `summary` blank) — the test now exercises
the real `shortSummary` fallback path. **Safety boundary unchanged.**

## Review fixes shipped

- **`ProfileAffinityBounds.MAX_REPORT_RESPONSE_BYTES`** was declared but unused.
  Since the affinity analyzer only ever receives the parsed
  `ChatCompletionResult.content` (the shared `ChatCompletionsClient` buffers the
  raw HTTP body internally and its behaviour is intentionally unchanged), a raw
  HTTP body byte cap cannot be enforced from this layer. Rather than fake an
  implementation, the unused declaration was **removed**; the effective output
  safety boundary is `MAX_REPORT_CHARACTERS` (16 KiB).
- **`showRetryDialog` / `showReportFailure`** `technical` parameter was unused —
  removed (signature + both call sites).
- **`.gitignore`** only keeps the necessary `.gradle-home/` entry (a real
  generated local Gradle home/cache in this worktree); no repository build
  artifacts are added.
- Verified already-correct boundaries: `ProfileAffinityConfig.fromEndpointConfig`
  gate (null when unconfigured), prompt carrying only two bounded profile texts,
  `SensitiveLogRedactor`-based error redaction, explicit "生成同频报告" button
  only, and `onDestroy` coroutine cancellation.

## Focused verification (serial)

Command:

```
sdk.dir=/opt/deepseek-harness/android-sdk
GRADLE_USER_HOME=<worktree>/.gradle-home/.gradle
gradle :app:testDebugUnitTest --tests com.clawmate.app.profile.affinity.ProfileAffinityAnalyzerTest --offline --console=plain
```

Result (from `app/build/test-results/testDebugUnitTest/TEST-...ProfileAffinityAnalyzerTest.xml`):
**build → BUILD SUCCESSFUL; 14 tests, 0 failures, 0 errors.** `:app:compileDebugKotlin`
and `:app:compileDebugUnitTestKotlin` both succeeded (main source incl. the
ProfileShareActivity wiring and test source compile cleanly).

## Known limitations / unfinished risk

- The report is only produced by the explicit user action after a successful
  import and only when the Planner backend is configured; it is never generated
  automatically — matching the honest boundary.
- A raw HTTP-body byte cap is deliberately **not** asserted at the affinity layer
  because the shared `ChatCompletionsClient` owns that buffering and its behavior
  is intentionally left unchanged; the content-character bound
  (`MAX_REPORT_CHARACTERS`) is the admitted-output safety boundary.
- The affinity result is shown in the existing dialog surface (AlertDialog text);
  no separate Compose report screen was added this phase.
- No new credentials / secrets; endpoints and keys come from existing
  `EndpointConfig`. No Runtime.exec/ProcessBuilder/adb/hdc/coordinate GUI
  automation. Existing `ChatCompletionsClient` behaviour unchanged.

Target merge: git merge --ff-only PHASE_COMMIT.
