# Memex for Android

A native Android client for memex, the voice-and-text capture and agentic task
assistant. It is a Jetpack Compose / Material 3 app that talks to a memex server
over its HTTP API.

## What it does

**Capture.** A quick-capture sheet handles four kinds of input:

- **Voice** — record with a live waveform meter, upload the audio, and poll until
  the server finishes transcribing and enriching it.
- **Photo** — pick an image, which the app scales and re-compresses to stay under
  1 MB before upload.
- **Text and links** — type a note, or paste a URL with an optional title and
  comment.
- **Share sheet** — send text, a link, or an image into memex from any other app.
  The share lands in the same capture sheet, pre-filled, so you can add a caption
  before submitting. Drafts survive being backgrounded or the process being
  killed.

**Read and act.** Five tabs across the bottom:

| Tab | What's there |
| --- | --- |
| Feed | Notes with tag filters, full markdown bodies, audio transcripts, and step-by-step replay of the agent trace that produced each note |
| Tasks | Open / done / dropped, with checkbox toggles that apply instantly and roll back if the server rejects them |
| Approvals | Actions the agent wants to take, approved or rejected in one tap |
| Runs | History of scheduled routine runs, each with its own agent trace to inspect |
| Chat | Conversation with the assistant, streamed over SSE |

Settings is reached from the Feed's top bar.

## Connecting it to a server

Everything is configured in **Settings**:

1. **Server URL** — the base URL of your memex deployment. A default is
   pre-filled; change it to point somewhere else.
2. **Bearer key** — your device key, pasted by hand.
3. **Save & Test Connection** — verifies the app can actually reach the server
   before you rely on it.

The key is stored in `EncryptedSharedPreferences` backed by the Android Keystore
(AES256-GCM) and never leaves the app except as an `Authorization` header to the
server URL you configured. Backups are disabled for the app, and the credential
store is excluded from data extraction. Keys are never bundled into the APK, and
the app will not accept a key through an intent or a command-line argument.

## Building

**You must point `JAVA_HOME` at the Android Studio JBR.** A system JDK 25 will
fail the Gradle build; the project targets Java 17 source compatibility and is
built with the bundled JetBrains Runtime.

```bash
# Path to the JBR shipped inside your Android Studio install, e.g.
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"

./gradlew testDebugUnitTest   # JVM unit tests
./gradlew assembleDebug       # debug APK
```

Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true`, so
Android framework classes are stubbed. Anything that needs a real `Bitmap`,
`ContentResolver`, or Compose tree lives in `app/src/androidTest/` and needs a
connected device or emulator.

Requires the Android SDK (`compileSdk` 36, `minSdk` 26) with `ANDROID_HOME` or
`local.properties` pointing at it.

## Layout

```
app/src/main/java/com/memex/android/
  data/api/          Retrofit service, DTOs, auth interceptor, SSE client
  data/local/        Server URL and device-id preferences
  data/repository/   Notes, captures, chat
  data/security/     Keystore-backed key storage
  ui/                One package per screen (feed, tasks, approvals, runs, chat,
                     settings, capture) plus shared components and theme
  util/              Audio recording, image compression, share-intent parsing
```

## Further reading

- [`docs/contracts.md`](docs/contracts.md) — the frozen server API contracts the
  app is written against.
- [`docs/image-capture-bug.md`](docs/image-capture-bug.md) — investigation notes
  on an image-capture decode failure.
