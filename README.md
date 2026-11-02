# Frianzo (new app)

A NEW, complete, native Android app for Frianzo — Jetpack Compose, **no WebView**.
This repo is separate from `frianzo-app` (the current live app). The current app
keeps running; this app goes public only after full testing and the owner's
phone test at each stage.

## Backend

Same live backend as the website: `https://api.frianzo.online` — same database,
same login, same data. No separate frontend hosting, no backend changes needed.

Key API fact (verified from the live backend code): login is **cookie based**.
The backend sets `vynzo_auth_token` as a cookie and only reads it back from
cookies — it ignores an `Authorization` header. This app therefore uses a
persistent `CookieJar` (see `data/Api.kt`); without it, login would "succeed"
and every later call would still be logged out.

## What is built (step by step)

- **Stage 1 — Login + Feed:** Google sign-in (native, via Credential Manager;
  the web client id is fetched at runtime from
  `GET /api/auth/google/native-config`, never hard-coded) and email OTP login
  (`POST /api/auth/email/request-code`, `POST /api/auth/email/verify-code`),
  plus the public home feed (`GET /api/posts`, 5 posts per page, Load more).
- **Stage 2 — Full app:** create post (text + photo), reels feed with video
  playback + like + view, chat list + thread (REST polling for now),
  notifications (list + mark read + mark all read), user search, own profile
  and other users' profiles with follow/unfollow and their posts.

## What is NOT in this app yet (later stages)

- Live chat delivery via socket (chat currently polls on open/send).
- Push notifications (needs a Firebase service-account key — an owner step).
- Reel upload, stories, post detail comments UI, settings, Blue Tick,
  AdMob SDK (deliberately not included yet), Play release build.

## Build (phone test APK)

Free tools only, no account needed from the owner:

```bash
# Android Studio (recommended) — open this folder, run on a phone/emulator.
# Or command line (needs JDK 17 + Android SDK, API 35):
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions (`.github/workflows/android.yml`) builds the same debug APK
on every push to `main` and uploads it as an artifact.

## Phone test (owner, stage by stage)

1. Install the debug APK from the Actions artifact (or from Android Studio).
2. Login with Google, then separately with email OTP — both must land on Feed.
3. Feed: posts load, Load more loads the next 5, images show.
4. Reels: tap a video to play/pause, like works.
5. Create a text post, then a photo post — both must appear in Feed.
6. Chat: open a conversation, send a message, it must appear.
7. Notifications, Search, Profile: each screen loads, follow/unfollow works.
8. Report anything that fails before the next stage starts.

## Rules

- Never publish to Play from this repo without the owner's explicit order.
- Never touch `frianzo-app`, the live website, or the live backend from here.
- No invented numbers, no earning promises, no AdMob until approved and ordered.
