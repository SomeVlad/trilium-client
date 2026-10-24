# Validation record

This record includes validation of **0.4.2** HTML line breaks, **0.4.1** publication changes, **0.4.0** runtime authentication and folders, and the earlier **0.3.5** baseline. It is not a claim that every Android device or Trilium version is supported. Public screenshots and committed test data are synthetic; optional private fixtures are injected locally and never bundled.

## Version 0.4.2

- The new Android regression reproduced the original defect before the fix: two newline-separated HTML tokens had a vertical separation of 0 px instead of one 28.05 px line.
- 17 unique Android editor checks passed on an API 36 emulator: seven new line-break cases plus the existing rich editor and lazy reader suites. They cover line geometry, empty lines, inline code and links, unchanged snapshots and zero callbacks on opening, a real edit followed by reopening, preformatted code, existing `<br>` tags, explicit whitespace styles, structural HTML indentation and Markdown.
- Two privately supplied HTML notes passed the same reader/edit/reopen line-geometry checks. The independent reviewer inspected their local screenshots. No private content, identifiers or screenshots were committed, and no server writes were needed for these checks.
- A System UI ANR initially obscured screenshots and blocked two keyboard-focus checks. After dismissing that emulator dialog, the two checks and private screenshot scenario passed. Screenshot capture now requires a visible, focused WebView before capture; an obscured window fails the test.
- Debug/instrumentation builds and release lint passed (0 errors, 21 warnings). The independent code review found no remaining blocker in the change.

The compatibility conversion applies to literal LF inside leaf HTML text blocks and bare text bodies. Opening preserves the original source bytes; a real edit can serialize those line boundaries as explicit `<br>` elements. It does not reinterpret every HTML whitespace pattern: mixed bare text around nested blocks and explicit whitespace styles retain their previous handling.

## Version 0.4.1

- 137 JVM tests passed with no failures or skips. New regression checks cover streaming large attachments, interrupted downloads preserving the previous cache, bounded text/inline responses, cache/export filename collisions, reverse-proxy subpath resources, unchanged tree refreshes avoiding disk rewrites, and recovery of local edits or children after a remote deletion.
- Debug, instrumentation and signed release APKs compiled; release lint reported 0 errors and 21 warnings. Most warnings report newer dependency versions; the others concern Compose conventions, backup configuration and KTX convenience APIs.
- An independent reviewer read all 23 application Kotlin files, editor integration, manifest/resources, Gradle build logic and release scripts. A 100 MiB response under a 64 MiB JVM heap now fails with a bounded I/O error instead of the previously reproduced out-of-memory crash.
- Fourteen Python release/source-packaging tests passed. Actionlint accepted the pinned GitHub workflows. These local checks are distinct from a successful hosted GitHub Actions run.
- 21 unique Android checks passed on an API 36 emulator: connection UI, lazy HTML/Markdown reading and editing, keyboard list input, attachment viewing/retry/cancellation, and license navigation/assets. The first run caught duplicate license keys; after fixing generation, both license checks passed again.
- The debug and signed APKs each contain 65 unique, nonempty indexed license assets and no loopback test certificate. APK package/version and the existing release certificate were verified.
- The signed APK installed over 0.4.0 without uninstalling. About and the offline GPL text opened in the minimized release build; light/dark screenshots were inspected.
- See [publication review](publication-review.md) for publication scope, source packaging and remaining limits.

## Version 0.4.0

- 122 JVM tests passed with no failures or skips: password and token authentication, invalid credentials, HTML proxy challenges, redirect refusal, URL normalization, encrypted-session handling, per-server storage, stale background work, explicit legacy import, folder persistence and parent ordering, plus the existing repository suite.
- 11 unique Android checks passed on an Android 16 / API 36 emulator: six connection UI checks, three folder UI checks, resume sync lifecycle, and the new real-Activity connection lifecycle scenario. Eighteen screenshots cover both themes.
- The connection lifecycle check uses Android Keystore on the emulator. It verifies the password is absent from persisted session data, the token and URL are encrypted, a durable draft survives Activity recreation, session restoration does not authenticate again, and sign-out/relogin preserves the draft.
- An isolated actual Trilium **0.106.0** accepted password login (201), rejected a wrong password (401), accepted token-authenticated app-info/root requests, and created a native empty `book` with a text-note child. No production note data was used.

- The signed APK installed over 0.3.5 without uninstalling. A synthetic unsent legacy edit was explicitly imported, reached the actual test server, and its original local cache remained byte-for-byte intact.
- On the signed APK, wrong-password and successful password login, token login, local sign-out and relogin all passed against that actual server. Local sign-out did not revoke the separately supplied token.
- With Wi-Fi and mobile data disabled, the existing session reopened after force-stop. An empty folder and child note created offline survived another force-stop and synchronized after reconnect; server reads verified `type=book` and the child's parent ID.
- Real MainActivity screenshots at 360 dp verified login and folder creation with the keyboard visible, the error state, legacy confirmation, and offline restart. The independent product reviewer accepted these and the light/dark matrix.
- Debug/instrumentation/release compilation and release lint completed; lint has 0 errors and 21 pre-existing warnings. The APK is signed with the same certificate as 0.3.5. Inspection found neither the prior personal server address nor token in its code/resources.

## Version 0.3.5 baseline

| Check | Result |
| --- | --- |
| JVM suite | 100 tests passed; no failures or skips |
| Android scenarios | 34 unique checks passed: 31 focused UI checks and 3 end-to-end scenarios |
| Build and lint | Debug, instrumentation and release builds completed; lint reported 0 errors and 21 warnings |
| Signed upgrade | 0.3.4 → 0.3.5 installed as an update; the synthetic offline cache remained byte-for-byte intact |

The JVM checks cover tree/search behavior, local persistence, retries, conflicts and deletion races. Deletion cases include failed local writes, restart recovery, an applied server DELETE whose response was lost, a subsequent 404, shared descendants, and clones discovered after an offline edit.

Android checks exercised the real activity, view model and local storage against synthetic fixtures. They covered editing, confirmation and cancellation, deleting documents and folders, offline creation followed by deletion, clone handling, queue persistence across force-stop and restart, reconnect retries, and keeping a shared descendant. The signed upgrade smoke check also verified dark-theme confirmation and an offline deletion surviving force-stop.

Folder and search scroll restoration passed using Android's system Back key command. **A physical edge-swipe Back gesture is not verified.** The emulator failed to perform that gesture even in Android Settings; it opened a system brightness dialog instead. No physical-device gesture check was completed, and the app was not changed to work around the emulator behavior.

## Limits and follow-up

- These checks used an emulator and synthetic fixtures, not a broad physical-device matrix.
- ETAPI does not provide atomic conditional writes. Conflict checks cannot eliminate a concurrent write between checking and saving.
- Offline availability depends on completion of the initial download. Android can defer background sync.
- Deletion has no client-side undo. Unsupported special note types are described in the [README](../README.md).
- Source-release configuration changes require their own build checks; the results above describe the app acceptance run, not a new run of that suite after every documentation change.

The [synthetic screenshots](screenshots/) illustrate the UI. For reproducible local commands, see [CONTRIBUTING.md](../CONTRIBUTING.md); the [build workflow](../.github/workflows/build.yml) validates the runtime-configured APK.

## Earlier source-preview preparation

A clean checkout with no private properties, signing properties, or release key passed all 100 JVM tests, `assembleDebug`, and release lint (0 errors, 21 warnings). The resulting APK contains only the `.invalid` endpoint and dummy token. A separate `-PdemoBuild=true` run also passed with private local configuration present, confirming that demo mode ignores it. This run did not repeat the Android device scenarios above because application behavior was unchanged.
