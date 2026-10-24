# trilium-client

An independent Android client for [Trilium Notes](https://github.com/TriliumNext/Trilium), using its ETAPI. Native Jetpack Compose navigation surrounds a bundled WebView editor for HTML and Markdown. The app connects to your existing Trilium server; it does not run a server on the phone.

Version **0.4.2** preserves literal line breaks in imported HTML paragraphs when reading and editing. It includes on-device connection setup and signed release automation. The same APK can connect to your own server; no server address, password or token is compiled into it.

## Features

- Browse the note tree, including clones, and search titles and content.
- Create empty folders (native Trilium book notes), including offline.
- Create and edit HTML, Markdown and source notes. The bundled CKEditor provides visual editing; Markdown stays Markdown when saved.
- Download visible notes and attachments for offline reading, editing and search. Local drafts survive restarts and sync when connectivity returns.
- Queue deletions offline, with confirmation and retries. Deleting a note removes its clones; descendants with another parent remain available. There is no undo in the client.
- Preview images, PDF, audio, video and text attachments; save, share or open files with Android apps.
- Follow internal note links, use system light/dark themes and return to the previous position in folders and search results.

Initial offline download needs a connection. Android schedules background work and may delay it. Conflict handling preserves local edits, but ETAPI has no atomic conditional write, so simultaneous edits from multiple clients still have a race between the version check and save.

Large binary attachments download directly to disk. Text/API responses and inline WebView resources are limited to **20 MiB per response**; larger responses produce an error. The text library and its index currently share an in-memory JSON cache, so very large libraries can be slow or memory-heavy. These limits are not a promise to support attachments or libraries of arbitrary size.

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

Public inline images must use HTTPS. Absolute HTTP image URLs, including URLs on an HTTP-only Trilium server, are not supported by the embedded editor; relative note resources use authenticated ETAPI access.

The password is not saved. The token is encrypted using Android Keystore; local notes and attachments remain in the app's private storage. After the first sync, restarting offline opens the existing library. The connection button in the library opens server details and sign-out. Sign-out forgets the token and stops that session's work; cached notes and queued edits stay on the phone and return when reconnecting to the same server URL. Different server URLs use separate caches and queues. Uninstalling the app deletes this local data; Android backup is disabled.

When upgrading from the old personally configured app, the first connection asks whether to attach its existing local copy to this server. Only confirm for the original server: this allows queued changes to sync. The original files are retained for recovery. Import never overwrites another queue with unsent changes.

`private.properties` is no longer read by the build. The old `-PdemoBuild=true` option remains accepted only to ignore local release signing settings; it does not alter connection behavior.

To sign your own release, copy `signing.properties.example` to `signing.properties` and configure your own PKCS#12 key. Then run `./gradlew :app:assembleRelease`. Without signing configuration, release output is unsigned. Keep the same key for future updates if you want to retain installed app data. The existing application ID is retained for update compatibility; changing the repository name does not change the installed package.

## Releases

The [release workflow](.github/workflows/release.yml) builds a signed APK when a stable `vMAJOR.MINOR.PATCH` tag is pushed. It runs tests and lint first, then verifies the package version and signing certificate before publishing the APK, corresponding source archive and `SHA256SUMS` to [GitHub Releases](https://github.com/SomeVlad/trilium-client/releases). While the repository is private, the releases require repository access too.

The source archive includes the exact application revision, the editor's preferred source and locked production packages, and source jars for the resolved Android runtime libraries. To create it locally after committing the release sources:

```sh
./gradlew :app:writeReleaseDependencyManifest
scripts/package-source.sh HEAD release-assets/trilium-client-source.tar.gz
```

See [CI setup and signing](ci/README.md) for the required repository secrets and tag procedure. The signing key is never included in release assets.

## Validation and contributing

See the [validation record](docs/acceptance.md) and [publication review](docs/publication-review.md) for the tested scope and known gaps, and [CONTRIBUTING.md](CONTRIBUTING.md) for local checks. The [build workflow](.github/workflows/build.yml) runs JVM tests, lint and a debug build on pushes to `main` and pull requests without release-signing secrets.

## License

Project code is licensed under [GPL-3.0-or-later](LICENSE). Bundled dependencies retain their own notices and licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the editor vendor [notice](app/src/main/assets/editor/vendor/NOTICE.txt).

The connection and server settings screens provide **About** with the project license and packaged dependency notices, available offline.
