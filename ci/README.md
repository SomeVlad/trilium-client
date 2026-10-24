# Continuous integration

`build.yml` is a prepared GitHub Actions workflow. It is kept outside `.github/workflows/` until the publishing credential has the `workflow` permission; it is **not active** in this location.

After authorizing workflow changes, activate it:

```sh
mkdir -p .github/workflows
mv ci/build.yml .github/workflows/build.yml
```

Then update the documentation links and commit the move. The workflow uses pinned action commits, JDK 17, Android SDK 36, and `-PdemoBuild=true` (disables local signing only). It runs the JVM suite and builds a debug APK. Before uploading its seven-day artifact, it checks that generated BuildConfig contains no connection credential fields and that the build script does not read the legacy private connection settings. It never needs a Trilium token or release signing key in GitHub secrets.
