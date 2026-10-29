#!/usr/bin/env python3
"""Build reproducible, separately versioned diagram packages from locked vendor files.

Normal builds verify the committed APK manifest. After deliberately changing
vendor content, use --write-manifest --package-version vMAJOR.MINOR.PATCH once,
commit packages.json, and publish the packages at that release before use.
ZIP_STORED fixes the archive bytes independently of the host zlib version.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("app/src/main/assets/diagrams/packages.json")
VENDOR = Path("app/src/main/assets/diagrams/vendor")
LOCK = Path("scripts/diagrams/sources.lock.json")
REPOSITORY = "https://github.com/SomeVlad/trilium-client"
NOTICES = ["THIRD_PARTY_NOTICES.txt", "UPSTREAM_LICENSES.txt"]
STABLE_TAG = r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def archive_bytes(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return output.getvalue()


def package_files(root):
    vendor = root / VENDOR
    lock = json.loads((root / LOCK).read_text())
    if lock.get("schemaVersion") != 1:
        raise ValueError("Unsupported diagram source lock schema")
    names = set(lock["bundledFiles"])
    actual = {path.relative_to(vendor).as_posix() for path in vendor.rglob("*") if path.is_file()}
    if actual != names:
        raise ValueError("Diagram vendor inventory differs from the source lock")
    contents = {}
    for name in sorted(names):
        if name.startswith("/") or ".." in name.split("/") or "\\" in name:
            raise ValueError("Unsafe diagram source path")
        content = (vendor / name).read_bytes()
        if sha256(content) != lock["bundledFiles"][name]:
            raise ValueError("Diagram asset differs from its source lock: " + name)
        contents[name] = content
    fonts = sorted(name for name in names if name.startswith("fonts/"))
    if not fonts or not all(name.endswith(".woff2") for name in fonts):
        raise ValueError("Complete local Canvas font inventory is required")
    groups = {"mermaid": ["mermaid.js", *NOTICES], "canvas": ["excalidraw.js", *NOTICES, *fonts]}
    if set(contents) != {"viewer.js", *groups["mermaid"], *groups["canvas"]}:
        raise ValueError("Diagram distribution contains unassigned vendor files")
    return {kind: {name: contents[name] for name in sorted(group)} for kind, group in groups.items()}


def build(directory, *, root=ROOT, package_version=None, write_manifest=False):
    manifest_path = root / MANIFEST
    old = json.loads(manifest_path.read_text()) if manifest_path.is_file() else None
    if write_manifest:
        if not package_version or not re.fullmatch(STABLE_TAG, package_version):
            raise ValueError("Writing the manifest requires a stable --package-version")
    elif package_version or not old or old.get("schemaVersion") != 1 or set(old.get("packages", {})) != {"mermaid", "canvas"}:
        raise ValueError("Build requires a committed manifest; only --write-manifest sets a version")
    result = {"schemaVersion": 1, "packages": {}}
    archives = {}
    for kind, files in package_files(root).items():
        previous = old and old.get("packages", {}).get(kind)
        data = archive_bytes(files)
        payload = {
            "sha256": sha256(data), "size": len(data),
            "unpackedSize": sum(map(len, files.values())), "files": sorted(files),
        }
        version = package_version if write_manifest else old["packages"][kind]["version"]
        # Updating one renderer must not invalidate the other installed package.
        if write_manifest and previous and all(previous.get(key) == value for key, value in payload.items()):
            version = previous["version"]
        if not re.fullmatch(STABLE_TAG, version):
            raise ValueError("Invalid diagram package version")
        name = f"diagram-{kind}-{version}.zip"
        spec = {
            "version": version,
            "url": f"{REPOSITORY}/releases/download/{version}/{name}",
            **payload,
        }
        if previous and previous.get("version") == version and previous != spec:
            raise ValueError("Changed package requires a new immutable content version: " + kind)
        if not write_manifest and previous != spec:
            raise ValueError("Diagram package differs from the committed manifest: " + kind)
        result["packages"][kind] = spec
        archives[name] = data
    if write_manifest:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(result, indent=2) + "\n")
    directory.mkdir(parents=True, exist_ok=True)
    for name, data in archives.items():
        (directory / name).write_bytes(data)
        print(f"{sha256(data)}  {name} ({len(data)} bytes)")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--write-manifest", action="store_true")
    parser.add_argument("--package-version")
    args = parser.parse_args()
    build(args.directory, package_version=args.package_version, write_manifest=args.write_manifest)


if __name__ == "__main__":
    main()
