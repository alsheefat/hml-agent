# HML Agent — Stage 22

Built additively on the Stage 21 fixed baseline. No Stage 20/21 feature is intentionally removed.

## UI
- Fix whole drawer geometry/insets and left clipping.
- Make conversation options narrow/content-sized with HML icons for Rename, Pin, Share, Add to Home; separated red Delete.
- Keep individual top icon surfaces; remove the impression of one shared rectangular surface and keep the fade behind/between them.
- Reduce composer blur height so it hugs the writing bar; keep glass appearance when focused.
- Align Temporary Chat color with HML palette.
- Increase core conversation/composer/history typography and improve capitalization consistency.

## Alarm / scheduling
- Add true alarm intent parsing including named alarms such as `set alarm at 2:29 and name as Messenger Issue`.
- Use AlarmManager.setAlarmClock for alarm intent.
- Add HML alarm channel with alarm-category sound/vibration and full-screen AlarmActivity.
- Alarm UI includes large time/title plus Dismiss and Snooze 10 min.
- Keep reminders as reminders and scheduled autonomous tasks as tasks; confirmation language distinguishes them.
- Preserve future-time autonomous missions as deferred missions; target apps are not opened by the scheduler before the trigger.

## Agent execution
- Add local semantic context separating entities from modifiers (contact/carrier, person/platform, content/platform).
- Prefer accessibility click actions for app controls before coordinate gestures.
- Add Android IME action support for Search/Done/Enter instead of visually manipulating Gboard.
- Strengthen Messenger/WhatsApp send-control handling and verification path.
- Keep adaptive recovery, screenshot vision, device context, completion verification and context/follow-up behavior from previous stages.
