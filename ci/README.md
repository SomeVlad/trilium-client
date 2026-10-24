# Continuous integration

`.github/workflows/build.yml` runs on pushes to `main`, pull requests and manual
dispatches. It runs JVM tests, Android lint and the debug build without signing
secrets or a Trilium server. A debug APK is retained for seven days. Release tags
also run this workflow before the signing job. Action versions are pinned to
commit hashes.

`.github/workflows/release.yml` accepts stable tags `vMAJOR.MINOR.PATCH` only. The
tag must match `versionName` in `app/build.gradle.kts` and point to a commit in
`main` history. Before tagging, increment both `versionName` and `versionCode` as
appropriate, commit and push `main`, then push the matching tag:

```sh
git tag v0.4.1
git push origin v0.4.1
```

Configure these repository Actions secrets using the existing PKCS12 signing
identity; generating another key would prevent installed users from upgrading:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the existing PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Private key password |

Only the signing step receives the passwords. The keystore is decoded under
`runner.temp`, is deleted even if the build fails and is never uploaded or cached.
Release builds disable Gradle caches. The workflow verifies the APK package,
version code/name and its signing certificate SHA256:

```text
ce0d4a08415d8560d7b1795aa14d91e3352d1a869ba1f0fd4066e582975e65de
```

The release job has `contents: write`; other jobs have read-only repository
access. Pull requests never enter the release workflow. No workflow needs a
Trilium connection URL or token.

The release contains `trilium-client-VERSION.apk`,
`trilium-client-VERSION-source.tar.gz` and `SHA256SUMS`. The source archive comes
from the release tag and includes the corresponding third-party source material
described in the repository's distribution documentation. All assets are
uploaded to a draft and downloaded again to verify their checksums before
publication. A rerun accepts matching existing assets and refuses to overwrite
different ones or modify an already published incomplete release. If a failed
build left a draft with different bytes, inspect and delete that draft manually
before rerunning; published releases should receive a new version tag.

Local workflow checks (no signing secret needed):

```sh
actionlint .github/workflows/*.yml
python3 scripts/verify-release.py --tag v0.4.1
python3 -m unittest discover -s ci -p 'test_*.py'
```
