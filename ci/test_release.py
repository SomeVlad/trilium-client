"""Offline checks for release identity gates and non-destructive publication."""
import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verify = load("verify_release", "scripts/verify-release.py")
publisher = load("release_publish", "scripts/release-publish.py")


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.build = self.path / "build.gradle.kts"
        self.build.write_text('versionName = "1.2.3"\nversionCode = 42\n')

    def test_tag_must_match_stable_version(self):
        self.assertEqual(verify.verify("v1.2.3", self.build), ("1.2.3", "42"))
        for tag in ("v1.2.4", "v01.2.3", "v1.2.3-rc1", "1.2.3", "v1.2.3\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                verify.verify(tag, self.build)

    def test_signature_and_apk_identity(self):
        signature = f"Signer #1 certificate SHA-256 digest: {verify.CERTIFICATE_SHA256}"
        package = "package: name='ru.vlad.triliumnotes' versionCode='42' versionName='1.2.3'"
        with patch.object(verify, "run", side_effect=[signature, package]):
            verify.verify("v1.2.3", self.build, apk="app.apk", apksigner="apksigner", aapt="aapt")
        for output in (signature.replace(verify.CERTIFICATE_SHA256, "bad"), signature + "\nSigner #2 certificate SHA-256 digest: abc"):
            with patch.object(verify, "run", return_value=output), self.assertRaises(ValueError):
                verify.verify("v1.2.3", self.build, apk="app.apk", apksigner="apksigner", aapt="aapt")
        with patch.object(verify, "run", side_effect=[signature, package.replace("42", "43")]), self.assertRaises(ValueError):
            verify.verify("v1.2.3", self.build, apk="app.apk", apksigner="apksigner", aapt="aapt")

    def test_tag_commit_and_master_ancestry(self):
        with patch.object(verify, "run", side_effect=["head", "other"]), self.assertRaises(ValueError):
            verify.verify("v1.2.3", self.build, check_git=True)
        with patch.object(verify, "run", side_effect=["head", "head"]), \
                patch.object(verify.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git")), \
                self.assertRaises(subprocess.CalledProcessError):
            verify.verify("v1.2.3", self.build, check_git=True)

    def test_real_master_repository_accepts_its_release_and_rejects_unrelated_history(self):
        def git(*arguments):
            return subprocess.run(["git", *arguments], cwd=self.path, check=True,
                                  capture_output=True, text=True)

        git("init", "-b", "master")
        git("config", "user.name", "Release test")
        git("config", "user.email", "ci@example.test")
        git("add", self.build.name)
        git("commit", "-m", "Release sources")
        git("tag", "v1.2.3")
        git("update-ref", "refs/remotes/origin/master", "HEAD")
        command = [sys.executable, str(ROOT / "scripts/verify-release.py"),
                   "--tag", "v1.2.3", "--build-file", str(self.build), "--check-git"]
        subprocess.run(command, cwd=self.path, check=True, capture_output=True, text=True)
        git("checkout", "--orphan", "unrelated")
        git("commit", "-m", "Unrelated sources")
        git("tag", "-f", "v1.2.3")
        result = subprocess.run(command, cwd=self.path, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)

    def assets(self):
        self.build.unlink()
        names = ["trilium-client-1.2.3.apk", "trilium-client-1.2.3-source.tar.gz"]
        for name in names:
            (self.path / name).write_bytes(name.encode())
        (self.path / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(name.encode()).hexdigest()}  ./{name}\n" for name in names))
        return names + ["SHA256SUMS"]

    def fake_gh(self, names, draft=True, existing=None, corrupt=False):
        assets = list(names if existing is None else existing)
        calls = []

        def call(*args, **kwargs):
            calls.append(args)
            if args[:2] == ("release", "view"):
                result = {"isDraft": draft, "assets": [{"name": name} for name in assets], "url": "https://example/release"}
                return subprocess.CompletedProcess(args, 0, json.dumps(result), "")
            if args[:2] == ("release", "upload"):
                assets.extend(Path(path).name for path in args[3:])
            if args[:2] == ("release", "download"):
                destination = Path(args[args.index("--dir") + 1])
                selected = [args[args.index("--pattern") + 1]] if "--pattern" in args else assets
                for name in selected:
                    (destination / name).write_bytes(b"different" if corrupt else (self.path / name).read_bytes())
            return subprocess.CompletedProcess(args, 0, "", "")
        return call, calls

    def test_matching_published_release_is_unchanged(self):
        names = self.assets()
        fake, calls = self.fake_gh(names, draft=False)
        with patch.object(publisher, "gh", side_effect=fake):
            publisher.publish("v1.2.3", self.path)
        self.assertFalse(any(call[1] in ("upload", "edit", "create") for call in calls))

    def test_changed_asset_is_never_overwritten(self):
        names = self.assets()
        fake, calls = self.fake_gh(names, corrupt=True)
        with patch.object(publisher, "gh", side_effect=fake), self.assertRaises(ValueError):
            publisher.publish("v1.2.3", self.path)
        self.assertFalse(any(call[1] in ("upload", "edit") for call in calls))

    def test_partial_draft_resumes_before_publish(self):
        names = self.assets()
        fake, calls = self.fake_gh(names, existing=names[:1])
        with patch.object(publisher, "gh", side_effect=fake):
            publisher.publish("v1.2.3", self.path)
        uploads = [call for call in calls if call[1] == "upload"]
        self.assertEqual([Path(path).name for path in uploads[0][3:]], names[1:])
        self.assertEqual(calls[-1], ("release", "edit", "v1.2.3", "--draft=false", "--latest"))

    def test_published_incomplete_release_refuses_changes(self):
        names = self.assets()
        fake, calls = self.fake_gh(names, draft=False, existing=names[:1])
        with patch.object(publisher, "gh", side_effect=fake), self.assertRaises(ValueError):
            publisher.publish("v1.2.3", self.path)
        self.assertFalse(any(call[1] in ("upload", "edit") for call in calls))


if __name__ == "__main__":
    unittest.main()
