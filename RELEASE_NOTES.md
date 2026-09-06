📢 Version v0.10.0-fork.1 - watch vibration, Reddit DMs fixed, screen wake made optional

🛑 **Two separate APKs** - one for the phone, one for the watch. Outside Google Play the watch app is never installed automatically, so install each on its own device. Both are debug-signed: they install over an existing debug build without losing your settings, but not over a release-signed one. 🛑

Special thanks to **H-IDE4PDA** for the original app - this fork only fixes what showed up on a Samsung Galaxy S25 Ultra paired with a OnePlus Watch 4.

## 🆕 What's new

### Main features

✅ **The watch vibrates for notifications now** - apps your phone marks as low priority used to reach the watch completely silently: no buzz, no dot on the watch face. On a wrist that is the same as never arriving. The watch now buzzes for every app you tick in the app filter, no matter what importance the phone gave the notification. You don't have to change anything on the phone, so apps you deliberately keep quiet there stay quiet there.

✅ **Reddit private messages arrive again** - they were being thrown away before they ever left the phone, because Reddit sends chat messages in a form the app treated as a duplicate. And when the watch itself refuses to display one, the app now shows it on the watch on its own - with the sender and the message text.

✅ **Screen wake is optional now** - you can have just the vibration and leave the screen dark. Previously the "Screen Wake" switch quietly turned off the entire app: no screen, but also no vibration and no sound. Leaving the screen off also saves noticeable watch battery, and there is nothing to look at anyway when a notification arrives silently.

✅ **No more hour-long silences while wearing the watch** - the app could decide the watch was off your wrist while it was on it, and suppress everything until something jolted it back. It now cross-checks with the watch lock before staying quiet.

✅ **Bursts of messages are no longer swallowed** - when several messages arrived within a couple of seconds, only the first one reached you. Every message buzzes now, while the screen still refuses to strobe.

### Also in the app

✅ **The app icon shows up on the phone** - it used to render as an empty grey circle on One UI.
✅ **New setting: vibrate the watch on a notification** - on by default, in Sound and vibration.
✅ **New setting: show notifications the watch never gets** - on by default.
✅ The "Sound Mode" section is now "Sound and vibration".
✅ The watch now records when it thinks it has been taken off, so this kind of problem can actually be diagnosed instead of guessed at.

## 💡 Tip for Samsung owners

If notifications from some app reach your watch silently while others are fine, check whether that app is set to **Silent** on the phone - long-press any of its notifications and look at the Alerts / Silent switch. On One UI that single switch overrides everything, including per-chat notification settings inside apps like Telegram: every notification from that app arrives at the watch stripped of sound, vibration and its watch-face dot.

## Install

```
adb install -r wake-my-watch-phone-0.10.0-fork.1.apk
adb install -r wake-my-watch-watch-0.10.0-fork.1.apk
```

The watch needs wireless debugging turned on in developer options. After pairing once, `adb mdns services` finds it again on its own.

Then grant the phone app notification access (Settings → Notifications → Special app access), and allow the watch app to post notifications if you want it to show the ones the watch drops.
