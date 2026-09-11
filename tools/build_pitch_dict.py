#!/usr/bin/env python3
"""Build a Yomitan pitch-accent dictionary from the Kanjium dataset (#43).

Source : https://github.com/mifunetoshiro/kanjium
         data/source_files/raw/accents.txt
License: CC BY-SA 4.0 (see the repo's LICENSE.txt). Redistribution of the
         produced zip must carry attribution and the same license; this script
         writes pitch/PROVENANCE.txt beside the artifact for that purpose, and
         #70 surfaces the notices in-app.

Input format (tab separated, one record per line):
    <term> \t <reading> \t <position>[,<position>...]
Positions are mora downstep indices; 0 means no downstep (heiban). A few
records annotate the position with a part-of-speech hint, e.g. "(副)0" —
the hint is dropped, the trailing integer is kept.

Output: a Yomitan term-meta-bank v3 zip:
    index.json            {"title": ..., "format": 3, ...}
    term_meta_bank_1.json [[term, "pitch", {"reading": r, "pitches": [{"position": n}, ...]}], ...]

Usage:
    python3 tools/build_pitch_dict.py accents.txt
    # -> app/src/main/assets/pitch/kanjium_pitch_accents.zip (+ PROVENANCE.txt)

Deterministic: same input -> byte-identical output (sorted keys, fixed
separators, no timestamps), so a vendored copy can be hash-verified and a
rebuild that differs means the input changed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from collections import OrderedDict

# Pinned upstream revision this build is validated against; bump deliberately.
SOURCE_COMMIT = "685d4d723d6d20bf9beb169103aeac188eb067ad"
SOURCE_REPO = "https://github.com/mifunetoshiro/kanjium"
SOURCE_LICENSE = "CC BY-SA 4.0"
DICTIONARY_TITLE = "Kanjium Pitch Accents"

# Vendored straight into the APK so the feature needs no network or file picker.
DEFAULT_OUTPUT = "app/src/main/assets/pitch/kanjium_pitch_accents.zip"
PROVENANCE_FILENAME = "PROVENANCE.txt"
POSITION_HINT_RE = re.compile(r"^(?:\([^)]*\))?\s*(\d+)$")


def parse_positions(field: str) -> list[int]:
    """Positions from the third column, dropping any "(pos)" hint."""
    positions: list[int] = []
    for token in field.split(","):
        token = token.strip()
        if not token:
            continue
        m = POSITION_HINT_RE.match(token)
        if not m:
            continue  # unparseable token — skip rather than emit junk
        positions.append(int(m.group(1)))
    return positions


def build_entries(lines) -> list[list]:
    """(term, reading) -> merged, ordered positions.

    Kana-only words (あっさり, あかんべ, …) ship an EMPTY reading column — the
    term IS the reading. Resolving that matters twice: it keeps those ~16k
    words in the output, and it is what makes the position/mora-count relation
    hold (with a blank reading every position looks out of range).
    """
    merged: "OrderedDict[tuple[str, str], list[int]]" = OrderedDict()
    for line in lines:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        term, reading, raw_positions = (p.strip() for p in parts)
        if not term:
            continue
        if not reading:
            reading = term
        positions = parse_positions(raw_positions)
        if not positions:
            continue
        key = (term, reading)
        bucket = merged.setdefault(key, [])
        for p in positions:
            if p not in bucket:
                bucket.append(p)

    entries: list[list] = []
    for (term, reading), positions in merged.items():
        entries.append([
            term,
            "pitch",
            {"reading": reading, "pitches": [{"position": p} for p in positions]},
        ])
    return entries


def build_index() -> dict:
    return {
        "title": DICTIONARY_TITLE,
        "format": 3,
        "revision": f"kanjium@{SOURCE_COMMIT[:12]}",
        "sequenced": False,
        "attribution": "Pitch accent data from the Kanjium dataset "
                       "(https://github.com/mifunetoshiro/kanjium), CC BY-SA 4.0.",
    }


def write_zip(path: str, entries: list[list]) -> None:
    index = json.dumps(build_index(), ensure_ascii=False, sort_keys=True, indent=1)
    body = json.dumps(entries, ensure_ascii=False, separators=(",", ":"))
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name, payload in (("index.json", index), ("term_meta_bank_1.json", body)):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, payload)


def write_provenance(path: str, zip_path: str, entry_count: int) -> None:
    """Sidecar that ships in the APK assets: where this file came from, how to
    rebuild it, and the hash to verify it against (#43, feeds the #70 viewer)."""
    digest = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    size = os.path.getsize(zip_path)
    text = f"""Pitch accent dictionary — vendored, do not edit by hand.

Dictionary : {DICTIONARY_TITLE}
Entries    : {entry_count}
File       : {os.path.basename(zip_path)}
Size       : {size} bytes
SHA-256    : {digest}

Source     : {SOURCE_REPO} (data/source_files/raw/accents.txt)
Source ref : {SOURCE_COMMIT}
Retrieved  : accents.txt as published by the Kanjium project
License    : {SOURCE_LICENSE}
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Build a Yomitan pitch-accent dictionary from Kanjium accents.txt"
    )
    ap.add_argument("accents", help="path to Kanjium accents.txt")
    ap.add_argument(
        "-o",
        "--output",
        default=DEFAULT_OUTPUT,
        help=f"output zip path (default: {DEFAULT_OUTPUT})",
    )
    args = ap.parse_args()

    with open(args.accents, encoding="utf-8") as f:
        entries = build_entries(f)
    if not entries:
        print("no entries parsed — wrong file?", file=sys.stderr)
        return 1
    write_zip(args.output, entries)
    provenance = os.path.join(os.path.dirname(args.output), PROVENANCE_FILENAME)
    write_provenance(provenance, args.output, len(entries))
    print(f"wrote {args.output}: {len(entries)} pitch entries "
          f"(source commit {SOURCE_COMMIT[:12]})")
    print(f"wrote {provenance}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
