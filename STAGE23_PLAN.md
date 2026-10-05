# HML Agent — Stage 23

Stage 23 is additive and keeps the Stage 22 UI/features intact.

## 1. Durable conversation history
- Replace the single growing SharedPreferences JSON blob with one atomic JSON file per conversation.
- Automatically migrate existing Stage 22/earlier SharedPreferences history on first launch.
- A malformed conversation can no longer hide the rest of the history.
- Long chats are no longer dependent on one ever-growing preference transaction.
- Keep the existing rename/pin/delete/share/home-shortcut behavior.
- Increase retained history from 50 to 100 conversations.

## 2. More natural HML reactions
- Strengthen the model behavior contract so ordinary conversation gets a genuine conversational reaction when appropriate.
- Target roughly 3 of 5 ordinary conversational turns, while explicitly avoiding forced reactions for commands, factual lookups, and autonomous missions.
- Tell HML to vary phrasing and avoid repetitive canned reactions.

## 3. Shorter drawer swipe
- Add a small HML DrawerLayout subclass with a short left-edge swipe trigger (~28dp edge start, ~42dp horizontal travel).
- Existing hamburger button behavior remains unchanged.

## 4. Build output
- Keep installed app name `HML Agent`.
- Produce an additional CI APK named `HML-Agent-v1.5.apk` without using the incompatible AGP `outputFileName` API.

## Preservation rule
No existing feature is intentionally removed or replaced. Stage 23 only hardens storage, improves conversational behavior, adds the shorter swipe gesture, and fixes the APK naming build failure.
