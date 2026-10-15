# ScreenLink

**See your phone's screen on a Chromebook — and control the phone by clicking.**

Like a simple local "Browserling for your phone": the Chromebook shows the live screen; clicks are sent back as taps.

| App | Where | Role |
|-----|--------|------|
| **ScreenLink Receiver** | Chromebook (Chrome App) | Shows screen, plays audio, sends taps |
| **ScreenLink Share** | Android phone | Captures screen + optional audio; receives remote taps |

Same Wi-Fi only. No cloud.

## Remote control

1. Phone: **Settings → Accessibility → ScreenLink → On**
2. Start sharing to the Chromebook
3. Chromebook: **click the mirrored image** → phone receives a tap

## Setup

### Chromebook
Load `chrome-app/` unpacked → Power On → Start Wi-Fi receiver → copy the link.

### Phone
Build APK from `android/` → enable Accessibility → paste link → Start ScreenLink.

Full source is in the downloadable project package; push remaining files from local if needed.
