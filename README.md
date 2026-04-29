# Slack Nuke

A one-button Android app that blocks Slack until 6 am.

Tap the big red button → Slack is force-closed every time you try to open it, until the next 6 am in your local time zone. There is no off-switch in the UI. The only way to lift the block early is to **uninstall the app**.

Built for Android 8+ (tested on Pixel 8 / Android 14).

---

## Install on your phone

The phone-side flow is one-time setup, ~90 seconds total. After that, the cycle is just: open app → tap button → done.

### 1. Get the APK

Every push to `main` builds a fresh APK and attaches it to a rolling release at:

> **https://github.com/USER/slack-nuke/releases/latest** *(replace `USER` after you push the repo)*

On your Pixel 8, open that page in Chrome and tap `slack-nuke.apk` to download.

### 2. Allow installs from your browser

The first time you do this on a Pixel:

1. Tap the downloaded APK in Chrome's downloads (or Files app → Downloads → `slack-nuke.apk`).
2. Android will say "For your security, your phone isn't allowed to install unknown apps from this source."
3. Tap **Settings**, toggle **Allow from this source** on, hit back.
4. Tap **Install**.

### 3. Grant Accessibility permission (one-time)

Open **Slack Nuke**, tap the big red button. The app will pop a dialog asking you to enable Accessibility, then take you to Settings.

In the Accessibility settings list, find **Slack Nuke** and toggle it on.

> **If the toggle is greyed out** (Android 13+ blocks sideloaded apps from accessibility services by default):
> 1. Long-press the Slack Nuke icon → **App info**.
> 2. Tap the **⋮** menu in the top-right corner.
> 3. Tap **Allow restricted settings**.
> 4. Go back to Settings → Accessibility → Slack Nuke and toggle it on now.

This is an Android security guardrail, not a bug. Apps that watch other apps' screens have to be unlocked manually.

### 4. Use it

Re-open Slack Nuke, tap the button. Slack is now blocked until the next 6 am. Try opening Slack — it'll get yanked back to the home screen the moment it appears.

To unblock early: uninstall Slack Nuke (long-press icon → App info → Uninstall).

---

## How it works

There are three small pieces:

- **`MainActivity`** — the single-screen UI. While the block is inactive, it shows the big button. While the block is active, it shows the expiry time and hides the button entirely.
- **`BlockerService`** — an Android `AccessibilityService` configured (via [`accessibility_config.xml`](app/src/main/res/xml/accessibility_config.xml)) to receive `typeWindowStateChanged` events for `com.Slack` only. When such an event arrives during an active block window, it calls `performGlobalAction(GLOBAL_ACTION_HOME)` to send you back to the launcher.
- **`BlockState`** — persists a `blockUntilMillis` value in `SharedPreferences`. The "next 6 am" timestamp is computed from `ZonedDateTime.now()` in the device's local time zone.

The app does not use the network, doesn't request any sensitive permissions beyond Accessibility, and has no analytics.

### What "no off-switch" actually means

There is no UI affordance to clear an active block. Force-stopping the app from Settings won't help — the accessibility service is reattached by the system on the next foreground event. Clearing app data wipes the `blockUntilMillis` preference, so technically that is an escape hatch, but it costs more friction than a tap. The intended out is: **uninstall**.

(This is also the safety valve. If something goes wrong, you can always uninstall and reinstall fresh.)

---

## Build it yourself

You don't need to — CI builds an APK on every push and attaches it to the `latest` release. But if you want to:

```bash
# Requires JDK 17 and either Android Studio or a standalone Gradle 8.9.
git clone https://github.com/USER/slack-nuke.git
cd slack-nuke
gradle wrapper --gradle-version=8.9   # one-time, generates ./gradlew
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Or open the directory in Android Studio (Iguana or newer) and hit Run.

---

## Customising the schedule

The "next 6 am" rule lives in [`BlockState.kt`](app/src/main/java/com/slacknuke/BlockState.kt) — change the `LocalTime.of(6, 0)` to whatever wake-up time suits you, rebuild, reinstall.

To block additional apps (e.g. Teams, Discord), edit [`accessibility_config.xml`](app/src/main/res/xml/accessibility_config.xml) and add their package names to `android:packageNames`, and update the check in [`BlockerService.kt`](app/src/main/java/com/slacknuke/BlockerService.kt).
