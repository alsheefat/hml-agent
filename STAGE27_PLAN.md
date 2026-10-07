# HML Agent — Stage 27

UI
- Top buttons (menu / history / new chat) are glass: translucent + blurred chat behind them (Android 12+).
- Official Google "G" and Facebook "f" logos on the sign-in page (vector drawables).
- Clipboard: tapping a clip in the keyboard's clipboard panel now pastes (OnReceiveContentListener on the composer).

Auto-update
- build.gradle.kts: versionCode = 100 + GITHUB_RUN_NUMBER; BuildConfig.BUILD_NUMBER / UPDATE_REPO.
- Workflow publishes a GitHub Release "build-<n>" (asset hml-agent.apk) after every successful build.
- App checks the latest release (UpdateManager), asks "Update?", downloads, installs via PackageInstaller.
- Accessibility service taps "Update/Install" on the installer screen when armed (best effort).
- Chat command: "check for updates".
- NOTE: the repository must be PUBLIC for the app to read releases. First install of this build is manual.

Agent
- QuickSkills: verified built-in flows for "send X to NAME in WhatsApp/Messenger/Telegram/Instagram" and
  "play ... (on YouTube)"; planner is the fallback.
- Scrolling never swipes across the keyboard (that was typing "t5"); uses the app's own scroll first.
- tapByText prefers exact matches; typing prefers the focused field.
- "screenshot" command (system screenshot via Accessibility).
- WhatsApp number links are converted to international format.

Google login
- app/google-services.json now contains the Android OAuth client (type 1, certificate_hash 4120cb38...f318 =
  keystore/debug.keystore SHA-1) plus the Web client used by default_web_client_id. Sign-in should work.
- The fingerprint only changes if keystore/debug.keystore is replaced; app updates keep it.
