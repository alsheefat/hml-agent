# HML Agent — Stage 20: Full Autonomous Agent Upgrade

Stage 20 is the major agent release. It keeps the Stage 19 UI/UX direction and adds the missing intelligence layer instead of adding cosmetic features.

## Product rule

**The user does not react to messages. HML reacts naturally to the user.**
There is no thumbs-up/down/emoji reaction control in the chat UI.

HML should distinguish:
- conversation
- questions
- commands
- multi-step missions
- reminders/scheduled work
- follow-ups
- corrections
- emotional/contextual messages

## Multimodal input

The Android client now sends actual attachment bytes rather than only filenames.

Payload fields:
- `attachments`: array of `{name, mime_type, data, original_mime_type}`
- `images`: same array for vision-aware backends
- `history`
- `profile`
- `agent_context`

Images are resized and JPEG-compressed on-device before upload. Multiple images are supported. Small non-image files can also be transported for a backend that supports documents.

### Backend contract

The existing `/chat` endpoint must read `images`/`attachments` and pass the actual bytes to the configured multimodal model (for Gemini this means image/file content parts, not the filename string).

The Android app intentionally keeps the existing endpoint so a backend can be upgraded without another app architecture rewrite.

## Agent brain

The client supplies the backend with:
- conversation history
- explicit long-term memories
- user profile
- previous/active mission goal
- capabilities
- natural-agent behavior instructions

Follow-up references such as `it`, `that`, `this one`, `the second one`, `do it`, and corrections can continue the previous autonomous goal instead of starting a disconnected conversation.

## Autonomous loop

`Understand → Plan → Act → Observe → Verify → Recover → Continue → Finish`

The runner supports:
- accessibility tree + screenshot observation
- app launch/open actions
- tap/type/scroll/back
- wait/sleep between UI transitions
- explicit verification passes
- bounded retries
- recovery after failed UI targets
- mission timeout and step limits
- cancellation
- confirmation for high-impact actions
- honest failure reporting

## Vision-assisted screen control

Android Accessibility remains the first source of semantic UI information. Screenshots are also captured on Android 11+ when safe. Password/PIN/OTP/card-entry screens suppress screenshot transmission.

The `/screen-action` backend should combine:
1. accessibility tree
2. screenshot
3. goal
4. previous actions
5. recovery state

and return a small action object such as:

```json
{
  "action": "tap",
  "target": "Search",
  "reasoning": "The search control is the next required step."
}
```

Supported client actions include `launch_app`, `open_app`, `tap`, `type`, `scroll_down`, `scroll_up`, `back`, `wait`, `verify`, and `done`.

## YouTube-first media behavior

Music/video goals should prefer regular YouTube unless the user explicitly asks for YouTube Music. This avoids assuming YouTube Music is installed or usable.

## Scheduling

Full autonomous goals can be persisted and scheduled through Android exact alarms. Android background activity restrictions are respected: when the OS prevents a background UI launch, HML uses the notification/foreground handoff instead of pretending it silently completed the task.

## Memory

Explicit user memories remain on-device. Conversation history and active mission context are supplied to the backend for contextual responses. `remember`, `forget`, and memory display remain user-controlled.

## Safety

Simple harmless actions should not be interrupted with unnecessary confirmation. High-impact actions such as sending, posting, deleting, purchasing, paying, transferring, calling, accepting, blocking, reporting, or submitting require confirmation before execution.

## What is intentionally NOT claimed

Stage 20 cannot bypass Android security restrictions, app-specific anti-automation protections, login/OTP challenges, or a third-party app's own UI/API limitations. It must verify outcomes rather than claiming success.
