# Clipboard

Open Clipboard from the feed toolbar or select Memex Clipboard in another app's
Share menu. The destination receives one text, URL, image, or file. Send is
explicit, text whitespace and original file bytes are preserved, and uploads
never pass through the durable capture compressor or draft store.

The new API must be deployed and enabled first. Use the existing Memex connection
settings/device key. Payloads stay in memory, except temporary files required by
Android sharing. Export files live under the private cache/clipboard directory;
old files are removed on subsequent use. App backups remain disabled.

Build and unit-test with a full JDK (17 or later):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Before release, run device tests with Chrome, Photos/screenshots, and Files:
verify exact command text and file hashes on a second device; cancel and retry a
share; rotate/recreate the activity; verify expiry, copy/open/share/delete/Keep;
and verify existing Memex durable sharing remains unchanged. General-file Keep
and background clipboard monitoring are intentionally absent.
