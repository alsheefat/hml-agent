# HML Agent — Stage 19

Version: 1.2 / versionCode 19

## Product goal
Stage 19 is an actual Agent upgrade plus a UI identity pass. It removes duplicated bottom navigation, makes the composer compact and keyboard-safe, and adds adaptive recovery + local Skills/repeatable missions.

## Agent upgrades
- Adaptive recovery loop: failed tap/type actions trigger a fresh screen observation and a recovery instruction instead of blindly repeating the same target.
- Recovery is bounded to keep the agent safe.
- Planner requests now carry agent mode, step count, and recovery-pass metadata.
- Last autonomous goal is stored locally so the user can say `do it again`, `repeat the last task`, or `repeat that`.
- Named local Skills: after an autonomous task, `save this task as <name>` stores the goal; `run skill <name>` re-runs it.
- Existing 15-step / 2-minute / confirmation gates remain intact.

## UI upgrades
- Removed the 3-button bottom dock because Home/Chat/History duplicated controls already available through the existing top/drawer navigation.
- Temporary mode now uses the dotted-ring identity directly in the top bar and composer.
- Attachment is a paperclip rather than another generic plus button.
- Voice control uses a waveform mark instead of the generic microphone glyph.
- Composer reduced from the oversized 42–46dp control row to a compact 34–38dp control surface.
- Top Temporary and New Chat controls intentionally use different shapes instead of one repeated button treatment.
- Android IME handling now uses explicit WindowInsets with edge-to-edge and `adjustNothing`, so the composer follows the keyboard instead of relying on OEM-specific resize behavior.

## Changed files
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/hmlai/agent/MainActivity.kt`
- `app/src/main/java/com/hmlai/agent/AutonomousTaskRunner.kt`
- `app/src/main/java/com/hmlai/agent/SkillStore.kt` (new)
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/drawable/top_temp_bg.xml` (new)
- `app/src/main/res/drawable/top_new_bg.xml` (new)
- `app/src/main/res/drawable/composer_temp_bg.xml` (new)
- `app/src/main/res/drawable/composer_attach_bg.xml` (new)
- `app/src/main/res/drawable/composer_voice_bg.xml` (new)
- `app/src/main/res/drawable/ic_voice_wave.xml` (new)

## Compatibility
No new third-party dependency. Existing backend endpoint/protocol remains compatible; extra planner metadata is additive.
