# "Unable to decode image stream" on image capture

**Status:** root cause not proven. Contributing defects found and fixed; the
error message now distinguishes the two failure modes so the next occurrence
identifies itself.

**Reported:** a capture from the Android app produced a note in the memex feed
summarising an error log containing "Unable to decode image stream", around
2026-08-29 04:37 UTC.

## Where the message comes from

Exactly one place: `DefaultImageCompressor.compressStream` in
[`ImageCompressor.kt`](../app/src/main/java/com/memex/android/util/ImageCompressor.kt).
It reaches the user via `CaptureViewModel.onImageUriSelected`, which wraps it as
`"Failed to process image: <message>"`.

Before this change the string was raised in two very different situations:

1. The image header genuinely could not be parsed — a real format problem.
2. `BitmapFactory` read a source that had **no bytes in it at all**, left
   `outWidth`/`outHeight` at zero, and the code blamed the format.

Case 2 is far more likely here, and it points away from the image and towards
how the app stages the file before compressing it.

## Why the source can end up empty

There is no camera path in the app — "capturing an image" means either the
gallery picker (`ActivityResultContracts.GetContent()`) or an image arriving
through the share sheet. Both funnel into `CaptureViewModel.onImageUriSelected`,
which copies the `content://` URI to a private cache file
(`draft_source_image_<gen>.bin`) and then compresses **that file**, not the URI.

Three ways that staging could fail without anyone noticing:

1. **The content resolver returned nothing.**
   `contentResolver.openInputStream(uri)?.use { … }` — a null return skipped the
   entire copy silently. No exception, no file.
2. **The promotion failed.** The copy lands in a `.tmp` file which is then moved
   into place. `Files.move` was wrapped in `catch (_: Exception) {}` with a
   fall-back to `renameTo`, whose `false` return value was discarded. If both
   failed, the code carried on as though the file were there.
3. **A zero-byte file counted as a real one.** The restore-after-process-death
   path and the draft-snapshot predicates both tested `sourceFile.exists()`. An
   empty or truncated file passes that test, so a dead draft was resumed and fed
   straight into the compressor.

In all three, the draft was still committed with `sourceFileName` set and
`pendingSourceUri` cleared — so the state said "the image is staged" while the
disk said otherwise, and the first thing to notice was the compressor.

A fourth possibility that the code cannot rule out: a **stale `content://`
grant**. `GetContent()` and share-sheet URIs are not persistable, and the app
never calls `takePersistableUriPermission`. `restoreDraftFromDisk` re-runs
`onImageUriSelected` with a `pendingSourceUri` read back from disk, which after
a process restart is a URI the app is no longer allowed to open. That path now
raises a `SecurityException` with its own message rather than being mistaken for
a decode failure, but it is still a real gap — see below.

## What changed

- `compressStream` counts the bytes the bounds pass actually read. Zero bytes
  now reports `"Unable to decode image stream: source is empty"`; a non-empty
  but unparseable source still reports `"invalid or unsupported image format"`.
  Two tests cover the split.
- `onImageUriSelected` treats a null `openInputStream` as an error instead of
  skipping the copy, and verifies the promoted `.bin` file exists and is
  non-empty before committing the draft.
- The restore path and the draft-snapshot predicates require
  `isFile && length() > 0` rather than `exists()`, so a zero-byte staged file is
  treated as absent.

## Why this is not called a fix

None of it is confirmed against the actual failure. The constraints:

- Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true`, so
  `BitmapFactory` is stubbed and returns nothing. **The real decode path has no
  automated coverage at all** — only its error branches do. This is the single
  biggest reason a defect here could ship unnoticed.
- Reproducing needs a device, which was out of scope for this session.

## Next steps

1. Reproduce on the Pixel and read the new message. "source is empty" confirms
   the staging theory; "invalid or unsupported image format" moves suspicion to
   the image itself (HEIC or a motion photo from Google Photos would be the
   first thing to check).
2. Decide what to do about non-persistable URIs. Resuming a draft by re-opening
   a `content://` URI after process death cannot work. Either drop the
   `pendingSourceUri` resume path entirely and rely only on the staged `.bin`
   file, or copy the bytes before the activity that received the grant goes
   away.
3. Add instrumented coverage for `DefaultImageCompressor` against real image
   files — a small JPEG, a HEIC, an image with EXIF rotation, and a zero-byte
   file — so this path stops being untested.
