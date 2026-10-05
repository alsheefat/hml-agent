# HML Agent — Stage 21 Plan

## Release rule
Stage 21 is additive. No Stage 20 feature is removed, replaced, or intentionally downgraded.

## UI additions
- Add a subtle upper content fade beneath the top bar/chat header, inspired by the way modern AI chat UIs visually fade scrolling content into the header.
- Preserve the Stage 20 custom New Chat and Temporary Chat controls.
- Preserve the Stage 20 glass/blur composer and custom voice, guest, rename, drawer, and logo work.

## Agent additions
- Send lightweight device context to the autonomous planner: battery percentage/charging state, validated network state/transport, free storage, and availability of common target apps.
- Recover from transient planner/network failures with bounded delayed retries instead of immediately abandoning a mission.
- Preserve the Stage 20 hard completion rule: an intermediate action such as entering a YouTube search query is never treated as mission completion.

## Version
- versionCode: 21
- versionName: 1.4
