#!/usr/bin/env python3
"""Generate the Rust half of the bundled-licence bundle (#70).

`nav_graph_core` is Rust, compiled to `lib/arm64-v8a/libnav_graph_core.so` and
shipped inside the APK. The crates it links carry their own licences, and most of
them (MIT / Apache-2.0 / MPL-2.0) require their notice and licence text to travel
with the binary. This script enumerates them from the pinned lockfile and copies
each crate's own licence file out of the crate as Cargo published it — so the text
is the crate's, not a canonical template someone re-typed.

Outputs (both committed; the build only reads them):

  app/licenses/rust.tsv                                index rows for :app:generateLicenseIndex
  app/src/main/assets/licenses/texts/rust/*.txt        the licence texts themselves

Texts are content-deduplicated: a file byte-identical to one already shipped (or
to a canonical text already in texts/) is referenced rather than copied again, so
the Apache-2.0 template lands in the APK once instead of forty times.

Regenerate:

    python3 tools/gen_rust_license_bundle.py

Requires `cargo` on PATH (the lockfile and the registry sources it already
downloaded are enough; --locked refuses to drift from Cargo.lock).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CRATE_DIR = REPO / "nav_graph_core"
ASSETS = REPO / "app/src/main/assets"
LICENSE_TEXTS = ASSETS / "licenses/texts"
RUST_TEXTS = LICENSE_TEXTS / "rust"
RUST_TSV = REPO / "app/licenses/rust.tsv"
TARGET = "aarch64-linux-android"

# Crates whose published source carries no licence file (the uniffi family is
# MPL-2.0 and publishes none). Falls back to the canonical text fetched from
# upstream. Anything not listed here and missing a licence file is a hard error:
# shipping a crate with no notice is the failure this tool exists to prevent.
NO_FILE_FALLBACK = {
    "MPL-2.0": "licenses/texts/mpl-2.0.txt",
}

LICENSE_FILE_RE = re.compile(r"^(LICEN[CS]E|COPYING|NOTICE)", re.IGNORECASE)

HEADER = """\
# Rust crates compiled into lib/arm64-v8a/libnav_graph_core.so — GENERATED.
#
# Regenerate with:  python3 tools/gen_rust_license_bundle.py
# Consumed by :app:generateLicenseIndex; the licence texts live under
# app/src/main/assets/licenses/texts/rust/. See docs/licenses.md.
#
# Columns: name | licence | version | licence text file(s) | notice file | provenance
#
# Iteration order is name-sorted so regeneration is byte-deterministic.
"""


def cargo_metadata() -> dict:
    cmd = [
        "cargo", "metadata", "--locked", "--format-version", "1",
        "--filter-platform", TARGET,
    ]
    proc = subprocess.run(
        cmd, cwd=CRATE_DIR, capture_output=True, text=True,
        env={**os.environ, "CARGO_NET_OFFLINE": os.environ.get("CARGO_NET_OFFLINE", "0")},
    )
    if proc.returncode != 0:
        sys.exit(f"cargo metadata failed:\n{proc.stderr}")
    return json.loads(proc.stdout)


def runtime_closure(meta: dict) -> list[dict]:
    """Crates reachable from the root package through `normal` dependencies.

    Build-only and dev-only edges are excluded: they do not contribute code to
    the shared object. (uniffi's `cli` feature does pull its bindgen chain in as
    normal deps for the crate's own `uniffi-bindgen` binary; those are listed too,
    which errs towards attributing more rather than less.)
    """
    by_id = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    roots = [i for i in nodes if "nav_graph_core" in i]
    if len(roots) != 1:
        sys.exit(f"expected exactly one root node, found {roots}")

    seen: set[str] = set()

    def walk(node_id: str) -> None:
        for dep in nodes[node_id]["deps"]:
            if not any(k["kind"] in (None, "normal") for k in dep["dep_kinds"]):
                continue
            if dep["pkg"] not in seen:
                seen.add(dep["pkg"])
                walk(dep["pkg"])

    walk(roots[0])
    crates = [by_id[i] for i in seen]
    crates.sort(key=lambda p: p["name"])
    return crates


def registry_src() -> Path:
    root = Path.home() / ".cargo/registry/src"
    candidates = sorted(p for p in root.glob("*") if p.is_dir())
    if not candidates:
        sys.exit(f"no extracted crate sources under {root}; run `cargo fetch` first")
    return candidates[0]


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    meta = cargo_metadata()
    crates = runtime_closure(meta)
    src_root = registry_src()

    # Canonical texts already shipped; a crate file identical to one of these is
    # referenced, not copied.
    shared: dict[str, str] = {}
    for path in sorted(LICENSE_TEXTS.glob("*.txt")):
        shared[sha256_text(path.read_text(encoding="utf-8", errors="replace"))] = \
            str(path.relative_to(ASSETS))

    written: list[Path] = []
    rows: list[list[str]] = []
    problems: list[str] = []

    for crate in crates:
        name, version = crate["name"], crate["version"]
        licence = crate.get("license") or "UNKNOWN"
        crate_src = src_root / f"{name}-{version}"
        licence_files: list[Path] = []
        if crate_src.is_dir():
            licence_files = sorted(
                p for p in crate_src.iterdir() if p.is_file() and LICENSE_FILE_RE.match(p.name)
            )

        refs: list[str] = []
        if licence_files:
            for lf in licence_files:
                text = lf.read_text(encoding="utf-8", errors="replace")
                digest = sha256_text(text)
                if digest in shared:
                    refs.append(shared[digest])
                    continue
                dest = RUST_TEXTS / f"{name}-{lf.name}"
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(text, encoding="utf-8")
                written.append(dest)
                rel = str(dest.relative_to(ASSETS))
                shared[digest] = rel
                refs.append(rel)
        else:
            fallback = NO_FILE_FALLBACK.get(licence)
            if fallback:
                refs.append(fallback)
            else:
                problems.append(f"{name}-{version} ({licence}): no licence file in the crate and no fallback")

        rows.append([
            name,
            licence,
            version,
            ",".join(dict.fromkeys(refs)) or "-",
            "-",
            "cargo metadata --locked --filter-platform aarch64-linux-android over nav_graph_core/Cargo.lock",
        ])

    if problems:
        sys.exit("refusing to write an incomplete bundle:\n  " + "\n  ".join(problems))

    # Remove texts from crates that are no longer in the closure.
    keep = {p.resolve() for p in written}
    for stale in sorted(RUST_TEXTS.glob("*.txt")) if RUST_TEXTS.is_dir() else []:
        if stale.resolve() not in keep:
            stale.unlink()

    lines = [HEADER]
    for r in rows:
        lines.append("\t".join(r) + "\n")
    RUST_TSV.write_text("".join(lines), encoding="utf-8")

    from_crate = sum(1 for r in rows if r[3].startswith("licenses/texts/rust/"))
    shared_refs = sum(1 for r in rows for f in r[3].split(",")
                      if not f.startswith("licenses/texts/rust/"))
    print(f"{len(rows)} crates in the closure; {len(written)} distinct licence texts copied "
          f"into {RUST_TEXTS.relative_to(REPO)}; index written to {RUST_TSV.relative_to(REPO)} "
          f"({RUST_TSV.stat().st_size} bytes)")
    print(f"  rows whose text is the crate's own file : {from_crate}")
    print(f"  references to an already-shipped text   : {shared_refs}")


if __name__ == "__main__":
    main()
