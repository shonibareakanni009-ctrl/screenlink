# ScreenLink

**See your phone's screen on a Chromebook — and control the phone by clicking.**

Like a simple local "Browserling for your phone": the Chromebook shows the live screen; clicks are sent back as taps.

| App | Where | Role |
|-----|--------|------|
| **ScreenLink Receiver** | Chromebook (Chrome App) | Shows screen, plays audio, sends taps |
| **ScreenLink Share** | Android phone | Captures screen + optional audio; receives remote taps |

Same Wi-Fi only. No cloud.

---

## Build the Android APK (GitHub Actions)

1. Open this repo on GitHub → **Actions**
2. Select **Build Android APK**
3. Click **Run workflow** → **Run workflow**
4. Wait until the job is green
5. Open the finished run → **Artifacts** → download **screenlink-debug-apk**
6. Unzip and install the `.apk` on your phone (allow "Install unknown apps")

The workflow also runs automatically on every push to `main`.

### Alternative: Android Studio

1. Open the `android/` folder in Android Studio
2. **Build → Build Bundle(s) / APK(s) → Build APK(s)**

---

## Remote control

1. Phone: **Settings → Accessibility → ScreenLink → On**
2. Start sharing to the Chromebook
3. Chromebook: **click the mirrored image** → phone receives a tap

## Chromebook receiver

1. Load `chrome-app/` as an unpacked Chrome App (`chrome://extensions` → Developer mode)
2. Power On → **Start Wi-Fi receiver** → copy `http://192.168.x.x:8080`
3. Phone and Chromebook on the **same Wi-Fi**

## Limits

- Laggy on 2 GB / 2012 Chromebooks — use **Low** quality
- Control needs Accessibility permission
- App audio: Android 10+; some apps block capture
