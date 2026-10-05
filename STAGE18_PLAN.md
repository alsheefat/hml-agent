# HML Agent — Stage 18 implementation plan

## Product rule
HML Agent remains an agent, not another chatbot: the user gives a goal, HML plans one bounded action at a time, verifies the result, and reports completion. Existing chat, voice input, attachments, history, app launching, calls/SMS, reminders, and accessibility automation remain intact.

## Priority 1 — Agent Mode 2.0
- Keep the existing `/screen-action` one-action-at-a-time protocol.
- Add a 15-step hard cap and 2-minute mission timeout.
- Add a visible STOP control while a mission is running.
- Add cancellation that clears pending callbacks and finishes exactly once.
- Keep action verification and one retry for tap/type failures.
- Bound planner observation history to the latest 8 observations.
- Add explicit confirmation before common high-impact actions such as send, submit, post, delete, buy, pay, call, accept, allow, and report.

## Priority 2 — Reliability and safety
- Chat network requests retry once for transient connection/5xx failures.
- 429 responses get a specific rate-limit message.
- Autonomous missions stop instead of guessing after repeated failures.
- Screenshot capture is skipped while password/PIN/OTP/card-entry fields are detected.
- Existing accessibility opt-in remains required; Android controls service activation.
- Existing conversation-ID checks prevent late network replies from corrupting another chat.

## Priority 3 — Voice-first experience
- Existing Android SpeechRecognizer flow is retained.
- A recognized voice command is now submitted automatically instead of requiring a second Send tap.
- No new speech SDK or cloud voice dependency is introduced.

## Priority 4 — Long-term memory
- Add a small on-device explicit-memory store using SharedPreferences + JSON.
- Only explicit commands such as `remember that ...` create durable memories.
- `forget that ...` removes matching memories.
- `what do you remember` displays stored memories.
- Up to 40 memories are retained locally; the latest 20 are sent to the chat backend as context.
- Existing name/profile memory remains unchanged.

## Priority 5 — UI polish
- Preserve the Stage 17 clean dark UI and keyboard-safe composer.
- Keep the task monitor compact and add a quiet STOP control.
- Keep the bottom navigation hidden while the IME is visible.
- Preserve icon-led controls and existing quick actions instead of adding another toolbar.

## Changed files
- `app/src/main/java/com/hmlai/agent/AutonomousTaskRunner.kt` — Agent Mode 2.0 lifecycle, bounds, verification, cancellation, confirmation gates.
- `app/src/main/java/com/hmlai/agent/MainActivity.kt` — voice auto-submit, task stop/confirmation UI, memory commands, memory context, chat retries.
- `app/src/main/java/com/hmlai/agent/HmlAccessibilityService.kt` — screenshot privacy guard for sensitive input screens.
- `app/src/main/java/com/hmlai/agent/MemoryStore.kt` — new local explicit-memory store.
- `app/src/main/res/layout/activity_main.xml` — task STOP control.
- `app/src/main/res/drawable/task_stop_bg.xml` — new STOP control background.
- `app/src/main/res/values/strings.xml` — Stage 18 status, safety, confirmation, and task strings.
- `app/build.gradle.kts` — versionCode 18 / versionName 1.1.
- `STAGE18_PLAN.md` — implementation notes and build instructions.

## Build
From the project root:

```bash
gradle assembleDebug
```

The debug APK is produced at:

`app/build/outputs/apk/debug/app-debug.apk`

No root, desktop-only dependency, database library, or new cloud SDK is required. The existing Android/Termux-compatible Gradle/Kotlin/OkHttp stack is preserved.
