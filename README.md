# PhotonSync for Android

Your phone screen's colours, live on a Bluetooth LED strip.

**[Download the latest APK](https://github.com/SuhasKalyanKumar/PhotonSync-Android/releases/latest)**

Open the file on your phone and allow **Install unknown apps** when Android asks.

## Features

- **Screen sync:** the strip follows the colour on your screen, even in the background
- **Auto connect:** pick your strip once and it connects by itself from then on
- **Screen follow:** the strip turns off with your screen and comes back when it turns on
- **Timer:** daily on and off times that run on the strip's own clock, so your phone can be off
- **Voice control:** works offline, and the mic stays off until you turn it on
- **Rainbow mode:** a slow, calm glide through seven colours

## Voice commands

| Say | What it does |
| --- | --- |
| On, Off | Turns the strip on or off |
| Sync | Goes back to screen sync |
| Rainbow | Starts a slow colour glide. Say Sync to go back |
| Low, Medium, High | Sets the brightness |
| Red, Cyan, Purple... | Sets a colour |

The voice model is about 40 MB and downloads the first time you turn on the mic.

## Skip the prompt after unlocking

Android 15 and newer ends screen sharing whenever the phone locks, then asks again. To skip that, turn on **PhotonSync no-tap sync** in Accessibility settings.

If the option is greyed out, open App info for PhotonSync, tap the ⋮ menu, then **Allow restricted settings**.

No-tap sync only reads your screen's average colour, and nothing is saved or sent anywhere. Colours update about 3 times a second, and force-stopping the app turns the option off.

## Good to know

- Most LED controllers take one connection at a time, so close PhotonSync on your PC first
- Apps that block screenshots, like Netflix or banking apps, show as black, so the strip goes dark
- Android 11 and older need Location on to scan for Bluetooth devices

## Build it yourself

Open this folder in [Android Studio](https://developer.android.com/studio) and press **Run**.

Also available [for Windows](https://github.com/SuhasKalyanKumar/PhotonSync). Fonts are [Michroma and Space Grotesk](app/src/main/assets/licenses), under the SIL Open Font License.
