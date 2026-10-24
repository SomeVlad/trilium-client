# Validation record

This record summarizes validation of app version **0.3.5** before source-release preparation. It is not a claim that every Android device or Trilium version is supported. Public screenshots and the scenarios below use synthetic notes and attachments.

## Recorded results

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

The [synthetic screenshots](screenshots/) illustrate the UI. For reproducible local commands, see [CONTRIBUTING.md](../CONTRIBUTING.md); the [prepared CI workflow](../ci/build.yml) uses the disconnected demo configuration and is not yet active.

## Source-preview preparation

A clean checkout with no private properties, signing properties, or release key passed all 100 JVM tests, `assembleDebug`, and release lint (0 errors, 21 warnings). The resulting APK contains only the `.invalid` endpoint and dummy token. A separate `-PdemoBuild=true` run also passed with private local configuration present, confirming that demo mode ignores it. This run did not repeat the Android device scenarios above because application behavior was unchanged.
