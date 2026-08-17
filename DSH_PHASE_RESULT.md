# Phase 28 result: explicit home recommendations from gallery data

DSH added a real, explicit-user-action "生成推荐" (generate recommendations)
flow to the Home screen that plans recommendations from **real, already-stored
Android Gallery data** on the shared OpenAI-compatible Planner chat backend —
faithfully porting Harmony `DataPipelineModelClient.planHomeRecommendations` /
`HOME_RECOMMENDATION_SYSTEM_PROMPT` — with all safety boundaries preserved.

## Fixed focused-test regression

`HomeRecommendationPlannerTest` previously reported **22 tests, 1 failed**:
the "never leaks a URI or key" test asserted
`assertFalse(user.contains("image_paths"))`.

The production prompt *faithfully* includes the Harmony protocol field
`image_paths: []` (the field name is not sensitive data; raw image references
never leave the device, so the value is always the empty list). The real safety
requirement is that the `image_paths` array is empty / carries no non-empty
path value — not that the protocol field name is absent.

The assertion was fixed to **parse the user JSON payload and assert every
`image_paths` value across every event is empty** (plus the unchanged
`content://` / URI / secret checks). **Real safety boundary unchanged** — the
Harmony protocol field is kept, `image_paths` is and remains
`emptyList()` in production.

## Implementation (Phase 28)

- **`HomeRecommendationPlanner`** — builds the two-message prompt (system +
  bounded user JSON payload mirroring the Harmony shape) and calls the shared
  `ChatClient`. Only real planner-event text + current date leave the device.
- **`GalleryPlannerEventMapper`** — maps real stored `GalleryPhotoItem`s into
  bounded planner events: real text fields capped, keywords/tags distinct &
  capped, `imagePaths` always empty, real URI/bytes/keys never emitted.
- **`HomeRecommendationNormalizer`** — strict output validation: non-empty
  title, `domain` constrained to the six allowed domains, tag/desc/title length
  bounds, `source_event_ids` must belong to input (and each input id is used at
  most once), recommendation count capped.
- **`GalleryBackedHomeRecommendationRepository`** — config gate
  (`fromEndpointConfig` null → `NotConfigured`, zero calls) and no-data
  short-circuit (no gallery, or all blank → `NoData`, zero calls). Only runs
  from an explicit call; no background work / service / WorkManager.
- **`HomeViewModel.generateRecommendations()`** — explicit user action driven
  by the Home "生成推荐" button only; never auto-invoked (init loads profile
  only). Error surfaces are redacted (`SensitiveLogRedactor`) and bounded.

## Verification performed

- **Explicit button only**: "生成推荐" wired to `viewModel.generateRecommendations()`
  via `Button(onClick=...)`; no LaunchedEffect/auto/background invocation.
- **Zero calls when unconfigured / when no real gallery data**: covered by tests
  (config gate returns null and repo short-circuits to NotConfigured; repo returns
  NoData with zero calls for empty/blank input).
- **Gallery metadata bounded input**: mapper caps todo_text/summary/normalized_fact/
  keywords; emits no URI/bytes/keys.
- **`source_event_ids` validation**: non-input ids dropped, dedup so each input id
  appears at most once, count cap — covered by tests.
- **Error redaction**: typed redacted failure, `safeMessage` never contains
  secrets — covered by tests.

## Focused verification (serial)

Command:

```
sdk.dir=/opt/deepseek-harness/android-sdk
./.phase28-gradle.sh :app:testDebugUnitTest \
  --tests "com.clawmate.app.home.recommendation.*" \
  --tests "com.clawmate.app.home.HomeRecommendationViewModelTest"
```

Result (from `app/build/test-results/testDebugUnitTest/*.xml`, `--no-daemon
--max-workers=1`): **build → BUILD SUCCESSFUL; 22 tests, 0 failures, 0 errors**
across `GalleryPlannerEventMapperTest` (6), `HomeRecommendationPlannerTest` (12)
and `HomeRecommendationViewModelTest` (4).

## Known limitations / unfinished risk

- Recommendations are produced only by the explicit Home "生成推荐" action and
  only when the Planner backend is configured; never automatic.
- Input is limited to already-stored Gallery text metadata; a photo with no
  analysed text is honestly skipped (no fabricated content).
- The original Harmony code is reference-only on HarmonyOS. Nothing under
  `/root/mobiinfra-oh` was modified.
- No new credentials / secrets; endpoints and keys come from existing
  `EndpointConfig`. No Runtime.exec/ProcessBuilder/adb/hdc/coordinate GUI
  automation. Existing `ChatCompletionsClient` behaviour unchanged.

Target merge: git merge --ff-only PHASE_COMMIT.
