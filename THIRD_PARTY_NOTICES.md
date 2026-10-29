# Third-party software

Original trilium-client code is GPL-3.0-or-later. Third-party notices are preserved under their upstream terms. This list describes the principal runtime components; Gradle resolves their transitive dependencies from the versions declared in [app/build.gradle.kts](app/build.gradle.kts).

| Component | Upstream license | Source and notices |
| --- | --- | --- |
| CKEditor 5, 49.0.0 | GPL-2.0-or-later (the GPL option is used; GPLv3 is compatible with the project license) | [Source tag](https://github.com/ckeditor/ckeditor5/tree/v49.0.0), [bundled license](app/src/main/assets/editor/vendor/LICENSE.md), [copyright and provenance](app/src/main/assets/editor/vendor/NOTICE.txt) |
| Mermaid 11.17.2 | MIT | [Preferred source](https://github.com/mermaid-js/mermaid/tree/02d554400de909df3fa1c62b87dca5ef7df4ac97), [retained dependency notices](app/src/main/assets/diagrams/vendor/THIRD_PARTY_NOTICES.txt) |
| Excalidraw 0.18.1, React/React DOM 18.3.1 | MIT | [Preferred Excalidraw source](https://github.com/excalidraw/excalidraw/tree/a2ec2889babf7d2295469c6d90ebe77fae57df84), [upstream licenses](app/src/main/assets/diagrams/vendor/UPSTREAM_LICENSES.txt) |
| Excalidraw local fonts | SIL Open Font License 1.1 and MIT, as identified per font | [Retained font copyright and license notices](app/src/main/assets/diagrams/vendor/UPSTREAM_LICENSES.txt) |
| AndroidX, Jetpack Compose and Material Components | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Kotlin and kotlinx.coroutines | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp and Okio | Apache-2.0 | [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| jsoup | MIT | [Source and license](https://github.com/jhy/jsoup) |
| commonmark-java | BSD-2-Clause | [Source and license](https://github.com/commonmark/commonmark-java) |
| Gradle wrapper | Apache-2.0 | [Source and license](https://github.com/gradle/gradle) |

The CKEditor distribution also includes third-party components under MIT and ISC terms, listed in its bundled [LICENSE.md](app/src/main/assets/editor/vendor/LICENSE.md). The full copyright and license notices of its locked production dependency closure are retained in [THIRD_PARTY_LICENSES.txt](app/src/main/assets/editor/vendor/THIRD_PARTY_LICENSES.txt). This includes transitive modules, and some modules not present after bundling. The OkHttp public suffix resource retains its upstream notice inside the library.

## Bundled editor provenance

`ckeditor5.umd.js`, `ckeditor5.css`, and `ru.umd.js` are unmodified files from the official [49.0.0 distribution ZIP](https://cdn.ckeditor.com/ckeditor5/49.0.0/zip/ckeditor5-49.0.0.zip). The ZIP's SHA-256 is `cff5d58b096ffb726bba9be494e857c58c4f3606ebade4c998f8fa8d624a4948`. The preferred upstream source is the `v49.0.0` tag, resolved to commit `7e7dd1eda991d700c65061c00ee12ca2c1de5bdd`. [sources.lock.json](sources.lock.json) pins that source archive, the distribution, its three bundled files, and the production npm dependency closure from its upstream lockfile.

The surrounding Android integration, `editor.js`, and `editor.css` are this project's modifications. A CSS rule added on 2026-10-23 hides the floating promotional badge; the upstream JavaScript, copyright, and license files are retained. This documents the modification rather than claiming a CKSource commercial license or endorsement.

## Offline diagram renderer provenance

The read-only Mermaid and Excalidraw viewers use official renderers bundled locally, including Excalidraw's font files. [scripts/diagrams/package-lock.json](scripts/diagrams/package-lock.json) pins the npm dependency and build-tool closure. [scripts/diagrams/sources.lock.json](scripts/diagrams/sources.lock.json) records their original archives, npm integrity hashes, preferred upstream source archives, and every generated asset hash. The build recipe and source maps with source content are retained in `scripts/diagrams/`; notices are retained in `assets/diagrams/vendor/`. The release source archive includes those files, the original locked npm packages and exact upstream Mermaid/Excalidraw source trees. The integration supplies an offline viewer with panning and zoom, without changing the upstream renderers or font files.

Starting with 0.6.3, the APK retains the diagram shell, package manifest and complete notices. Mermaid and Canvas renderers are distributed in separate optional ZIP release assets; the Canvas package retains every bundled font and language subset. Each ZIP includes `THIRD_PARTY_NOTICES.txt` and `UPSTREAM_LICENSES.txt` as well. [packages.json](app/src/main/assets/diagrams/packages.json) pins each package's immutable content release URL, SHA-256, archive and expanded sizes, and file inventory. App patches reuse unchanged package versions. [package-diagrams.py](scripts/package-diagrams.py) reproduces the ZIPs from the unchanged locked vendor tree; all vendor files and corresponding source archives remain included in the full release source archive.

## Binary distribution

Versions from 0.4.0 use runtime connection setup. Their APKs do not embed server credentials; older personally configured APKs must remain private. CI builds and tests the runtime-configured client without a Trilium server token. Automated signed releases use a separately configured signing key.

Every APK release must be accompanied at the same download location by its `trilium-client-VERSION-source.tar.gz` and `SHA256SUMS`. The release workflow runs [package-source.sh](scripts/package-source.sh) for the exact release revision. The package contains the application's Git archive, the pinned editor source tree with its build scripts and lockfile, the distribution's complete source maps, locked npm source packages and additional original TypeScript sources. It also contains matching source JARs and POMs for every resolved Maven runtime dependency, including inherited parent POM metadata. The package records original URLs and SHA-256 checksums and includes build instructions. Missing source downloads or a dependency manifest from another application commit stop packaging. GitHub's automatic application-only source ZIP or a development-branch link does not replace this package.

The APK and source archive include the application's copyright, GPL terms, warranty notice and retained third-party notices as files, including full Apache-2.0, BSD and MIT/ISC notices. The APK retains its legal files under `assets/licenses/` and `assets/editor/vendor/`, with an index at `assets/licenses/index.txt`. Copyright and license notices remain included while the floating editor badge is hidden. The actual upstream GPL option is used; this project does not claim a commercial white-label license.

For local source packaging, first check out the intended clean revision, run `./gradlew -PdemoBuild=true :app:writeReleaseDependencyManifest`, then run `scripts/package-source.sh REF /tmp/trilium-client-source.tar.gz`. The dependency manifest must identify the same commit as `REF`. Source downloads use public upstream HTTPS endpoints; local connection settings and signing keys are never read. To regenerate editor dependency notices after deliberately updating the source lock, run `python3 scripts/package-source.py HEAD --write-editor-notices app/src/main/assets/editor/vendor/THIRD_PARTY_LICENSES.txt`.

Synthetic test fixtures are not personal documents. `lifecycle-loopback.p12` is a localhost test certificate/key, not an application signing key. The sample video and screenshots were generated for UI tests.
