# HML Agent — Stage 23 (rechecked / corrected)

Stage 23 preserves the Stage 22 feature set and fixes the actual UI geometry problem instead of merely moving the chat surface.

## 1. Composer + chat geometry (primary fix)
- The root cause was `adjustNothing` combined with adding the IME height to `composerContainer` bottom padding.
- That increased the composer's layout height, so its top edge moved upward.
- `messageList`/home content was constrained to the composer's layout top, so the visible chat area appeared to move upward too.
- Stage 23 no longer adds IME height to composer padding.
- The composer remains bottom-anchored and is translated above the keyboard only visually.
- The chat list keeps its layout bounds and receives only bottom content padding so the latest message stays above the keyboard/composer.
- Focused and unfocused composer use the same glass surface; no focus-state layout jump.

## 2. Top controls
- Removed the shared full-width atmospheric header layer that was visibly behaving like a rectangle.
- The existing app background supplies the subtle upper glow.
- Existing individual button surfaces remain unchanged.
- Hamburger and Temporary/clock shapes are unchanged; only their gray tint is corrected to the existing HML blue.
- The solved 3-dot conversation menu is untouched.

## 3. History durability
- One atomic JSON file per conversation instead of one growing SharedPreferences blob.
- Existing legacy history is migrated even if a partial file-store migration already exists; existing per-chat files are never overwritten by legacy migration.
- Malformed chats are isolated instead of hiding the whole history.
- Long conversations are not limited by one SharedPreferences transaction.
- 100 retained conversations.
- Recent ordering uses `updatedAt`.

## 4. Conversational reactions
- Ordinary conversational turns are counted locally.
- Three of every five ordinary turns are explicitly marked `reaction_required=true`.
- The server behavior contract requires a brief natural reaction when that flag is true.
- Commands, factual lookups, and autonomous missions are excluded.
- Wording should vary instead of repeating canned reactions.

## 5. Drawer swipe
- Left-edge swipe opens after ~42dp horizontal travel from a 28dp edge zone.
- Threshold is handled during MOVE so DrawerLayout cannot consume the gesture before HML opens it.
- Hamburger button behavior remains unchanged.

## 6. Build output
- Installed application remains `HML Agent`.
- `assembleDebug` additionally copies the debug APK to `HML-Agent-v1.5.apk`.
- No incompatible AGP `outputFileName` API.

## Preservation rule
No existing feature is intentionally removed. The 3-dot menu and its current functionality are explicitly left alone.
