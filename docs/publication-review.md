# Publication review — 0.4.1

Reviewed on 2026-10-24. This is an early independent client, not a claim of complete device compatibility or a formal security certification. The repository remains private until its owner chooses otherwise.

## Scope and findings

An independent GPT-6.1-sol reviewer at maximum reasoning effort read all 23 first-party Kotlin application files, the editor HTML/CSS/JavaScript integration, manifest/resources, Gradle configuration and publication scripts. A separate reviewer checked repository history, dependency licensing and source distribution. Minified third-party editor internals were checked for provenance and notices, not reviewed line by line.

The following findings were fixed before release:

- Binary note and attachment downloads previously materialized the entire response in memory. They now stream to atomic files; cancelled or failed downloads retain the previous cache. Native export also streams, with a separate filename namespace. Text and inline resources have a 20 MiB response limit. A reproduced 100 MiB response that exhausted a 64 MiB JVM heap now returns a controlled I/O error through the bounded API.
- An unchanged tree refresh rewrote the entire JSON store. Equality checks now skip those writes.
- A local edit whose server note disappeared, or an offline child whose server parent disappeared, could retry indefinitely. It now becomes a persistent conflict, retains the local text and can be recovered as a copy in the root folder.
- Resources under reverse-proxy subpaths were rejected by editor policy. The configured server path is now passed through both HTML and Markdown viewers without relaxing origin checks.
- An unused live-server verification script depended on removed build-time credentials and used default HTTP redirect behavior. It was removed; automated checks use synthetic fixtures.
- jsoup was updated from 1.21.2 to 1.23.2 after the dependency audit found GHSA-65r4-943x-97jj and GHSA-pmhh-3w7g-xqp8. The affected XML/custom-safelist APIs were not used by the inspected application, but the affected version is no longer shipped. See the [upstream release](https://jsoup.org/news/release-1.23.2).

## Distribution

Project GPL text, copyright/no-warranty notice, editor notices and license texts found in the resolved runtime artifacts are bundled and accessible from About without a connection. Full editor dependency notices are retained in the vendor assets.

The source packager uses the exact committed application revision. It rejects private properties, build output, signing material and unexpected non-regular entries. The public loopback test certificate is allowed only at its locked fixture hash. The editor distribution is checked against pinned hashes; its preferred source, complete source maps and 112 locked production npm packages are included. Runtime source jars and POMs come from the actual resolved release dependency manifest, including parent POMs. The source archive records upstream URLs and checksums.

Repository-history scanning checked the former personal server identifiers and credentials, private configuration patterns and signing material without printing secret values. Synthetic test credentials and the public loopback certificate are intentional fixtures. Older personal APKs with compiled credentials are not release inputs.

After the jsoup update, the live OSV query reported no known advisories for 203 exact package versions: 90 resolved Maven runtime coordinates, 112 editor npm production packages and the CKEditor umbrella package. This checks known database entries as of the review date, not build-time tools or undisclosed vulnerabilities.

## Release automation

The build workflow runs Python automation tests, JVM tests, Android lint and a debug build. The tag workflow requires a stable tag matching the app version and a commit in `main` history. It runs the checks before signing, then validates package identity and the pinned signing certificate. Signing inputs are repository Actions secrets, available only to the signing job; they are not source files or uploaded artifacts.

The publisher uploads the APK, source archive and SHA256SUMS to a draft, downloads and compares all bytes, then publishes it. Reruns refuse to replace different assets or alter an incomplete published release. Repository privacy also applies to its releases.

See the [validation record](acceptance.md) for executed tests. Hosted Actions status is available on the repository's [Actions page](https://github.com/SomeVlad/trilium-client/actions); local workflow validation alone does not establish a successful hosted run.

## Known limits

- The text corpus and index remain a shared in-memory JSON store. Initial downloads can require repeated whole-store writes; very large libraries need further measurement and storage work.
- Text/API and inline WebView responses are limited to 20 MiB, including large text-like attachments. Public inline HTTP images are unsupported; HTTPS is supported.
- ETAPI lacks atomic conditional writes, so version checks do not eliminate all concurrent-edit races.
- The current acceptance device is an API 36 emulator. Physical devices, API 28 and a broad Trilium/proxy compatibility matrix are not fully verified.
- This review and the advisory database check cannot establish the absence of unknown defects or vulnerabilities.
