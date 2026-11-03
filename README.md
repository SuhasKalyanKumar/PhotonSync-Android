# PhotonSync for Android
### Ambient Screen & Voice-Reactive BLE Engine

The phone version of [PhotonSync](https://github.com/SuhasKalyanKumar/PhotonSync). It mirrors your phone screen's average colour to a Bluetooth Low Energy RGB LED strip in real time and responds to offline voice commands.

## Features

* **Real-Time Screen Sync:** Uses Android screen capture to stream ambient colour shifts to your strip. It keeps running in the background while you watch videos or play games.
* **Pick Your Strip Once:** Choose your LED strip from a list the first time. PhotonSync remembers it and connects by itself whenever it's on. Tap the strip button to switch.
* **Follows Your Screen:** The strip goes dark when the screen turns off and lights up the moment it turns back on.
* **Offline Voice Control:** Same commands as desktop ("On"/"Off", "Sync", "Low"/"Medium"/"High", colour names), powered by **Vosk**. The mic is **off by default** and you toggle it from the screen-sharing notification or the app. The voice model (~40MB) downloads the first time you turn the mic on.
* **Master Brightness, Telemetry Log and Catppuccin UI.** These match the desktop dashboard.

## Download

Grab the latest APK from [Releases](https://github.com/SuhasKalyanKumar/PhotonSync-Android/releases/latest), open it on your phone and allow **Install unknown apps** when Android asks.

## Skip the "Start sharing?" prompt after unlocking

Android 15+ ends every screen share when the phone locks and asks for consent again. To avoid that, tap **No-tap sync** in the app and turn on **PhotonSync no-tap sync** in Accessibility settings (Android 11+). If it's greyed out, open App info for PhotonSync, tap ⋮, then **Allow restricted settings**.

It only works out your screen's average colour. Nothing is saved or sent anywhere. Colours update about 3 times a second, so it's a little less smooth than screen sharing.

**Smoother alternative:** if your phone allows it, run this once from a computer with USB debugging on, and allow display over other apps. It keeps full-speed screen sharing and skips the prompt. Some phones, including recent OnePlus models, block it, and it only applies while the Accessibility option above is off.

```bash
adb shell appops set me.suhaskalyan.photonsync PROJECT_MEDIA allow
```

## Build & Run

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** this folder and let Gradle sync.
3. On your phone, enable **Developer options → USB debugging**, plug it in, and press **Run ▶**.

## Sharing the APK

```bash
gradlew.bat exportApk
```

This writes a signed `apk/PhotonSync-<version>.apk`. Send that file to friends. They open it and allow **Install unknown apps** when Android asks.

The signing key lives in `keystore/` and is gitignored. **Back it up.** Future updates must be signed with the same key, or friends will have to uninstall the app before installing a new version.

## Notes

* Most LED controllers accept **one connection at a time**. Close the desktop app before syncing from your phone.
* Apps that block screenshots (Netflix, banking apps) show up as black, so the strip goes dark while they're open.
* Android 11 and older need **Location** turned on for Bluetooth scanning.
