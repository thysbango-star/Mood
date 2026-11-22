# Mood

Rovian's wireless casting and screen-sharing platform. Native Android app (Kotlin + Jetpack Compose).

## Get the APK

Every push to `main` is built by GitHub Actions (`.github/workflows/android.yml`):

- **Phone-friendly:** the rolling pre-release **`latest-debug`** under *Releases* always holds the newest `mood-debug.apk`.
- **Per build:** each workflow run also attaches the APK as the `mood-debug-apk` artifact.

Builds are signed with the committed debug key (`app/debug.keystore`), so each new APK installs as an update over the previous one. It is a debug key only and must never be used for a Play Store release.

## Launch animation

The official Mood launch animation is a self-contained component in `app/src/main/java/com/rovian/mood/brand/`:

| File | Role |
|---|---|
| `MoodLogoGeometry.kt` | Vector geometry of the `mood` wordmark, measured from the master artwork |
| `launch/MoodLaunchTimeline.kt` | All beats (ms) and easing curves — tune timing here |
| `launch/MoodLaunchChoreography.kt` | Pure `(time, canvas) → frame` maths, unit-tested |
| `launch/MoodLaunchAnimation.kt` | Compose renderer (`MoodLaunchAnimation(...)`) |
| `MoodWordmark.kt` | Static wordmark from the same geometry |

The choreography runs in five phases: the wordmark fades in, the capsule travels through the letters, it expands to fill the screen, it contracts back into the 'o', and the overlay dissolves into the app. Rendering uses scaled vector paths on the hardware canvas, so there is no video and no bitmaps. Values update in the draw phase only, so playback causes no recomposition.

Startup work (`MoodStartup`, registered in `MoodApp`) runs in parallel from process start. The home UI composes underneath the animation from the first frame. The overlay waits at most 1.5 s for startup readiness after its last beat.

When it plays:

- on every fresh launch
- when Mood is reopened after at least 60 s in the background (`REPLAY_AFTER_BACKGROUND_MS` in `MainActivity`; set it to 0 to replay on every return)
- when restored after the system killed the process

It does not replay on rotation. With system animations turned off (Accessibility → Remove animations), it resolves straight to the app.

## Build locally

```
./gradlew testDebugUnitTest assembleDebug
```

Requires JDK 17 and the Android SDK (API 35).
