# Contributing

This is an early source-release preview. Small, focused fixes and clear reports of reproducible problems are welcome. For a larger change, describe the intended behavior in an issue before investing in an implementation.

## Local setup

Install JDK 17 and Android SDK 36, then set `ANDROID_HOME` or configure `sdk.dir` in an ignored `local.properties`. Use the checked-in Gradle wrapper:

```sh
./gradlew -PdemoBuild=true :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

The demo flag ignores local server and signing settings. It produces a disconnected app with a dummy token and a non-routable endpoint, not a working sample server. To connect to your own Trilium instance, follow the [README](README.md); keep configured APKs private because they contain your connection credentials.

With an emulator or device connected, Android instrumentation tests can be run with:

```sh
./gradlew -PdemoBuild=true :app:connectedDebugAndroidTest
```

Use an isolated test device or emulator: UI tests exercise app storage and lifecycle behavior. Use only synthetic fixtures and disposable test data. Record the Android version and relevant steps when reporting a UI failure. The existing [validation record](docs/acceptance.md) explains what has already been checked and which gesture behavior remains unverified.

## Changes and reports

Keep changes focused and explain the user-visible result. Include the checks you ran and any remaining limitation. Test behavior that could lose drafts, change sync ordering or delete notes; do not rely only on screenshots for those changes.

Do not submit tokens, private server URLs, signing keys, connected APKs, real note content or fixtures derived from a private library. Use synthetic titles, bodies and attachments in tests, logs and screenshots. Remove identifying details before opening an issue or pull request.

Contributions are made under the project's GPL-3.0-or-later license. Preserve dependency license notices when updating bundled assets.
