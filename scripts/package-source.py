#!/usr/bin/env python3
"""Package an exact application revision and the source of its runtime libraries.

Run :app:writeReleaseDependencyManifest for the same clean revision first. No
Gradle process, local connection configuration or signing configuration is read
by this script. Downloads use only the official HTTPS sources recorded below.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_HOSTS = {
    "codeload.github.com", "cdn.ckeditor.com", "registry.npmjs.org",
    "dl.google.com", "repo.maven.apache.org",
}


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.PIPE)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def checked_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password:
        raise ValueError("Source download is not from an approved public HTTPS host")
    return url


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Downloads:
    def __init__(self, cache: Path):
        self.cache = cache
        cache.mkdir(parents=True, exist_ok=True)

    def get(self, url: str, *, digest: str | None = None, integrity: str | None = None) -> bytes:
        checked_url(url)
        target = self.cache / sha256(url.encode())
        if target.is_file():
            data = target.read_bytes()
        else:
            request = urllib.request.Request(url, headers={"User-Agent": "trilium-client-source-package/1"})
            with urllib.request.build_opener(PublicRedirects()).open(request, timeout=60) as response:
                checked_url(response.url)
                data = response.read()
            # Parallel downloads can share an identical URL. Atomic replacement
            # prevents another worker from observing a partly written archive.
            with tempfile.NamedTemporaryFile(dir=self.cache, delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(data)
            temporary.replace(target)
        if digest and sha256(data) != digest:
            raise ValueError("A locked source archive failed its SHA-256 check")
        if integrity:
            algorithm, expected = integrity.split("-", 1)
            actual = base64.b64encode(hashlib.new(algorithm, data).digest()).decode()
            if actual != expected:
                raise ValueError("An npm archive differs from the upstream lockfile integrity")
        return data


def npm_filename(package: dict) -> str:
    return package["name"].replace("/", "__") + "-" + package["version"] + ".tgz"


def npm_url(package: dict) -> str:
    name, version = package["name"], package["version"]
    return f"https://registry.npmjs.org/{name}/-/{name.rsplit('/', 1)[-1]}-{version}.tgz"


def editor_inputs(lock: dict, downloads: Downloads) -> tuple[dict[str, bytes], list[dict]]:
    """Keep original archives intact, including lockfiles and license files."""
    files: dict[str, bytes] = {}
    records: list[dict] = []
    editor = lock["editor"]
    for item in [editor["source"], editor["distribution"], *editor["additionalPreferredSources"]]:
        data = downloads.get(item["url"], digest=item["sha256"])
        path = "third-party-sources/editor/" + item["filename"]
        files[path] = data
        records.append({**item, "path": path})

    def fetch(package: dict) -> tuple[str, bytes, dict]:
        url = npm_url(package)
        data = downloads.get(url, integrity=package["integrity"])
        path = "third-party-sources/npm/" + npm_filename(package)
        return path, data, {**package, "url": url, "path": path, "sha256": sha256(data)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for path, data, record in pool.map(fetch, editor["npmPackages"]):
            files[path] = data
            records.append(record)

    # Source maps complement the original TypeScript tree and let readers
    # compare every module of the exact unmodified UMD distribution.
    distribution = files["third-party-sources/editor/" + editor["distribution"]["filename"]]
    with zipfile.ZipFile(io.BytesIO(distribution)) as archive:
        source_map = json.loads(archive.read("ckeditor5/ckeditor5.umd.js.map"))
        if len(source_map["sources"]) != len(source_map["sourcesContent"]) or any(
            content is None for content in source_map["sourcesContent"]
        ):
            raise ValueError("The editor distribution has incomplete source-map content")
        covered = {p["name"] + "@" + p["version"] for p in editor["npmPackages"]}
        for source in source_map["sources"]:
            match = re.search(r"node_modules/\.pnpm/([^/]+)/node_modules/", source)
            if match and match[1].replace("+", "/", 1) not in covered:
                raise ValueError("An editor runtime module has no locked source package")

    # blurhash's npm tarball supplies its original TypeScript in sourcesContent.
    # Materialize it as well, so modifying it needs no source-map tooling.
    blurhash = next(p for p in editor["npmPackages"] if p["name"] == "blurhash")
    with tarfile.open(fileobj=io.BytesIO(files["third-party-sources/npm/" + npm_filename(blurhash)])) as archive:
        member = archive.extractfile("package/dist/index.js.map")
        if member is None:
            raise ValueError("blurhash's embedded TypeScript source is unavailable")
        source_map = json.load(member)
        if len(source_map["sources"]) != len(source_map["sourcesContent"]):
            raise ValueError("blurhash's source map is incomplete")
        for source, content in zip(source_map["sources"], source_map["sourcesContent"]):
            path = "/".join(p for p in PurePosixPath(source).parts if p not in (".", "..", "/"))
            if content is None or not path.endswith(".ts"):
                raise ValueError("blurhash's original TypeScript is missing")
            files["third-party-sources/blurhash-preferred/" + path] = content.encode()
    return files, records


def editor_notices(lock: dict, files: dict[str, bytes]) -> bytes:
    output = [
        "Third-party notices for the CKEditor 5 49.0.0 integration\n",
        "This file preserves notices from the exact production dependency closure\n"
        "in the upstream pnpm-lock.yaml, including modules bundled in the UMD file.\n"
        "Their original licenses apply; these notices do not change those licenses.\n"
        "Preferred source and package checksums are recorded in sources.lock.json\n"
        "and accompany each binary release in its source archive.\n",
    ]
    for package in lock["editor"]["npmPackages"]:
        output.append("\n" + "=" * 72 + "\n" + package["name"] + " " + package["version"] + "\n")
        data = files["third-party-sources/npm/" + npm_filename(package)]
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            notices = sorted(
                (m for m in archive.getmembers() if m.isfile() and re.search(
                    r"(^|/)(?:licen[sc]e|copying|notice)(?:[./-]|$)", m.name, re.IGNORECASE
                )), key=lambda m: m.name
            )
            if not notices:
                if package["name"] != "blurhash":
                    raise ValueError("An editor dependency has no retained license notice")
                # The npm package declares MIT but omits a license file. CKEditor's
                # own LICENSE.md supplies this copyright and identifies MIT.
                output.append("Copyright (c) 2018 Wolt Enterprises\n\n" + lock["editor"]["blurhashMitText"])
            for member in notices:
                content = archive.extractfile(member)
                assert content is not None
                output.append("\n" + member.name + "\n\n" + content.read().decode("utf-8").rstrip() + "\n")
    # Upstream notice files use mixed line endings. Match the repository's
    # text=auto/eol=lf policy so regeneration also matches a fresh Git archive.
    return "\n".join(output).replace("\r\n", "\n").replace("\r", "\n").encode()



def diagram_lock_from_archive(app_data: bytes) -> dict | None:
    """The requested revision, never the working tree, supplies the viewer inventory."""
    with tarfile.open(fileobj=io.BytesIO(app_data)) as archive:
        try:
            content = archive.extractfile("scripts/diagrams/sources.lock.json")
        except KeyError:
            if any(m.name.startswith("app/src/main/assets/diagrams/") for m in archive):
                raise ValueError("Diagram assets have no committed source lock")
            return None
        if content is None:
            raise ValueError("Missing diagram source lock")
        lock = json.load(content)
        if lock.get("schemaVersion") != 1:
            raise ValueError("Unsupported diagram source lock schema")
        npm_lock_file = archive.extractfile("scripts/diagrams/package-lock.json")
        if npm_lock_file is None:
            raise ValueError("Missing diagram npm lockfile")
        npm_lock = json.load(npm_lock_file)
        expected = {path: item for path, item in npm_lock["packages"].items() if path and item.get("resolved")}
        actual = {item["path"]: item for item in lock["packages"]}
        if len(actual) != len(lock["packages"]) or set(expected) != set(actual):
            raise ValueError("Diagram npm source closure differs from package-lock.json")
        for path, item in expected.items():
            record = actual[path]
            name = path.rsplit("node_modules/", 1)[-1]
            if any(record.get(key) != value for key, value in {
                "name": name, "version": item["version"], "url": item["resolved"],
                "integrity": item["integrity"], "dev": item.get("dev", False),
            }.items()):
                raise ValueError("Diagram npm source integrity differs from package-lock.json")
        asset_prefix = "app/src/main/assets/diagrams/vendor/"
        bundled = lock["bundledFiles"]
        assets = {m.name.removeprefix(asset_prefix) for m in archive if m.isfile() and m.name.startswith(asset_prefix)}
        if assets != set(bundled):
            raise ValueError("Diagram asset inventory is incomplete")
        for name, digest in bundled.items():
            if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                raise ValueError("Unsafe diagram asset path")
            content = archive.extractfile(asset_prefix + name)
            if content is None or sha256(content.read()) != digest:
                raise ValueError("Bundled diagram content differs from its source lock: " + name)
        required = {"mermaid.js", "excalidraw.js", "viewer.js", "THIRD_PARTY_NOTICES.txt", "UPSTREAM_LICENSES.txt"}
        if not required <= set(bundled) or not any(name.startswith("fonts/") and name.endswith(".woff2") for name in bundled):
            raise ValueError("Diagram renderer, local fonts or retained notices are missing")
        for name in ("mermaid", "excalidraw", "viewer"):
            content = archive.extractfile("scripts/diagrams/maps/" + name + ".js.map")
            metadata = archive.extractfile("scripts/diagrams/maps/" + name + ".meta.json")
            if content is None or metadata is None:
                raise ValueError("Missing diagram source map or dependency metadata")
            source_map = json.load(content)
            if len(source_map["sources"]) != len(source_map["sourcesContent"]) or any(x is None for x in source_map["sourcesContent"]):
                raise ValueError("Diagram source map has incomplete original content")
            inputs = json.load(metadata)["inputs"]
            for source in inputs:
                if source.startswith("node_modules/"):
                    package_matches = list(re.finditer(r"(?:^|/)node_modules/(?:@[^/]+/)?[^/]+", source))
                    package_path = source[:package_matches[-1].end()] if package_matches else ""
                    if package_path not in actual:
                        raise ValueError("A bundled diagram module has no locked npm source")
        for required_file in ("scripts/diagrams/build.mjs", "scripts/diagrams/package.json", "scripts/diagrams/README.txt"):
            if archive.extractfile(required_file) is None:
                raise ValueError("Missing diagram build recipe")
        upstream = lock["upstreamSources"]
        if not {"mermaid", "@excalidraw/excalidraw"} <= {item["name"] for item in upstream}:
            raise ValueError("Missing preferred diagram renderer source archive")
        for name, digest in lock.get("preferredSources", {}).items():
            if not name.startswith("preferred-sources/") or PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                raise ValueError("Unsafe preferred diagram source path")
            content = archive.extractfile("scripts/diagrams/" + name)
            if content is None or sha256(content.read()) != digest:
                raise ValueError("Preferred diagram source differs from its source lock")
        return lock


def diagram_inputs(lock: dict, downloads: Downloads) -> tuple[dict[str, bytes], list[dict]]:
    """Retain exact npm archives (including the pinned bundler) and preferred upstream trees."""
    files: dict[str, bytes] = {}
    records: list[dict] = []
    unique: dict[tuple[str, str], dict] = {}
    for item in lock["packages"]:
        key = (item["name"], item["version"])
        if key in unique and any(unique[key][field] != item[field] for field in ("url", "integrity")):
            raise ValueError("Conflicting diagram source archives for the same npm version")
        unique[key] = item
    def fetch(item: dict) -> tuple[str, bytes, dict]:
        if not re.fullmatch(r"(?:@[A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+", item["name"]) or not re.fullmatch(r"[A-Za-z0-9_.+-]+", item["version"]):
            raise ValueError("Unsafe diagram npm coordinate")
        data = downloads.get(item["url"], integrity=item["integrity"])
        path = "third-party-sources/diagrams/npm/" + npm_filename(item)
        # The original package archive must remain readable, including its preferred sources/notices.
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            if not archive.getmembers():
                raise ValueError("Empty diagram npm source archive")
        return path, data, {**item, "npmLockPath": item["path"], "path": path, "sha256": sha256(data)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for path, data, record in pool.map(fetch, (unique[key] for key in sorted(unique))):
            files[path] = data
            records.append(record)
    for item in lock["upstreamSources"]:
        filename = item["filename"]
        if PurePosixPath(filename).name != filename or not re.fullmatch(r"[A-Za-z0-9_.-]+", filename):
            raise ValueError("Unsafe diagram upstream source filename")
        data = downloads.get(item["url"], digest=item["sha256"])
        path = "third-party-sources/diagrams/upstream/" + filename
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            if not archive.getmembers():
                raise ValueError("Empty preferred diagram source archive")
        files[path] = data
        records.append({**item, "path": path})
    return files, records


def maven_inputs(dependencies: list[dict], lock: dict, downloads: Downloads) -> tuple[dict[str, bytes], list[dict]]:
    def validate(coordinate: dict) -> None:
        for key in ("group", "name", "version"):
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", coordinate[key]):
                raise ValueError("Invalid Maven coordinate in dependency manifest")

    def download(coordinate: dict, suffix: str) -> tuple[str, bytes, dict]:
        validate(coordinate)
        relative = coordinate["group"].replace(".", "/") + "/" + coordinate["name"] + "/" + coordinate["version"]
        filename = coordinate["name"] + "-" + coordinate["version"] + suffix
        failures = []
        repositories = sorted(lock["maven"]["repositories"], key=lambda url:
                              ("google.com" in url) != coordinate["group"].startswith("androidx."))
        for repository in repositories:
            url = repository.rstrip("/") + "/" + relative + "/" + filename
            try:
                data = downloads.get(url)
                if suffix == "-sources.jar":
                    with zipfile.ZipFile(io.BytesIO(data)) as source:
                        if not source.namelist():
                            raise ValueError("Empty source archive")
                else:
                    ET.fromstring(data)
                path = "third-party-sources/maven/" + relative + "/" + filename
                return path, data, {"path": path, "url": url, "sha256": sha256(data)}
            except (urllib.error.URLError, ValueError, zipfile.BadZipFile, ET.ParseError) as error:
                failures.append(type(error).__name__)
        raise ValueError("Missing corresponding source/POM for " + ":".join(coordinate[k] for k in
                         ("group", "name", "version")) + ": " + filename + " (" + ", ".join(failures) + ")")

    def fetch(dependency: dict) -> tuple[dict[str, bytes], dict]:
        coordinate = ":".join(dependency[k] for k in ("group", "name", "version"))
        files = {}
        records = []
        for suffix in ("-sources.jar", ".pom"):
            path, data, record = download(dependency, suffix)
            files[path] = data
            records.append(record)
        # Preserve inherited license/build metadata as well (for example,
        # listenablefuture's Apache license comes from guava-parent).
        pom = ET.fromstring(data)
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        seen = {coordinate}
        parent_records = []
        while (parent := pom.find("m:parent", namespace)) is not None:
            parent_coordinate = {k: parent.findtext("m:" + xml, default="", namespaces=namespace)
                                 for k, xml in (("group", "groupId"), ("name", "artifactId"), ("version", "version"))}
            parent_name = ":".join(parent_coordinate[k] for k in ("group", "name", "version"))
            if parent_name in seen or len(seen) > 20:
                raise ValueError("Invalid Maven parent POM chain")
            seen.add(parent_name)
            path, parent_data, record = download(parent_coordinate, ".pom")
            files[path] = parent_data
            parent_records.append({"coordinate": parent_name, **record})
            pom = ET.fromstring(parent_data)
        return files, {"coordinate": coordinate, "artifact": dependency["artifact"],
                       "files": records, "parentPoms": parent_records}

    files: dict[str, bytes] = {}
    records: list[dict] = []
    # The manifest is produced from resolved artifacts rather than declared
    # dependencies: variants and runtime transitive libraries are included.
    unique = {tuple(d[k] for k in ("group", "name", "version")): d for d in dependencies}
    if not unique:
        raise ValueError("The release runtime dependency manifest is empty")
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for dependency_files, record in pool.map(fetch, (unique[k] for k in sorted(unique))):
            files.update(dependency_files)
            records.append(record)
    return files, records


def safe_app_archive(commit: str, lock: dict) -> bytes:
    data = git("archive", "--format=tar", commit)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if member.isdir():
                continue
            if not member.isfile() or path.is_absolute() or ".." in path.parts:
                raise ValueError("Unexpected non-regular application source entry")
            forbidden = any(p in {".git", ".private", ".gradle", ".kotlin", "build", "artifacts", "captures"} for p in path.parts)
            forbidden |= path.name in {"private.properties", "signing.properties", "local.properties"}
            if path.suffix.lower() in {".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx"}:
                fixture_hash = lock.get("publicTestFixtures", {}).get(member.name)
                content = archive.extractfile(member)
                forbidden |= fixture_hash is None or content is None or sha256(content.read()) != fixture_hash
            if forbidden:
                raise ValueError("Private configuration, binary or signing material found in the source revision: " + member.name)
        for name, digest in lock["editor"]["bundledFiles"].items():
            content = archive.extractfile("app/src/main/assets/editor/vendor/" + name)
            if content is None or sha256(content.read()) != digest:
                raise ValueError("Bundled editor content differs from sources.lock.json: " + name)
    diagram_lock_from_archive(data)
    return data


def add_bytes(archive: tarfile.TarFile, name: str, data: bytes, timestamp: int, mode: int = 0o644) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mtime = timestamp
    info.mode = mode
    archive.addfile(info, io.BytesIO(data))


def package(args: argparse.Namespace) -> None:
    commit = git("rev-parse", "--verify", "--end-of-options", args.ref + "^{commit}").decode().strip()
    # Notice regeneration is an explicit maintenance operation against the
    # working lockfile. Release packaging always reads the requested git ref.
    lock = json.loads((ROOT / "sources.lock.json").read_bytes() if args.write_editor_notices
                      else git("show", commit + ":sources.lock.json"))
    if lock.get("schemaVersion") != 1:
        raise ValueError("Unsupported source lock schema")
    downloads = Downloads(args.cache_dir)
    app_data = None if args.write_editor_notices else safe_app_archive(commit, lock)
    print("Downloading and verifying the locked editor sources...", flush=True)
    files, editor_records = editor_inputs(lock, downloads)
    notices = editor_notices(lock, files)
    if args.write_editor_notices:
        args.write_editor_notices.parent.mkdir(parents=True, exist_ok=True)
        args.write_editor_notices.write_bytes(notices)
        print("Wrote editor dependency notices.")
        return
    assert app_data is not None
    with tarfile.open(fileobj=io.BytesIO(app_data)) as archive:
        notice = archive.extractfile("app/src/main/assets/editor/vendor/THIRD_PARTY_LICENSES.txt")
        if notice is None or notice.read() != notices:
            raise ValueError("Bundled editor dependency notices need regeneration")

    diagrams = diagram_lock_from_archive(app_data)
    diagram_records = []
    if diagrams is not None:
        print("Downloading and verifying the locked offline diagram sources...", flush=True)
        diagram_files, diagram_records = diagram_inputs(diagrams, downloads)
        files.update(diagram_files)

    metadata = json.loads(args.dependencies.read_text())
    if metadata.get("schemaVersion") != 1 or metadata.get("configuration") != "releaseRuntimeClasspath":
        raise ValueError("Expected a resolved releaseRuntimeClasspath manifest")
    if metadata.get("gitCommit") != commit:
        raise ValueError("Dependency manifest does not belong to the requested application commit")
    version = metadata["versionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError("Invalid application version in dependency manifest")
    print("Downloading source jars and POMs for every resolved runtime library...", flush=True)
    maven_files, maven_records = maven_inputs(metadata["dependencies"], lock, downloads)
    files.update(maven_files)
    timestamp = int(git("show", "-s", "--format=%ct", commit))
    prefix = "trilium-client-" + version + "-source/"
    manifest = {
        "schemaVersion": 1, "application": {"commit": commit, "ref": args.ref, "versionName": version},
        "editor": editor_records, "runtimeLibraries": maven_records,
        "diagrams": {"sources": diagram_records, "bundledFiles": diagrams["bundledFiles"] if diagrams else {},
                     "preferredSources": diagrams.get("preferredSources", {}) if diagrams else {}},
    }
    files["SOURCE_MANIFEST.json"] = json_bytes(manifest)
    files["RUNTIME_DEPENDENCIES.json"] = json_bytes(metadata)
    files["BUILDING_FROM_SOURCE.md"] = (
        "# Source for trilium-client " + version + "\n\n"
        "Application revision: `" + commit + "`. This directory is an exact git archive;\n"
        "the added third-party-sources directory contains the editor's original source\n"
        "tree, distribution with complete source maps, locked npm packages, original\n"
        "TypeScript supplements, and all resolved Maven runtime source jars and POMs.\n"
        "SOURCE_MANIFEST.json records the original HTTPS URLs and SHA-256 checksums.\n\n"
        "Build the app using JDK 17, Android SDK 36 and `./gradlew -PdemoBuild=true\n"
        ":app:assembleDebug` (one shell command). The README explains local setup and\n"
        "signing a modified release with your own key. Local signing keys and personal\n"
        "connection settings are deliberately absent. Credentials are entered on the\n"
        "device. The included loopback certificate is a public synthetic test fixture.\n\n"
        "To modify the editor, unpack its preferred-source archive, follow its checked-in\n"
        "Node/pnpm engine requirements, run `pnpm install --frozen-lockfile` and `pnpm build`,\n"
        "then replace the three editor vendor assets from packages/ckeditor5/dist. The\n"
        "upstream build scripts and lockfile are included. Use the npm source packages\n"
        "and TypeScript supplements when modifying their corresponding modules.\n\n"
        "The offline diagram viewer has a separate pinned build in scripts/diagrams.\n"
        "Its npm archives include the exact renderer dependencies and esbuild toolchain,\n"
        "and the preferred Mermaid/Excalidraw source trees are included under\n"
        "third-party-sources/diagrams/upstream. Full source maps and the build recipe\n"
        "are in scripts/diagrams/maps and scripts/diagrams/build.mjs. With Node 22, run\n"
        "`cd scripts/diagrams && npm ci --ignore-scripts && npm run build && npm test`.\n"
        "The local font files and retained font/dependency notices are inventoried in\n"
        "scripts/diagrams/sources.lock.json; no device-side CDN is required.\n\n"
        "The sources are provided alongside the APK under the licenses and copyright\n"
        "notices preserved in LICENSE, NOTICE, THIRD_PARTY_NOTICES.md and the archives.\n"
    ).encode()
    files["SOURCE_SHA256SUMS"] = "".join(
        sha256(data) + "  " + path + "\n" for path, data in sorted(files.items())
    ).encode()
    if args.output is None:
        raise ValueError("An output archive path is required")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        with temporary.open("wb") as stream, gzip.GzipFile(fileobj=stream, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as result:
                with tarfile.open(fileobj=io.BytesIO(app_data)) as source:
                    for member in source:
                        if member.isfile():
                            content = source.extractfile(member)
                            assert content is not None
                            add_bytes(result, prefix + member.name, content.read(), timestamp, member.mode)
                for name, data in sorted(files.items()):
                    add_bytes(result, prefix + name, data, timestamp)
        temporary.replace(args.output)
    finally:
        temporary.unlink(missing_ok=True)
    print(json.dumps({"archive": str(args.output), "applicationCommit": commit,
                      "runtimeLibraries": len(maven_records), "npmPackages": len(lock["editor"]["npmPackages"]),
                      "diagramSourceArchives": len(diagram_records),
                      "sha256": sha256(args.output.read_bytes())}))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ref", help="Exact release tag (or a commit for local verification)")
    parser.add_argument("output", nargs="?", type=Path, help="Destination .tar.gz source archive")
    parser.add_argument("--dependencies", type=Path, default=ROOT / "app/build/release-metadata/runtime-dependencies.json")
    parser.add_argument("--cache-dir", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "trilium-client-sources")
    parser.add_argument("--write-editor-notices", type=Path, help="Regenerate retained editor notices, without a release package")
    args = parser.parse_args()
    try:
        package(args)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, tarfile.TarError, zipfile.BadZipFile) as error:
        # Avoid tracing environment or HTTP headers into release logs.
        print("Source packaging failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
