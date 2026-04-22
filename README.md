# PodcastDiary

A personal Android app for [Divine Intervention
Podcasts](https://divineinterventionpodcasts.com) that tracks what you've
listened to, where you stopped, how many times you've played each episode, and
when.

- Syncs the site's RSS feed on launch and shows new episodes since last open.
- You download each episode on demand — nothing downloads in bulk.
- Background playback with lock-screen controls, variable speed (0.8–2.5x),
  30-second skip.
- Per-episode: last position, listened flag, play count, first/last listened
  timestamps, plus a log of every listening session.
- Flat newest-first list with category filter chips (USMLE Step 1, Step 2 CK,
  Step 3, etc.).
- Settings screen with a Kimi API key field, wired up for a future
  "generate questions from episode" feature.

## Install

1. Push to this branch — GitHub Actions builds a debug APK.
2. Open the Actions run on your phone's browser, download `app-debug.apk` from
   the artifacts.
3. Tap the downloaded APK. Android will prompt you to allow "Install unknown
   apps" for your browser — enable it, then install.

## Build locally

Requires JDK 17 and the Android SDK (`ANDROID_HOME` set, `cmdline-tools`
installed with `platforms;android-34` and `build-tools;34.0.0`).

```bash
./gradlew assembleDebug             # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest         # run unit tests
```

The first CI build generates the Gradle wrapper jar automatically; for local
builds run `gradle wrapper --gradle-version 8.9` once if you haven't already.
