# trilium-client

An independent Android client for [Trilium Notes](https://github.com/TriliumNext/Trilium), using its ETAPI. Native Jetpack Compose navigation surrounds a bundled WebView editor for HTML and Markdown. The app connects to your existing Trilium server; it does not run a server on the phone.

Version **0.4.0** adds on-device connection setup. The same APK can connect to your own server; no server address, password or token is compiled into it.

## Features

- Browse the note tree, including clones, and search titles and content.
- Create empty folders (native Trilium book notes), including offline.
- Create and edit HTML, Markdown and source notes. The bundled CKEditor provides visual editing; Markdown stays Markdown when saved.
- Download visible notes and attachments for offline reading, editing and search. Local drafts survive restarts and sync when connectivity returns.
- Queue deletions offline, with confirmation and retries. Deleting a note removes its clones; descendants with another parent remain available. There is no undo in the client.
- Preview images, PDF, audio, video and text attachments; save, share or open files with Android apps.
- Follow internal note links, use system light/dark themes and return to the previous position in folders and search results.

Initial offline download needs a connection. Android schedules background work and may delay it. Conflict handling preserves local edits, but ETAPI has no atomic conditional write, so simultaneous edits from multiple clients still have a race between the version check and save.

Canvas, relation maps, encrypted notes and server widgets do not have full native support. Unsupported special notes show an explanation and an option to open Trilium; encrypted notes must be unlocked there. Mermaid is edited as source. Server commands and widgets are not executed by this client.

## Screenshots

All screenshots use synthetic notes.

| Library | Editor |
| --- | --- |
| ![Library in light theme](docs/screenshots/list-light.png) | ![Editor in dark theme](docs/screenshots/editor-dark.png) |

More examples: [connection setup](docs/screenshots/connection-dark.png), [creating a folder](docs/screenshots/new-folder-light.png), [Markdown](docs/screenshots/markdown-reader.png), [offline library](docs/screenshots/offline-list-light.png), [sync details](docs/screenshots/sync-details.png), [delete confirmation](docs/screenshots/delete-confirmation-dark.png).

## Build

Requires **JDK 17**, **Android SDK 36** and the included Gradle wrapper. The app supports **Android 9 or newer** (API 28) and targets API 36. Set `ANDROID_HOME` or create an ignored `local.properties` with `sdk.dir` pointing to your SDK.

```sh
./gradlew :app:assembleDebug
```

Install the APK, enter your Trilium server address and password, and tap **Connect**. The app uses the [official ETAPI login](https://docs.triliumnotes.org/rest-api/etapi/) to obtain a token and verifies API access before saving the connection. Alternatively, paste an ETAPI token created in Trilium's **Options → ETAPI**. No username is needed.

Use the server's full HTTPS address, including a reverse-proxy subpath if applicable. HTTP requires an explicit opt-in on the connection screen. TLS certificates must be valid; the app does not bypass certificate checks or proxy CAPTCHA/SSO pages. It reports these cases instead of treating an HTML page as note data.

The password is not saved. The token is encrypted using Android Keystore; local notes and attachments remain in the app's private storage. After the first sync, restarting offline opens the existing library. The connection button in the library opens server details and sign-out. Sign-out forgets the token and stops that session's work; cached notes and queued edits stay on the phone and return when reconnecting to the same server URL. Different server URLs use separate caches and queues. Uninstalling the app deletes this local data; Android backup is disabled.

When upgrading from the old personally configured app, the first connection asks whether to attach its existing local copy to this server. Only confirm for the original server: this allows queued changes to sync. The original files are retained for recovery. Import never overwrites another queue with unsent changes.

`private.properties` is no longer read by the build. The old `-PdemoBuild=true` option remains accepted only to ignore local release signing settings; it does not alter connection behavior.

To sign your own release, copy `signing.properties.example` to `signing.properties` and configure your own PKCS#12 key. Then run `./gradlew :app:assembleRelease`. Without signing configuration, release output is unsigned. Keep the same key for future updates if you want to retain installed app data. The existing application ID is retained for update compatibility; changing the repository name does not change the installed package.

## Validation and contributing

See the [validation record](docs/acceptance.md) for the tested scope and known gaps, and [CONTRIBUTING.md](CONTRIBUTING.md) for local checks. A [ready-to-enable CI workflow](ci/build.yml) builds and tests the runtime-configured APK. GitHub Actions is not enabled yet because the current publishing credential lacks the `workflow` scope; see [CI setup](ci/README.md).

## License

Project code is licensed under [GPL-3.0-or-later](LICENSE). Bundled dependencies retain their own notices and licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the editor vendor [notice](app/src/main/assets/editor/vendor/NOTICE.txt).
