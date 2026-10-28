#!/usr/bin/env python3
"""Source-release checks with synthetic Git repositories and no network access."""

import argparse
import base64
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import MagicMock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/package-source.py"
SPEC = importlib.util.spec_from_file_location("source_package", SCRIPT)
source_package = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(source_package)


def archive_bytes(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for name, data in entries.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return stream.getvalue()


class SourcePackageTests(unittest.TestCase):
    def test_editor_notices_use_lf_even_when_upstream_files_mix_line_endings(self):
        package = {"name": "synthetic-package", "version": "1.0.0"}
        lock = {"editor": {"npmPackages": [package]}}
        files = {"third-party-sources/npm/synthetic-package-1.0.0.tgz": archive_bytes({
            "package/LICENSE": b"Copyright synthetic contributors\r\nPermission notice\rWarranty notice\n"
        })}
        notices = source_package.editor_notices(lock, files)
        self.assertNotIn(b"\r", notices)
        self.assertIn(b"Copyright synthetic contributors\nPermission notice\nWarranty notice\n", notices)

    def test_only_the_requested_committed_source_is_archived(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL)
            git("init", "-q")
            git("config", "user.name", "Synthetic Test")
            git("config", "user.email", "test@example.invalid")
            (root / "app.kt").write_text("committed source")
            git("add", "app.kt")
            git("commit", "-qm", "Synthetic source revision")
            commit = git("rev-parse", "HEAD").decode().strip()
            (root / "app.kt").write_text("uncommitted content")
            (root / "private.properties").write_text("synthetic value that must not be archived")
            with patch.object(source_package, "git", side_effect=git):
                data = source_package.safe_app_archive(commit, {"editor": {"bundledFiles": {}}})
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                self.assertEqual(archive.getnames(), ["app.kt"])
                self.assertEqual(archive.extractfile("app.kt").read(), b"committed source")

    def test_a_committed_signing_configuration_is_rejected(self):
        data = archive_bytes({"signing.properties": b"synthetic configuration"})
        with patch.object(source_package, "git", return_value=data):
            with self.assertRaisesRegex(ValueError, "Private configuration"):
                source_package.safe_app_archive("a" * 40, {"editor": {"bundledFiles": {}}})

    def test_only_the_known_public_certificate_fixture_is_allowed(self):
        path = "app/src/androidTest/assets/lifecycle-loopback.p12"
        public_fixture = b"synthetic public fixture bytes"
        lock = {"editor": {"bundledFiles": {}}, "publicTestFixtures": {
            path: hashlib.sha256(public_fixture).hexdigest()
        }}
        with patch.object(source_package, "git", return_value=archive_bytes({path: public_fixture})):
            source_package.safe_app_archive("a" * 40, lock)
        with patch.object(source_package, "git", return_value=archive_bytes({path: b"different key"})):
            with self.assertRaisesRegex(ValueError, "Private configuration"):
                source_package.safe_app_archive("a" * 40, lock)

    def test_a_cached_download_still_needs_its_locked_checksum(self):
        url = "https://registry.npmjs.org/example/-/example-1.0.0.tgz"
        payload = b"synthetic archive content"
        response = MagicMock()
        response.__enter__.return_value = response
        response.url = url
        response.read.return_value = payload
        opener = MagicMock()
        opener.open.return_value = response
        with tempfile.TemporaryDirectory() as directory, patch.object(
            source_package.urllib.request, "build_opener", return_value=opener
        ):
            downloads = source_package.Downloads(Path(directory))
            self.assertEqual(downloads.get(url, digest=hashlib.sha256(payload).hexdigest()), payload)
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                downloads.get(url, digest="0" * 64)

    def run_package(self, root, manifest_commit, with_diagrams=False):
        commit = "a" * 40
        lock = {"schemaVersion": 1, "editor": {"bundledFiles": {}, "npmPackages": []}}
        app_entries = {"app.kt": b"committed application source",
            "app/src/main/assets/editor/vendor/THIRD_PARTY_LICENSES.txt": b"retained notices"}
        diagram_files, diagram_records = {}, []
        if with_diagrams:
            entries, _, npm_data, upstream_data = self.diagram_fixture()
            app_entries.update(entries)
            diagram_files = {"third-party-sources/diagrams/npm/synthetic-1.0.0.tgz": npm_data,
                             "third-party-sources/diagrams/upstream/mermaid-fixture.tar.gz": upstream_data}
            diagram_records = [{"name": "synthetic", "version": "1.0.0", "path": path,
                                "sha256": hashlib.sha256(data).hexdigest()} for path, data in diagram_files.items()]
        app = archive_bytes(app_entries)
        def git(*args):
            if args[0] == "rev-parse":
                return commit.encode()
            if args[0] == "archive":
                return app
            if args[0] == "show" and args[1] == "-s":
                return b"1700000000"
            return json.dumps(lock).encode()
        manifest = root / "runtime.json"
        manifest.write_text(json.dumps({"schemaVersion": 1, "configuration": "releaseRuntimeClasspath",
            "gitCommit": manifest_commit, "versionName": "1.2.3", "dependencies": []}))
        output = root / "release-source.tar.gz"
        args = argparse.Namespace(ref="v1.2.3", output=output, dependencies=manifest,
                                  cache_dir=root / "cache", write_editor_notices=None)
        library = {"third-party-sources/synthetic.java": b"synthetic library source"}
        with patch.object(source_package, "git", side_effect=git), patch.object(
            source_package, "editor_inputs", return_value=({}, [])
        ), patch.object(source_package, "editor_notices", return_value=b"retained notices"), patch.object(
            source_package, "maven_inputs", return_value=(library, [{"coordinate": "example:library:1.0"}])
        ), patch.object(source_package, "diagram_inputs", return_value=(diagram_files, diagram_records)), redirect_stdout(io.StringIO()):
            source_package.package(args)
        return output

    def diagram_fixture(self):
        npm_data = archive_bytes({"package/src/index.js": b"synthetic preferred source", "package/LICENSE": b"Synthetic MIT notice"})
        integrity = "sha512-" + base64.b64encode(hashlib.sha512(npm_data).digest()).decode()
        packages = [{"path": "node_modules/synthetic", "name": "synthetic", "version": "1.0.0",
                     "url": "https://registry.npmjs.org/synthetic/-/synthetic-1.0.0.tgz", "integrity": integrity,
                     "dev": False, "license": "MIT"}]
        assets = {name: b"synthetic diagram asset" for name in (
            "mermaid.js", "excalidraw.js", "viewer.js", "THIRD_PARTY_NOTICES.txt", "UPSTREAM_LICENSES.txt", "fonts/Virgil/fixture.woff2")}
        upstream_data = archive_bytes({"upstream/src/renderer.ts": b"synthetic renderer preferred source"})
        upstream = [{"name": name, "version": "1.0.0", "filename": filename,
                     "url": "https://codeload.github.com/synthetic/" + filename,
                     "sha256": hashlib.sha256(upstream_data).hexdigest()}
                    for name, filename in (("mermaid", "mermaid-fixture.tar.gz"), ("@excalidraw/excalidraw", "excalidraw-fixture.tar.gz"))]
        lock = {"schemaVersion": 1, "packages": packages, "upstreamSources": upstream,
                "bundledFiles": {name: hashlib.sha256(data).hexdigest() for name, data in assets.items()}}
        entries = {"app/src/main/assets/diagrams/vendor/" + name: data for name, data in assets.items()}
        entries["scripts/diagrams/sources.lock.json"] = json.dumps(lock).encode()
        entries["scripts/diagrams/package-lock.json"] = json.dumps({"packages": {"node_modules/synthetic": {
            "version": "1.0.0", "resolved": packages[0]["url"], "integrity": integrity}}}).encode()
        for name in ("mermaid", "excalidraw", "viewer"):
            entries["scripts/diagrams/maps/" + name + ".js.map"] = json.dumps({
                "sources": ["node_modules/synthetic/src/index.js"], "sourcesContent": ["synthetic preferred source"]}).encode()
            entries["scripts/diagrams/maps/" + name + ".meta.json"] = json.dumps({
                "inputs": {"node_modules/synthetic/src/index.js": {"bytes": 8}}}).encode()
        for name in ("build.mjs", "package.json", "README.txt"):
            entries["scripts/diagrams/" + name] = b"synthetic build recipe"
        return entries, lock, npm_data, upstream_data

    def test_diagrams_require_exact_locked_assets_fonts_maps_and_npm_closure(self):
        entries, lock, _, _ = self.diagram_fixture()
        self.assertEqual(source_package.diagram_lock_from_archive(archive_bytes(entries)), lock)
        changed = dict(entries)
        changed["app/src/main/assets/diagrams/vendor/mermaid.js"] = b"tampered renderer"
        with self.assertRaisesRegex(ValueError, "differs"):
            source_package.diagram_lock_from_archive(archive_bytes(changed))
        changed = dict(entries)
        changed["app/src/main/assets/diagrams/vendor/unlocked.js"] = b"unlisted code"
        with self.assertRaisesRegex(ValueError, "inventory"):
            source_package.diagram_lock_from_archive(archive_bytes(changed))
        changed = dict(entries)
        changed["scripts/diagrams/maps/mermaid.js.map"] = json.dumps({"sources": ["source.js"], "sourcesContent": [None]}).encode()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            source_package.diagram_lock_from_archive(archive_bytes(changed))
        changed = dict(entries)
        changed["scripts/diagrams/maps/mermaid.meta.json"] = json.dumps({"inputs": {
            "node_modules/synthetic/node_modules/unlocked/index.js": {"bytes": 8}}}).encode()
        with self.assertRaisesRegex(ValueError, "no locked npm source"):
            source_package.diagram_lock_from_archive(archive_bytes(changed))

    def test_diagrams_refuse_package_lock_integrity_drift_and_missing_lock(self):
        entries, lock, _, _ = self.diagram_fixture()
        lock["packages"][0]["integrity"] = "sha512-untrusted"
        entries["scripts/diagrams/sources.lock.json"] = json.dumps(lock).encode()
        with self.assertRaisesRegex(ValueError, "integrity"):
            source_package.diagram_lock_from_archive(archive_bytes(entries))
        del entries["scripts/diagrams/sources.lock.json"]
        with self.assertRaisesRegex(ValueError, "no committed source lock"):
            source_package.diagram_lock_from_archive(archive_bytes(entries))

    def test_diagrams_preserve_original_npm_and_upstream_archives_with_verified_hashes(self):
        _, lock, npm_data, upstream_data = self.diagram_fixture()
        downloads = MagicMock()
        downloads.get.side_effect = lambda url, **kwargs: npm_data if "registry.npmjs.org" in url else upstream_data
        files, records = source_package.diagram_inputs(lock, downloads)
        self.assertEqual(len(records), 3)
        self.assertEqual(files["third-party-sources/diagrams/npm/synthetic-1.0.0.tgz"], npm_data)
        self.assertEqual(files["third-party-sources/diagrams/upstream/mermaid-fixture.tar.gz"], upstream_data)
        self.assertEqual(records[0]["sha256"], hashlib.sha256(npm_data).hexdigest())
        downloads.get.assert_any_call(lock["packages"][0]["url"], integrity=lock["packages"][0]["integrity"])
        for upstream in lock["upstreamSources"]:
            downloads.get.assert_any_call(upstream["url"], digest=upstream["sha256"])

    def test_a_stale_runtime_manifest_cannot_produce_a_release_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "does not belong"):
                self.run_package(root, "b" * 40)
            self.assertFalse((root / "release-source.tar.gz").exists())

    def test_release_archive_includes_diagram_sources_and_recorded_asset_closure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.run_package(Path(directory), "a" * 40, with_diagrams=True)
            with tarfile.open(output) as archive:
                prefix = "trilium-client-1.2.3-source/"
                manifest = json.load(archive.extractfile(prefix + "SOURCE_MANIFEST.json"))
                self.assertEqual(len(manifest["diagrams"]["sources"]), 2)
                self.assertIn("fonts/Virgil/fixture.woff2", manifest["diagrams"]["bundledFiles"])
                for record in manifest["diagrams"]["sources"]:
                    self.assertEqual(hashlib.sha256(archive.extractfile(prefix + record["path"]).read()).hexdigest(), record["sha256"])
                self.assertIsNotNone(archive.extractfile(prefix + "scripts/diagrams/build.mjs"))

    def test_release_archive_includes_library_source_and_verifiable_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.run_package(Path(directory), "a" * 40)
            with tarfile.open(output) as archive:
                prefix = "trilium-client-1.2.3-source/"
                self.assertEqual(archive.extractfile(prefix + "app.kt").read(), b"committed application source")
                self.assertEqual(archive.extractfile(prefix + "third-party-sources/synthetic.java").read(),
                                 b"synthetic library source")
                manifest = json.load(archive.extractfile(prefix + "SOURCE_MANIFEST.json"))
                self.assertEqual(manifest["application"]["commit"], "a" * 40)
                sums = archive.extractfile(prefix + "SOURCE_SHA256SUMS").read().decode().splitlines()
                for line in sums:
                    digest, name = line.split("  ", 1)
                    self.assertEqual(hashlib.sha256(archive.extractfile(prefix + name).read()).hexdigest(), digest)


if __name__ == "__main__":
    unittest.main()
