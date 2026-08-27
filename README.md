# CamStream

An ad-free, open-source Android app that streams your phone's camera to your computer
as a webcam: a from-scratch replacement for the closed-source, ad-supported DroidCam
app that plugs straight into the existing open-source
[`droidcam-linux-client`](https://github.com/dev47apps/droidcam-linux-client) /
`v4l2loopback-dc` PC pipeline, unmodified.

No DroidCam code is reused anywhere in this project. The wire protocol was
independently recovered by reading `droidcam-linux-client`'s own GPL-2.0 source
(published specifically so people can build interoperable clients) and reimplemented
from scratch here. See [`PROTOCOL.md`](PROTOCOL.md) for the full wire-format writeup
and provenance notes.

## Features

- CameraX-based capture, JPEG-encoded, streamed over the DroidCam-compatible TCP
  protocol on port 4747
- Foreground service that keeps streaming while the screen is off or the app is
  backgrounded
- Front/back camera switching mid-stream, without dropping the PC connection
- Minimal, modern UI: connection status, the address to enter on the PC side
  (tap to copy), and start/stop/switch controls. Deliberately no camera preview in
  the UI itself, since rendering one would cost battery for no benefit
- Works with any client that speaks the protocol: `droidcam-cli`, the `droidcam` GUI,
  or a browser video call (e.g. Google Meet) via the resulting `v4l2loopback` device

## Requirements

**On the phone:**
- Android 8.0 (API 26) or newer
- Developer Options → USB debugging enabled, to sideload the debug build

**On the computer:**
- [`droidcam-linux-client`](https://github.com/dev47apps/droidcam-linux-client) and
  its `v4l2loopback-dc` kernel module installed and working (e.g. on Arch/CachyOS,
  `paru -S droidcam v4l2loopback-dc-dkms`). This project doesn't touch or replace that
  pipeline; it's purely a new client for the phone side.
- Both devices on the same LAN/WiFi (this protocol has no NAT traversal or relay).

**To build:**
- JDK 17
- Android SDK (`platform-tools`, `platforms;android-34`, `build-tools;34.0.0`, or
  whatever the Gradle build reports it needs), installable via `sdkmanager` or
  Android Studio

## Build

```
git clone <this-repo>
cd camstream
./gradlew assembleDebug
```

`local.properties` (gitignored) must contain `sdk.dir=<path-to-your-android-sdk>`, or
set `ANDROID_HOME`/`ANDROID_SDK_ROOT` in your environment; Android Studio does this
automatically on first sync.

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Run

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Launch the app, grant the camera (and, on Android 13+, notification) permission,
   tap **Start**. The screen shows the phone's LAN IP and port `4747`.
3. On the computer:
   ```
   droidcam-cli <phone-ip> 4747
   ```
   or launch the `droidcam` GUI and enter the same address. Confirm a live feed
   appears on the resulting `/dev/videoX` in `cheese`, OBS, or a browser webcam test
   page.
4. To use it in a video call: the `v4l2loopback` device identifies itself as
   **"Droidcam"** in any camera picker. In Google Meet's pre-join screen, for example,
   pick it there to preview before joining.

Tap **Front/back camera** any time to switch; it rebinds without dropping the
connection or changing the resolution the PC side already negotiated.

## Project layout

- `app/src/main/java/dev/camstream/app/CameraCapture.kt`: CameraX `ImageAnalysis` →
  YUV_420_888 → JPEG, with front/back switching.
- `app/src/main/java/dev/camstream/app/LatestFrameHolder.kt`: single-slot
  latest-frame handoff (drop-frame-if-busy), so a slow network never backs up frames.
- `app/src/main/java/dev/camstream/app/StreamingService.kt`: foreground service
  (`camera` type), `ServerSocket(4747)`, and the wire protocol itself.
- `app/src/main/java/dev/camstream/app/MainActivity.kt`: the entire UI.
- `PROTOCOL.md`: the wire format this app implements.

## Troubleshooting

- **`EADDRINUSE` / "Server socket failed" in logcat, app stuck showing "Streaming"
  with no PC connection working:** something else on the phone already has port
  4747, most likely the official DroidCam app, if it's also installed and left
  running. Force-stop it (`adb shell am force-stop com.dev47apps.obsdroidcam`, or from
  Android settings), then retry. CamStream resets its own state automatically when
  the bind fails, so a fresh Start is enough.
- **PC client prints `Connection reset! Is the app running?`:** check logcat for the
  actual `StreamingService` error to see whether the port bind failed for a different
  reason (another instance already running, an `adb reverse` conflict, etc.).

## Not implemented

- `avc`/H.264 codec path (currently `jpg` only)
- Audio (`CMD /v2/audio`)
- USB/`adb reverse` transport (WiFi only for now)
- Zoom/AF/flash control channel (`CMD /v1/ctl?...`)

Contributions welcome: PRs and issues both.

## License

MIT. See [`LICENSE`](LICENSE).

This project only *talks to* `droidcam-linux-client` over the network protocol
described in [`PROTOCOL.md`](PROTOCOL.md); no GPL-licensed source is included, linked,
or bundled here, so its GPL-2.0 license doesn't extend to this codebase. The two
projects are independent and separately licensed; this one just happens to be
wire-compatible with the other.

## Credits

- [dev47apps/droidcam-linux-client](https://github.com/dev47apps/droidcam-linux-client)
  (GPL-2.0): the PC-side pipeline this app is compatible with, and the source read to
  recover the wire protocol.
- [google/material-design-icons](https://github.com/google/material-design-icons)
  (Apache-2.0): icon glyphs used in the UI.
