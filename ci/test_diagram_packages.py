"""Reproducible package bytes, immutable content versions and complete font closure."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("package_diagrams", ROOT / "scripts/package-diagrams.py")
packages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packages)


class DiagramPackagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.vendor = self.root / packages.VENDOR
        self.vendor.mkdir(parents=True)
        self.files = {
            "mermaid.js": b"Mermaid", "excalidraw.js": b"Excalidraw", "viewer.js": b"shell",
            "THIRD_PARTY_NOTICES.txt": b"MIT notices", "UPSTREAM_LICENSES.txt": b"font notices",
            "fonts/Latin/full.woff2": b"latin", "fonts/CJK/full.woff2": b"complete CJK font",
        }
        self.write_vendor()
        self.output = self.root / "packages"

    def write_vendor(self):
        for name, content in self.files.items():
            path = self.vendor / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        lock = self.root / packages.LOCK
        lock.parent.mkdir(parents=True, exist_ok=True)
        lock.write_text(json.dumps({"schemaVersion": 1, "bundledFiles": {
            name: hashlib.sha256(content).hexdigest() for name, content in self.files.items()
        }}))

    def initialize(self):
        return packages.build(self.output, root=self.root, package_version="v0.6.3", write_manifest=True)

    def test_committed_manifest_matches_every_locked_vendor_file(self):
        # Actual production files catch omitted language/font assets and stale hashes.
        with tempfile.TemporaryDirectory() as directory:
            manifest = packages.build(Path(directory))
            self.assertEqual(manifest, json.loads((ROOT / packages.MANIFEST).read_text()))

    def test_build_is_byte_identical_and_does_not_rewrite_manifest(self):
        manifest = self.initialize()
        original = {path.name: path.read_bytes() for path in self.output.iterdir()}
        manifest_path = self.root / packages.MANIFEST
        before = manifest_path.stat().st_mtime_ns
        self.assertEqual(manifest, packages.build(self.root / "again", root=self.root))
        self.assertEqual(original, {path.name: path.read_bytes() for path in (self.root / "again").iterdir()})
        self.assertEqual(before, manifest_path.stat().st_mtime_ns)

    def test_packages_keep_all_fonts_and_standalone_notices(self):
        manifest = self.initialize()
        for kind, record in manifest["packages"].items():
            with ZipFile(self.output / record["url"].rsplit("/", 1)[1]) as archive:
                self.assertEqual(record["files"], archive.namelist())
                self.assertEqual(record["unpackedSize"], sum(item.file_size for item in archive.infolist()))
                self.assertEqual(b"MIT notices", archive.read("THIRD_PARTY_NOTICES.txt"))
                self.assertEqual(b"font notices", archive.read("UPSTREAM_LICENSES.txt"))
                if kind == "canvas":
                    self.assertEqual({name for name in self.files if name.startswith("fonts/")},
                                     {name for name in archive.namelist() if name.startswith("fonts/")})
                self.assertNotIn("viewer.js", archive.namelist())

    def test_unlocked_tampering_is_rejected(self):
        self.initialize()
        (self.vendor / "mermaid.js").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "source lock"):
            packages.build(self.output, root=self.root)

    def test_changed_locked_content_requires_new_package_version(self):
        old = self.initialize()
        self.files["mermaid.js"] = b"new Mermaid"
        self.write_vendor()
        with self.assertRaisesRegex(ValueError, "immutable content version"):
            packages.build(self.output, root=self.root)
        with self.assertRaisesRegex(ValueError, "immutable content version"):
            self.initialize()
        new = packages.build(self.output, root=self.root, package_version="v0.6.4", write_manifest=True)
        self.assertNotEqual(old["packages"]["mermaid"]["sha256"], new["packages"]["mermaid"]["sha256"])
        self.assertEqual(old["packages"]["canvas"], new["packages"]["canvas"])

    def test_unlocked_or_missing_font_stops_packaging(self):
        self.initialize()
        (self.vendor / "fonts/CJK/full.woff2").unlink()
        with self.assertRaisesRegex(ValueError, "inventory"):
            packages.build(self.output, root=self.root)

    def test_unassigned_vendor_files_stop_packaging(self):
        self.files["unexpected.js"] = b"code"
        self.write_vendor()
        with self.assertRaisesRegex(ValueError, "unassigned"):
            self.initialize()


if __name__ == "__main__":
    unittest.main()
