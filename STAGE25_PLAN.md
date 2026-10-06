# HML Agent — Stage 25 (UI glass pass)

- System status bar + navigation bar are fully transparent (no black bars, no contrast scrim).
- Top: chat list is full-screen and fades out from the very top edge (behind clock/network icons).
  No header surface; only the icon buttons keep their own rounded surfaces. Title row removed;
  temporary-chat mode is shown by the highlighted temporary button.
- Bottom: no fade. Chat scrolls under the writing bar; inside the bar the chat is shown blurred
  (BlurBehindView, Android 12+). Older Android uses a near-opaque bar.

## Fix (stage 26)
- BlurBehindView.onMeasure no longer inflates its FrameLayout parent (bar was full-screen tall).
