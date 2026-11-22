# MoodPlay

Rovian's casting platform: find a movie, watch it, and send it (or your own videos, photos and music) to a TV. Native Android app (Kotlin + Jetpack Compose).

## Get the APK

Every push to `main` is built by GitHub Actions (`.github/workflows/android.yml`):

- **Phone-friendly:** the rolling pre-release **`latest-debug`** under *Releases* always holds the newest `mood-debug.apk`.
- **Per build:** each run also attaches the APK as the `mood-debug-apk` artifact.
- **Screens:** a second job renders the real UI with Paparazzi and pushes the PNGs to the `ci-screens` branch.

Builds are signed with the committed debug key (`app/debug.keystore`), so each new APK installs over the previous one. It is a debug key only and must never be used for a Play Store release.

## Casting architecture

```
cast/
  CastAdapter.kt          protocol-agnostic contract (discovery, session, playback)
  MoodCastEngine.kt       MoodCast Engine: merges adapters, picks pathway, compat-checks, serves files
  Compatibility.kt        format rules (Cast spec + what DLNA TVs declare), device grouping
  googlecast/             Google Cast — official Cast Application Framework + MediaRouter
  dlna/                   DLNA/UPnP AV — SSDP discovery, device description, SOAP control, polling
  media/LocalMediaServer  token-protected HTTP server with Range support so TVs can stream phone files
  media/MediaResolver     reads picked files, converts photos (HEIC→JPEG), validates links
  mirror/SystemMirroring  hands off to Android's own Cast screen; reports the mirrored display
  mirror/DisplayAdapter   TV display mode: while mirroring, Mood draws a TV-native UI on the TV
                          (Android Presentation API, TV's own resolution) and plays media there
  service/                foreground service, alive only while a session exists
catalog/
  MovieCatalog.kt         provider interface + MoodFit stream selection
  OpenFilmsCatalog.kt     built-in, openly licensed films (Blender Foundation, CC BY)
```

**Truthful state.**
- Google Cast states come only from `SessionManagerListener` and `RemoteMediaClient` callbacks.
- A DLNA TV shows as "Connected" only after it actually answers an AVTransport request. It is then polled every second, so changes made with the TV remote show up in Mood.
- Controls appear only when the receiver reports it supports them (for example, DLNA Pause appears only if the TV lists it).

**MoodFit.**
- With media casting the TV fetches the stream itself, so the phone isn't in the video path.
- MoodFit sends an adaptive HLS/DASH stream when the receiver plays one. The TV then picks the quality that suits its own screen and connection.
- Otherwise MoodFit sends the best fixed version the TV says it can play.

**Not built yet:**
- **Branded Cast receiver.** It needs a Receiver App ID from the Google Cast Developer Console. Until then Mood uses Google's Default Media Receiver (`CastOptionsProvider.RECEIVER_APP_ID`).

## Test matrix

| Scenario | Status | Notes |
|---|---|---|
| Unit: HTTP server ranges/HEAD/404/token revoke | ✅ CI | `LocalMediaServerTest` |
| Unit: SSDP, device description, SOAP round-trip vs fake renderer, faults | ✅ CI | `DlnaProtocolTest` |
| Unit: format compatibility, device grouping, MoodFit | ✅ CI | |
| Google Cast receiver (discover/connect/play/seek/volume) | ⏳ needs hardware | Built on the official SDK; not yet run against a TV |
| DLNA TV (Samsung/LG/Sony etc.) | ⏳ needs hardware | SOAP flow verified against a fake renderer only |
| Phone files → TV via local server | ⏳ needs hardware | |
| Receiver unavailable / TV goes offline | ⏳ needs hardware | Cast: SDK callbacks; DLNA: Reconnecting after 3 failed polls, Disconnected after 15 s |
| Different Wi-Fi networks | ⏳ | Discovery state shows "join your TV's Wi-Fi" |
| Sender goes offline | ⏳ | Cast resumes the session via the SDK; DLNA TV keeps playing remote URLs |
| Unsupported format | ✅ logic in CI | Blocked before sending, with the reason |
| Network permission denied | n/a on targetSdk 35 | Discovery uses only install-time permissions |
| TV display mode while mirroring | ⏳ needs hardware | Works when the phone's mirroring exposes a presentation display (Miracast/Smart View do); the network check lists displays |
| Nude OS sender | ❌ not built | |

## Launch animation

Lives in `brand/` (`MoodLogoGeometry`, `launch/MoodLaunchTimeline`, `launch/MoodLaunchChoreography`, `launch/MoodLaunchAnimation`). It plays on launch and when Mood is reopened after 60 s in the background (`REPLAY_AFTER_BACKGROUND_MS`).

## Build locally

```
./gradlew testDebugUnitTest assembleDebug
```

Requires JDK 17 and the Android SDK (API 35).
