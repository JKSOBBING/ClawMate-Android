# HarmonyOS → Android migration backlog

Reference: `doulujiyao12/mobiinfra-oh`, `clawmate_dev`, commit
`cd69b68bf16b9ed352be5073939eb24d042de583`. Source files are read-only.
Android baseline: `7f76afb`. Implementation branch: `codex/android-migration`.
The existing Android app already contains substantial implementations; these are
the remaining gaps identified in the source comparison, not a claim that every
feature below starts from zero. Detailed evidence and completed checks are in
`MIGRATION_STATUS.md`.

| ID | Gap / next Android slice | Harmony source of truth | State |
|---|---|---|---|
| F01 | Executable home task suggestions | `pages/home/HomePage.ets`, `HomeTaskSuggestion.ets` | Generation, evidence/detail, editable copy and supported-workflow navigation complete; free-form GUI/Qwen execution pending P01/P06 |
| F02 | Emotional support chat entry and prompt | `pages/home/HomePage.ets`, `HomeEmotionalSupport.ets`, `HomeComfortChatStore.ets` | Casual support and grounded topics complete with first-party chat adapter; unit/build and 4 device tests pass; provider quality/source collection expansion unverified |
| F03 | Chat → daily-log long-term memory synchronization | `datamanager/ChatMemorySync.ets`, `ChatMemoryExtraction.ets` | Implemented with per-run consent and separate local index refresh; unit/build and 4 feature device tests pass; real provider/configured-account verification pending |
| F04 | `query_events` personal memory agent tool | `datamanager/DataManagerQueryEventsTool.ets` | Implemented; unit/build and 2 connected-device query tests pass |
| F05 | Image attachment transmission to a vision-capable cloud model | `pages/chat/ChatAttachmentPolicy.ets`, `pages/Index.ets` | Bounded image preparation/request encoding complete; chat attachment/history integration pending |
| F06 | Markdown, code, safe links, full-message copying | `components/MarkdownView.ets`, `pages/chat/ChatMessageList.ets` | Implemented; unit/build and 4 device renderer/whole-message clipboard tests pass |
| F07 | Gallery cloud vision analyzer | `utils/gallery/GalleryAnalyzer.ets` | Shared image preparation/vision HTTP foundation complete; actual analyzer/UI/persistence integration in progress |
| F08 | Gallery question answering | `pages/gallery/GalleryPage.ets`, `utils/gallery/GallerySearchService.ets` | Pending; keyword search exists |
| F09 | Unified gallery → daily-log → index refresh | `utils/gallery/GalleryMemorySync.ets`, `datamanager/DataManagerFacade.ets` | All 12 memory summary categories/legacy mapping complete; unified gallery/global refresh orchestration pending |
| F10 | Workflow config viewing, JSON editing, creation/deletion, graph editor | `pages/task/TaskPage.ets`, `WorkflowFileViewer.ets` | Viewer `b930746`; JSON editing complete; creation/deletion and graph UI pending |
| F11 | Workflow `vlm_qa` cloud tool | `utils/WorkflowRunner.ets` | Pending |
| F12 | Avatar image-to-image and version selection | `datamanager/avatar/AvatarService.ets` | Pending; text-to-image exists |
| F13 | Real MiMO streaming voice client | `utils/voice/MimoVoiceClient.ets` | Pending; system ASR/TTS exists |
| F14 | Collection reminder notifications | `utils/CollectionReminderPolicy.ets`, `CollectionReminderService.ets` | Pending; distinct from consolidation scheduling |
| F15 | Persistent gallery scan pause/resume | `pages/gallery/GalleryPage.ets` | Pending; run/cancel exists |
| F16 | Versioned privacy consent and revocation | `utils/PrivacyConsent.ets`, `pages/Index.ets` | Pending |

Platform-specific follow-ups (implement Android adapters or explicit actionable
unavailable states, never simulated success):

| ID | Area | Existing Android boundary |
|---|---|---|
| P01 | Model-driven GUI/screenshot loop | Accessibility execution currently restricts semantic actions to the app whitelist |
| P02 | PC companion/router | Companion setup/adaptation required |
| P03 | WeChat rich collection result persistence | Bridge currently maps status/message |
| P04 | Floating controls and visual status | Status overlay exists; pause/resume/stop controls remain |
| P05 | Avatar segmentation and custom avatar in chat | Default drawable currently used in chat |
| P06 | Xiaoyi A2A, Huawei account, ShareKit | Harmony services need Android-specific decisions |

Native C++/MNN inference remains deferred by the project request. Preserve honest
capability reporting. Do not embed shared relay URLs, credentials or keys from
screenshots in source control. Real cloud checks require configured credentials;
local HTTP protocol tests are recorded separately from provider verification.

Each completed small feature needs a build/test result, connected-device check,
status entry and separate commit. Keep the original phone app and its data;
validate with a separate development application id. Create a PR only after the
user's final review confirmation.
