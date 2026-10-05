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

## Stage 22 UI Polish Patch — Drawer / Header / Composer
- Fixed conversation-options popup placement so it stays inside the HML drawer and remains compact.
- Kept Rename, Pin, Share, Add to Home and Delete functionality intact; Delete remains visually separated/red.
- Fixed edge-to-edge drawer safe areas so the HML header and Guest card no longer hide behind system bars.
- Replaced the generic hamburger with an HML-styled menu vector.
- Removed the shared top fade overlay and replaced it with an invisible atmospheric fade behind the header controls, starting at the true top edge.
- Reworked the composer blur so the blur is clipped to the exact writing-bar bounds; removed the old full-width strip above the keyboard and kept the focused state on the same glass surface.
- Kept all existing composer controls and behavior unchanged.
- Corrected Temporary Chat's top-button treatment to preserve the HML blue/white icon colors.
- No existing feature removed; this patch is UI-only apart from safe-area/positioning behavior.
