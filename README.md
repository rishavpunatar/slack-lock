# Slack Lock

An Android self-binding app that prevents opening the Slack app for a chosen timer duration.

The app is intentionally narrow: one button, one duration picker, one confirmation, and one Accessibility service scoped to Slack's Android package (`com.Slack`). During an active lock, opening Slack sends you back to the home screen.

This does not force-stop Slack, mute notifications, block Slack in a browser, or block Slack in another Android profile. Pair it with Android Focus/DND or Slack notification settings if notifications are the real trigger.

Built for Android 8+.

---

## Install on your phone

### 1. Get the APK

Every push to `main` builds a fresh APK and attaches it to a rolling release at:

> **https://github.com/rishavpunatar/slack-lock/releases/latest**

On your phone, open that page in Chrome and tap `slack-lock.apk` to download.

### 2. Allow installs from your browser

The first time you do this on a Pixel:

1. Tap the downloaded APK in Chrome's downloads, or in Files -> Downloads.
2. Android will say your browser is not allowed to install unknown apps.
3. Tap **Settings**, enable **Allow from this source**, then go back.
4. Tap **Install**.

### 3. Grant Accessibility permission

Open **Slack Lock** and tap the button. The app first shows an in-app Accessibility disclosure explaining what it can and cannot see. Accepting that disclosure opens Android Settings.

In the Accessibility settings list, find **Slack Lock** and toggle it on.

If the toggle is greyed out on Android 13+:

1. Long-press the Slack Lock icon and open **App info**.
2. Tap the three-dot menu in the top-right corner.
3. Tap **Allow restricted settings**.
4. Go back to Settings -> Accessibility -> Slack Lock and toggle it on.

Android restricts sideloaded apps from enabling sensitive settings until you explicitly trust them. That is expected.

### 4. Use it

Open **Slack Lock**, tap the button, choose a timer, and confirm the lock. Presets include 30 minutes, 1 hour, 2 hours, 4 hours, and the original "until next 6 AM" mode. You can also choose a custom hours/minutes timer.

There is no in-app undo while a lock is active. To stop early, leave the app and either disable **Slack Lock** in Android Accessibility Settings or uninstall the app.

---

## Safety model

Slack Lock uses Accessibility only for a deterministic rule:

> If the Slack Android app opens during an active lock, perform Android's global Home action.

The service configuration is deliberately limited:

- Receives only `typeWindowStateChanged` events.
- Receives events only from `com.Slack`.
- Cannot retrieve window content.
- Cannot perform gestures.
- Does not request network access.
- Does not collect, store, or transmit data.

This app is not presented as an accessibility tool for people with disabilities. It is a personal automation/self-binding tool, so the app includes a prominent in-app disclosure and requires explicit consent before sending you to Accessibility Settings.

---

## How it works

- `MainActivity` handles the single-screen UI, the Accessibility disclosure, the duration picker, the final lock confirmation, and the visible enforcement status.
- `BlockerService` is an Android `AccessibilityService` that listens for Slack foreground events and sends the device home during an active lock.
- `BlockState` stores `blockUntilMillis`, computes duration-based timers, and keeps the next local 6 AM shortcut.

If Accessibility is disabled while a lock is active, the app shows that the timer is still active but enforcement is off.

---

## Build it yourself

CI runs tests, builds a release APK, and attaches it to the `latest` release. Locally:

```bash
# Requires JDK 17 and Android SDK 34.
git clone https://github.com/rishavpunatar/slack-lock.git
cd slack-lock
./gradlew testDebugUnitTest assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

The public repo builds a non-debuggable release variant signed with Android's debug signing config so it can be sideloaded without storing a private signing key in GitHub. For long-term distribution, create your own release keystore and replace the signing config.

---

## Customizing

The 6 AM shortcut lives in [`BlockState.kt`](app/src/main/java/com/slacklock/BlockState.kt). Change the `WAKE_TIME` value, rebuild, and reinstall.

To block additional apps, add their package names to [`accessibility_config.xml`](app/src/main/res/xml/accessibility_config.xml) and update the package check in [`BlockerService.kt`](app/src/main/java/com/slacklock/BlockerService.kt).
