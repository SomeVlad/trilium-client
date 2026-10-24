#!/usr/bin/env python3
"""Publish a complete draft, refusing to replace assets on any rerun."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def gh(*args, check=True):
    return subprocess.run(["gh", *args], check=check, capture_output=True, text=True)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def publish(tag, directory):
    if not re.fullmatch(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", tag):
        raise ValueError("Only stable version tags are supported")
    version = tag[1:]
    names = [f"trilium-client-{version}.apk", f"trilium-client-{version}-source.tar.gz", "SHA256SUMS"]
    if set(path.name for path in directory.iterdir()) != set(names):
        raise ValueError("Release directory must contain exactly the APK, source archive and SHA256SUMS")
    expected_sums = "".join(f"{digest(directory / name)}  ./{name}\n" for name in names[:2])
    if (directory / "SHA256SUMS").read_text() != expected_sums:
        raise ValueError("SHA256SUMS does not match the release assets")
    result = gh("release", "view", tag, "--json", "isDraft,assets,url", check=False)
    if result.returncode:
        if "release not found" not in result.stderr.lower() and "(HTTP 404)" not in result.stderr:
            raise RuntimeError(result.stderr.strip())
        gh("release", "create", tag, "--verify-tag", "--draft", "--title", f"Trilium Client {version}",
           "--notes", f"Signed Android APK and corresponding source for {tag}.\n\n"
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
