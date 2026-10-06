# Slack Lock

An Android self-binding app that prevents opening Slack, your **work** Gmail account, or both, for a chosen timer duration.

The app is intentionally narrow: one button, a choice of what to lock, one duration picker, one confirmation, and one Accessibility service. During an active lock, opening a locked app sends you back to the home screen. Slack and work Gmail have independent timers.

**Work Gmail only.** Work and personal Gmail are the same Android app, so Slack Lock tells them apart by the account Gmail is showing. You set your work address (or work domain, e.g. `@example.org`) once; during a Gmail lock, that account is blocked and every other Gmail account stays usable.

This does not force-stop apps, mute notifications, or block Slack/Gmail in a browser. Pair it with Android Focus/DND or app notification settings if notifications are the real trigger.

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

Open **Slack Lock**, tap the button, tick what to lock (Slack, Work Gmail, or both), choose a timer, and confirm. The first time you pick Work Gmail you'll be asked for your work address or domain; you can change it from the main screen any time a Gmail lock isn't running. Presets include 30 minutes, 1 hour, 2 hours, 4 hours, 1 day, 3 days, 7 days, 14 days, and the original "until next 6 AM" mode. You can also choose a custom days/hours/minutes timer up to 14 days.

While one thing is locked you can still start a lock on the other. Starting a lock never shortens one that's already running.

There is no in-app undo while a lock is active. To stop early, leave the app and either disable **Slack Lock** in Android Accessibility Settings or uninstall the app.

---

## Safety model

Slack Lock uses Accessibility only for two deterministic rules:

> If the Slack Android app opens during a Slack lock, perform Android's global Home action.
>
> If Gmail is showing your work account during a work Gmail lock, perform Android's global Home action.

The service configuration is deliberately limited:

- With no Gmail lock running, it receives only `typeWindowStateChanged` events from `com.Slack`, as before.
- During a Gmail lock it also receives window-change events from other apps (to notice you left Gmail and recheck the account next time) and Gmail content-change events (to catch account switches).
- It reads window content from Gmail only, only during a Gmail lock, and only looks at Gmail's account button (the avatar in the top-right). It never reads email content, and never reads other apps' screens.
- Cannot perform gestures.
- While a locked app is being sent home, or while Gmail's account is still being checked, it draws a plain full-screen cover (an accessibility overlay) so locked content doesn't flash up. The cover never takes touches and removes itself within 4 seconds at most.
- Does not request network access.
- Stores nothing except your lock timers, the work account setting, and whether the last Gmail check saw the work account.

This app is not presented as an accessibility tool for people with disabilities. It is a personal automation/self-binding tool, so the app includes a prominent in-app disclosure and requires explicit consent before sending you to Accessibility Settings. Upgrading from a Slack-only version shows the updated disclosure again.

### Work Gmail: how it decides, and its limits

- **Opening Gmail during a lock** briefly shows a cover until the account is confirmed. For a personal account that usually happens before the cover is even drawn; for the work account the cover stays up through the Home animation. Android only reports a window once it is on screen, so a frame or two can still slip through.
- **Inbox and most list screens** show the account button, so the account is known immediately.
- **Screens without the account button** (an open email, compose): once Slack Lock has seen the account since Gmail was opened, it remembers it. If it hasn't (e.g. you tapped a Gmail notification and Gmail opened straight into an email), it waits 1.5 seconds and then presses Back, which takes you to that account's inbox, where it can check. During a Gmail lock that means personal emails opened from a notification bounce to the personal inbox once. That is deliberate: otherwise tapping a work notification would skip the lock.
- **"All inboxes"** mixes accounts under whichever account button is selected. Avoid it during a lock; it is not blocked when a personal account is selected.
- **Gmail notifications, widgets, and notification actions** (reply/archive from the shade) aren't blocked. Turn work-account notifications off in Gmail settings for the strongest effect.
- **Work profile:** if your work Gmail is in an Android work profile (briefcase icon), the same account check applies, provided your employer's device policy lets a personal-profile accessibility service see work-profile apps.
- Detection relies on Gmail's account button keeping its current accessibility label ("Signed in as …") or view id. If a Gmail update changes both, the main screen's "Last Gmail check" line stops updating — that's the sign it needs fixing.

---

## How it works

- `MainActivity` handles the single-screen UI, the Accessibility disclosure, the target and duration pickers, the work account setting, the final lock confirmation, and the visible enforcement status.
- `BlockerService` is an Android `AccessibilityService` that sends the device home when Slack opens during a Slack lock, or when Gmail shows the work account during a Gmail lock. It widens its event scope only while a Gmail lock is active.
- `BlockState` stores per-target lock expiry times and the work account, computes duration-based timers, and keeps the next local 6 AM shortcut.
- `WorkAccount` holds the pure matching logic: normalising the work address/domain and reading the signed-in account from Gmail's account button.

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

To block additional apps, add a `LockTarget` in [`BlockState.kt`](app/src/main/java/com/slacklock/BlockState.kt), handle its package in [`BlockerService.kt`](app/src/main/java/com/slacklock/BlockerService.kt), and give it a label in `MainActivity.targetName`.
