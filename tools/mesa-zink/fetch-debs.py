#!/usr/bin/env python3
"""Downloads the arm64 -dev packages (and the libraries they depend on) that the Mesa cross build links against.

Uses Ubuntu 24.04's frozen release pocket ("noble", not noble-updates), so the set of packages never changes:
the index is pinned by its SHA256 and every .deb is checked against the index.
Usage: fetch-debs.py [--runtime] OUT_DIR PACKAGE...
  --runtime follows every dependency (to run the packages, e.g. lavapipe for smoke-aarch64.sh).
"""
import hashlib
import lzma
import os
import re
import sys
import urllib.request

MIRROR = "https://ports.ubuntu.com/ubuntu-ports/"
INDEX = "dists/noble/main/binary-arm64/Packages.xz"
INDEX_SHA256 = "4a1901e6124fb0a111f5dffc8f5c14474f449e2ecfa71f2eaf0b29917edb53f9"
# Provided by the cross toolchain (libc6-dev-arm64-cross etc.) or not needed for linking.
SKIP = {"libc6", "libc6-dev", "libgcc-s1", "libstdc++6", "linux-libc-dev", "libcrypt-dev", "libcrypt1",
        "debconf", "dpkg", "perl", "python3", "pkg-config", "pkgconf", "libpkgconf3", "pkgconf-bin",
        "x11-common", "xorg-sgml-doctools"}


def fetch(path, dst, sha256):
    if not (os.path.exists(dst) and sha256_of(dst) == sha256):
        urllib.request.urlretrieve(MIRROR + path, dst + ".part")
        os.replace(dst + ".part", dst)
    if sha256_of(dst) != sha256:
        sys.exit("checksum mismatch: " + path)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    args = sys.argv[1:]
    runtime = args[0] == "--runtime"
    if runtime:
        args = args[1:]
    out_dir, wanted = args[0], args[1:]
    os.makedirs(out_dir, exist_ok=True)
    index = os.path.join(out_dir, "Packages.xz")
    fetch(INDEX, index, INDEX_SHA256)

    pkgs = {}
    for stanza in lzma.open(index).read().decode("utf-8", "replace").split("\n\n"):
        fields = {}
        for line in stanza.splitlines():
            if line and not line[0].isspace() and ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields

    todo, seen = list(wanted), set()
    while todo:
        name = todo.pop()
        if name in seen or name in SKIP:
            continue
        seen.add(name)
        fields = pkgs.get(name)
        if not fields:
            sys.exit("package not found: " + name)
        # For linking, follow the dependencies of -dev packages only: runtime libraries are just needed to link.
        if runtime or name.endswith("-dev") or name in wanted:
            for alt in (fields.get("Depends", "") + "," + fields.get("Pre-Depends", "")).split(","):
                dep = re.split(r"[ (:|]", alt.split("|")[0].strip())[0]
                if dep and (runtime or dep.endswith("-dev") or dep.startswith(("lib", "zlib"))):
                    todo.append(dep)

    for name in sorted(seen):
        fields = pkgs[name]
        fetch(fields["Filename"], os.path.join(out_dir, os.path.basename(fields["Filename"])), fields["SHA256"])
        print(name, fields["Version"])


if __name__ == "__main__":
    main()
