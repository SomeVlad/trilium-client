#!/usr/bin/env python3
"""Check the release tag, trusted branch, APK identity and signing certificate."""
import argparse
from pathlib import Path
import re
import subprocess

CERTIFICATE_SHA256 = "ce0d4a08415d8560d7b1795aa14d91e3352d1a869ba1f0fd4066e582975e65de"
STABLE_TAG = r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def verify(tag, build_file, check_git=False, apk=None, apksigner=None, aapt=None):
    if not re.fullmatch(STABLE_TAG, tag):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH without a prerelease suffix")
    build = Path(build_file).read_text()
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', build, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', build, re.M)
    if len(names) != 1 or len(codes) != 1 or names[0] != tag[1:]:
        raise ValueError("Tag must exactly match the single app versionName/versionCode declaration")
    if check_git:
        head = run("git", "rev-parse", "HEAD")
        if run("git", "rev-parse", f"refs/tags/{tag}^{{commit}}") != head:
            raise ValueError("Checked-out commit does not match release tag")
        subprocess.run(["git", "merge-base", "--is-ancestor", head, "refs/remotes/origin/master"], check=True)
    if apk:
        if not apksigner or not aapt:
            raise ValueError("APK verification requires --apksigner and --aapt")
        signature = run(apksigner, "verify", "--verbose", "--print-certs", str(apk))
        digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.M)
        if [digest.lower() for digest in digests] != [CERTIFICATE_SHA256]:
            raise ValueError("APK signing certificate does not match the pinned release identity")
        package = run(aapt, "dump", "badging", str(apk)).splitlines()[0]
        identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", package)
        if not identity or identity.groups() != ("ru.vlad.triliumnotes", codes[0], names[0]):
            raise ValueError("APK package/version does not match the source release")
    return names[0], codes[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--build-file", default="app/build.gradle.kts")
    parser.add_argument("--check-git", action="store_true")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--apksigner")
    parser.add_argument("--aapt")
    args = parser.parse_args()
    version, code = verify(args.tag, args.build_file, args.check_git, args.apk, args.apksigner, args.aapt)
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"version={version}\nversion_code={code}\n")
    print(f"Verified release {args.tag} (versionCode {code})")


if __name__ == "__main__":
    main()
