# muffuqOS

A speed-up toolkit and a custom home screen for Android TV, with a built-in AirPlay-compatible receiver for screen mirroring and audio.

The TV's firmware is never modified. muffuqOS disables unneeded services over ADB, tunes a few settings and replaces the stock home screen with its own launcher. Every change is recorded per device and can be undone with one command.

Türkçe sürüm: [README.tr.md](README.tr.md)

## What is in here

| Part | What it does |
| --- | --- |
| `launcher/` | The Android TV app: home screen, screen-mirroring receiver, lyrics visualiser and screensaver. Kotlin and Jetpack Compose for TV, written for TVs with 1 GB of RAM. |
| `tools/muffuq.ps1` | Windows toolkit: connect to the TV, status report, speed-up, install the launcher, restore. |
| `debloat/packages.txt` | The list of packages that can be disabled. Plain text, edit as you like. |
| `tools/dev/airplay_sender.py` | A test sender that speaks the real protocol, so the receiver can be tested without a phone. |

## Screen mirroring receiver

The receiver lives in `launcher/app/src/main/java/com/muffuq/os/airplay/` and is written in Kotlin:

- **Discovery:** advertises the TV over mDNS (`Advertiser.kt`).
- **Session control:** an RTSP server with a binary-plist parser (`AirPlayServer.kt`, `BPlist.kt`).
- **Pairing:** Ed25519 signatures and X25519 key agreement (`Pairing.kt`).
- **Video:** AES-CTR encrypted H.264, decoded on the TV's hardware decoder through MediaCodec (`VideoReceiver.kt`, `VideoPlayer.kt`).
- **Audio:** AES-CBC encrypted AAC-ELD for mirroring and ALAC for audio-only playback (`AudioReceiver.kt`, `AudioPlayer.kt`).
- **Clock sync:** an NTP client that keeps audio and video aligned with the sender (`NtpClient.kt`).
- **Volume:** the phone's volume buttons drive the TV's own volume (`TvVolume.kt`).

The protocol flow follows [UxPlay](https://github.com/FDH2/UxPlay). Not supported yet: H.265/4K mirroring and PIN access control.

## Speed-up toolkit

```powershell
.\tools\muffuq.ps1 -Command status   -Device 192.168.1.50
.\tools\muffuq.ps1 -Command speed    -Level recommended   # safe | recommended | aggressive
.\tools\muffuq.ps1 -Command launcher
.\tools\muffuq.ps1 -Command restore  -UninstallLauncher   # put everything back
```

- Packages are disabled with `pm disable-user`, never removed.
- The launcher is installed and verified to start before the stock launcher is touched.
- `backups/<device-id>.json` keeps the original value of every setting and every disabled package, so `restore` returns the TV to its exact previous state.

## Other features

- **Launcher:** favourites, sorting, hiding apps, and a live background that takes its colours from the focused app. Uses about 40 to 65 MB of memory on a 1 GB TV.
- **Stage:** shows the lyrics of the song playing on the TV, animated word by word, with a light that pulses to the beat.
- **Screensaver:** a large clock that shifts position over time to avoid burn-in.

## Build

Requires Android Studio (JDK and SDK 36) and the NDK.

```powershell
cd launcher
.\gradlew.bat dist    # -> dist\muffuqOS-launcher.apk
```

## Third-party code and licence

This project is released under the GNU General Public License v3.0; see [LICENSE](LICENSE).

- `launcher/app/src/main/cpp/playfair` is taken unmodified from UxPlay/playfair (GPL-3.0).
- `launcher/app/src/main/cpp/alac` is Apple's open-source ALAC decoder (Apache-2.0).
- The bundled Manrope font is licensed under the SIL Open Font License.

AirPlay is a trademark of Apple Inc. This project is not affiliated with or endorsed by Apple.
