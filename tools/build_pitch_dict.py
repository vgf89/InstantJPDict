#!/usr/bin/env python3
"""Build a Yomitan pitch-accent dictionary from the Kanjium dataset (#43).

Source : https://github.com/mifunetoshiro/kanjium
         data/source_files/raw/accents.txt
License: CC BY-SA 4.0 (see the repo's LICENSE.txt). Redistribution of the
         produced zip must carry attribution and the same license; the app
         ships the notice in assets/licenses/ (see #70).

Input format (tab separated, one record per line):
    <term> \t <reading> \t <position>[,<position>...]
Positions are mora downstep indices; 0 means no downstep (heiban). A few
records annotate the position with a part-of-speech hint, e.g. "(副)0" —
the hint is dropped, the trailing integer is kept.

Output: a Yomitan term-meta-bank v3 zip:
    index.json            {"title": ..., "format": 3, ...}
    term_meta_bank_1.json [[term, "pitch", {"reading": r, "pitches": [{"position": n}, ...]}], ...]

Usage:
    python3 tools/build_pitch_dict.py accents.txt -o dist/kanjium_pitch_accents.zip

Deterministic: same input -> byte-identical output (sorted keys, fixed
separators, no timestamps), so the zip can be hash-pinned for the #71
one-step download catalog.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from collections import OrderedDict

# Pinned upstream revision this build is validated against; bump deliberately.
SOURCE_COMMIT = "685d4d723d6d20bf9beb169103aeac188eb067ad"
DICTIONARY_TITLE = "Kanjium Pitch Accents"
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
    """(term, reading) -> merged, ordered positions."""
    merged: "OrderedDict[tuple[str, str], list[int]]" = OrderedDict()
    for line in lines:
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        term, reading, raw_positions = (p.strip() for p in parts)
        if not term or not reading:
            continue
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


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Build a Yomitan pitch-accent dictionary from Kanjium accents.txt"
    )
    ap.add_argument("accents", help="path to Kanjium accents.txt")
    ap.add_argument("-o", "--output", default="kanjium_pitch_accents.zip")
    args = ap.parse_args()

    with open(args.accents, encoding="utf-8") as f:
        entries = build_entries(f)
    if not entries:
        print("no entries parsed — wrong file?", file=sys.stderr)
        return 1
    write_zip(args.output, entries)
    print(f"wrote {args.output}: {len(entries)} pitch entries "
          f"(source commit {SOURCE_COMMIT[:12]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
