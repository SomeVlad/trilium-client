# Third-party software

Original trilium-client code is GPL-3.0-or-later. Third-party notices are preserved under their upstream terms. This list describes the principal runtime components; Gradle resolves their transitive dependencies from the versions declared in [app/build.gradle.kts](app/build.gradle.kts).

| Component | Upstream license | Source and notices |
| --- | --- | --- |
| CKEditor 5, 49.0.0 | GPL-2.0-or-later (the GPL option is used; GPLv3 is compatible with the project license) | [Source tag](https://github.com/ckeditor/ckeditor5/tree/v49.0.0), [bundled license](app/src/main/assets/editor/vendor/LICENSE.md), [copyright and provenance](app/src/main/assets/editor/vendor/NOTICE.txt) |
| AndroidX, Jetpack Compose and Material Components | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Kotlin and kotlinx.coroutines | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| OkHttp and Okio | Apache-2.0 | [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| jsoup | MIT | [Source and license](https://github.com/jhy/jsoup) |
| commonmark-java | BSD-2-Clause | [Source and license](https://github.com/commonmark/commonmark-java) |
| Gradle wrapper | Apache-2.0 | [Source and license](https://github.com/gradle/gradle) |

The CKEditor distribution also includes third-party components under MIT and ISC terms, listed in its bundled [LICENSE.md](app/src/main/assets/editor/vendor/LICENSE.md). The OkHttp public suffix resource retains its upstream notice inside the library.

## Bundled editor provenance

`ckeditor5.umd.js`, `ckeditor5.css`, and `ru.umd.js` are unmodified files from the official [49.0.0 distribution ZIP](https://cdn.ckeditor.com/ckeditor5/49.0.0/zip/ckeditor5-49.0.0.zip). The ZIP's SHA-256 is `cff5d58b096ffb726bba9be494e857c58c4f3606ebade4c998f8fa8d624a4948`. The preferred upstream source is the `v49.0.0` tag linked above.

The surrounding Android integration, `editor.js`, and `editor.css` are this project's modifications. A CSS rule added on 2026-10-23 hides the floating promotional badge; the upstream JavaScript, copyright, and license files are retained. This documents the modification rather than claiming a CKSource commercial license or endorsement.

## Binary distribution

Version 0.4.0 uses runtime connection setup. Its APK does not embed server credentials; older personally configured APKs must remain private. The prepared CI workflow builds and tests the same runtime-configured client without a server token or signing key.

When distributing a binary, provide the corresponding source for that exact build, including the pinned editor source and build instructions, and retain all required third-party notices. A link to the development branch or a minified bundle alone is not a substitute for the corresponding source. Before a public binary release, prepare that source package and review the branding choice against the selected upstream license terms.

Synthetic test fixtures are not personal documents. `lifecycle-loopback.p12` is a localhost test certificate/key, not an application signing key. The sample video and screenshots were generated for UI tests.
