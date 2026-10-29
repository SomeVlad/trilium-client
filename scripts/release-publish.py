#!/usr/bin/env python3
"""Publish a complete draft, refusing to replace assets on any rerun."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile


def gh(*args, check=True):
    return subprocess.run(["gh", *args], check=check, capture_output=True, text=True)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def diagram_assets(apk_path, directory):
    """Verify the optional renderer assets against the manifest in this APK."""
    with ZipFile(apk_path) as apk:
        manifest = json.loads(apk.read("assets/diagrams/packages.json"))
        names = set(apk.namelist())
        required = {"assets/diagrams/" + name for name in (
            "index.html", "viewer.css", "vendor/viewer.js",
            "vendor/THIRD_PARTY_NOTICES.txt", "vendor/UPSTREAM_LICENSES.txt")}
        if not required <= names:
            raise ValueError("APK is missing the diagram shell or retained license files")
        if any(name in names for name in ("assets/diagrams/vendor/mermaid.js", "assets/diagrams/vendor/excalidraw.js")) or any(
            name.startswith("assets/diagrams/vendor/fonts/") for name in names
        ):
            raise ValueError("APK still includes downloadable diagram renderers or fonts")
    if manifest.get("schemaVersion") != 1 or set(manifest.get("packages", {})) != {"mermaid", "canvas"}:
        raise ValueError("APK diagram package manifest is incomplete")
    assets = []
    for kind, spec in sorted(manifest["packages"].items()):
        version = spec["version"]
        if not re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version):
            raise ValueError("Diagram package must use a stable immutable version")
        name = f"diagram-{kind}-{version}.zip"
        if spec["url"] != f"https://github.com/SomeVlad/trilium-client/releases/download/{version}/{name}":
            raise ValueError("Diagram package URL must pin its content release")
        path = directory / name
        if not path.is_file() or path.stat().st_size != spec["size"] or digest(path) != spec["sha256"]:
            raise ValueError("Diagram package differs from the APK manifest: " + name)
        expected = spec["files"]
        if expected != sorted(set(expected)) or any(
            not item or item.startswith("/") or ".." in item.split("/") or "\\" in item for item in expected
        ):
            raise ValueError("Unsafe or duplicate diagram package inventory")
        required_files = {"THIRD_PARTY_NOTICES.txt", "UPSTREAM_LICENSES.txt", "mermaid.js" if kind == "mermaid" else "excalidraw.js"}
        if not required_files <= set(expected) or kind == "canvas" and not any(item.startswith("fonts/") for item in expected):
            raise ValueError("Diagram package renderer, fonts or licenses are missing")
        with ZipFile(path) as archive:
            if archive.namelist() != expected or sum(info.file_size for info in archive.infolist()) != spec["unpackedSize"]:
                raise ValueError("Diagram package contents differ from the APK manifest")
            if any(info.is_dir() or info.external_attr >> 28 == 0xA for info in archive.infolist()) or archive.testzip():
                raise ValueError("Diagram package has unsafe or corrupted entries")
        assets.append(name)
    return assets


def publish(tag, directory):
    if not re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", tag):
        raise ValueError("Only stable version tags are supported")
    version = tag[1:]
    binary = f"trilium-client-{version}.apk"
    names = [binary, f"trilium-client-{version}-source.tar.gz", *diagram_assets(directory / binary, directory), "SHA256SUMS"]
    if set(path.name for path in directory.iterdir()) != set(names):
        raise ValueError("Release directory must contain exactly the APK, source archive, diagram packages and SHA256SUMS")
    expected_sums = "".join(f"{digest(directory / name)}  ./{name}\n" for name in names[:-1])
    if (directory / "SHA256SUMS").read_text() != expected_sums:
        raise ValueError("SHA256SUMS does not match the release assets")
    result = gh("release", "view", tag, "--json", "isDraft,assets,url", check=False)
    if result.returncode:
        if "release not found" not in result.stderr.lower() and "(HTTP 404)" not in result.stderr:
            raise RuntimeError(result.stderr.strip())
        gh("release", "create", tag, "--verify-tag", "--draft", "--title", f"Trilium Client {version}",
           "--notes", f"Signed Android APK, optional Mermaid/Canvas packages and corresponding source for {tag}.\n\n"
           "Verify the downloaded files with `sha256sum -c SHA256SUMS`.")
        result = gh("release", "view", tag, "--json", "isDraft,assets,url")
    release = json.loads(result.stdout)
    existing = [asset["name"] for asset in release["assets"]]
    if len(existing) != len(set(existing)) or set(existing) - set(names):
        raise ValueError("Existing release contains unexpected or duplicate assets; review it manually")
    with tempfile.TemporaryDirectory(prefix="trilium-release-verify-") as temp:
        for name in existing:
            gh("release", "download", tag, "--pattern", name, "--dir", temp)
            if digest(Path(temp) / name) != digest(directory / name):
                raise ValueError(f"Existing asset {name} differs; refusing to overwrite it")
    missing = [name for name in names if name not in existing]
    if not release["isDraft"]:
        if missing:
            raise ValueError("Published release is incomplete; refusing to change a published release")
        print(f"Published assets already match: {release['url']}")
        return
    if missing:
        gh("release", "upload", tag, *(str(directory / name) for name in missing))
    # Download and check every uploaded byte before publishing the draft.
    with tempfile.TemporaryDirectory(prefix="trilium-release-verify-") as temp:
        gh("release", "download", tag, "--dir", temp)
        if set(path.name for path in Path(temp).iterdir()) != set(names):
            raise ValueError("Draft release asset set is incomplete or unexpected")
        for name in names:
            if digest(Path(temp) / name) != digest(directory / name):
                raise ValueError(f"Uploaded asset {name} differs from the verified build")
    gh("release", "edit", tag, "--draft=false", "--latest")
    print(f"Published {release['url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    publish(args.tag, args.directory)


if __name__ == "__main__":
    main()
