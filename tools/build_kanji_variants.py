#!/usr/bin/env python3
"""Build the vendored kanji variant table (#44 lookup fold).

Unihan records, per character, the other characters that are *the same character*
written differently: `kSemanticVariant` (semantic/orthographic variants) and
`kZVariant` (z-variants, i.e. mainly glyph/regional shapes of one character). A
dictionary indexes one of the two forms and the recogniser emits the other, so a
lookup-time fold across the pair is free accuracy with no model and — because the
fold is query-side only — no over-correction risk.

**Direction rule (the point of this tool).** Unihan lists variants in both
directions and says nothing about which form a Japanese dictionary keys on. The
surviving direction is decided by the shipped recogniser's own dictionary:

    canonical = the side that IS present in
                app/src/main/assets/PP-OCRv6_small_ncnn/vocab.json
    variant   = the side that is NOT

Folding exists to map a form something downstream cannot handle onto one it can,
so the target of the fold must be the form the recogniser/dictionary already
knows. Pairs where *both* sides are in the dict (nothing to gain) or *neither*
(folding onto another unknown form just moves the miss) are dropped.

Two consequences worth stating out loud:

* `囘` -> `回` and `欝` -> `鬱` come out in that direction, as required — check
  them in the build output (`--check 囘:回 欝:鬱`).
* Because the canonical side is by construction the form in `vocab.json`, and a
  `vocab.json` entry is a class the head *could* be trained to emit, the fold is
  usable from a dictionary-side query too. It is query-side only: displayed OCR
  text is never rewritten (see `JapaneseUtil.foldLookupVariants`).

Only **BMP CJK** pairs are kept: the Kotlin API is `canonical(ch: Char): Char`
(one UTF-16 code unit), so supplementary-plane ideographs cannot be represented,
and they are also a class the head cannot emit.

Input is the Unicode Character Database's Unihan bundle:

    curl -L -o /tmp/Unihan.zip \\
        https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    python3 tools/build_kanji_variants.py --zip /tmp/Unihan.zip \\
        --vocab app/src/main/assets/PP-OCRv6_small_ncnn/vocab.json \\
        --out-dir app/src/main/assets/variants

Output is byte-deterministic (sorted, LF, one `variant<TAB>canonical` pair per
line) and is written with a PROVENANCE.txt sidecar, mirroring
tools/build_component_table.py.
"""
import argparse
import hashlib
import io
import json
import sys
import zipfile
from datetime import date
from pathlib import Path

SOURCE = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"
LICENSE = "Unicode License v3 (https://www.unicode.org/license.txt)"
FIELDS = ("kSemanticVariant", "kZVariant")

# BMP CJK ranges: Ext A, URO, Compatibility Ideographs (and its supplement).
CJK_RANGES = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF))


def is_cjk(ch):
    o = ord(ch)
    return any(lo <= o <= hi for lo, hi in CJK_RANGES)


def parse_unihan_variants(text):
    """`U+4E18<TAB>kSemanticVariant<TAB>U+3400<kMatthews` lines -> [(src, tgt)].

    Each target carries an optional trailing provenance tag (`<kMatthews`,
    `<kHanYu`, `<kIRG_GSource`, …) which is not part of the character; a target
    field may also list several characters, and those are returned separately.
    """
    pairs = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or parts[1] not in FIELDS:
            continue
        src = parts[0]
        if not src.startswith("U+"):
            continue
        for target in parts[2].split():
            field = target.split("<", 1)[0]  # strip <kMatthews-style tag
            if field.startswith("U+"):
                pairs.append((src, field))
    return pairs


def to_char(field):
    """`U+4E18` -> the character, or None if it is not a single BMP code unit."""
    cp = int(field[2:], 16)
    if cp > 0xFFFF or 0xD800 <= cp <= 0xDFFF:
        return None
    return chr(cp)


def build(text, vocab):
    """(variant_char, canonical_char) pairs, deduped, according to the direction rule."""
    out = set()
    for src_field, tgt_field in parse_unihan_variants(text):
        src, tgt = to_char(src_field), to_char(tgt_field)
        if src is None or tgt is None or src == tgt:
            continue
        if not (is_cjk(src) and is_cjk(tgt)):
            continue
        in_src, in_tgt = src in vocab, tgt in vocab
        if in_src == in_tgt:
            continue  # both known (nothing to gain) or both unknown
        variant, canonical = (tgt, src) if in_src else (src, tgt)
        out.add((variant, canonical))
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", required=True, help="Unihan.zip from unicode.org")
    ap.add_argument("--vocab", required=True,
                    help="PP-OCRv6_small_ncnn/vocab.json — decides the direction")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--retrieved", default=date.today().isoformat(),
                    help="retrieval date recorded in PROVENANCE.txt")
    ap.add_argument("--check", nargs="*", default=[],
                    help="assert these `variant:canonical` pairs are present")
    args = ap.parse_args()

    with zipfile.ZipFile(args.zip) as z:
        raw = z.read("Unihan_Variants.txt").decode("utf-8")
        names = z.namelist()

    header = [l for l in raw.splitlines() if l.startswith("#")][:5]
    unihan_version = next((l.split("Version", 1)[1].strip()
                           for l in header if "Version" in l), "?")
    unihan_date = next((l.split("Date:", 1)[1].strip()
                        for l in header if "Date:" in l), "?")

    vocab = set(json.loads(Path(args.vocab).read_text(encoding="utf-8")))
    pairs = build(raw, vocab)
    multi = sum(1 for v in {v for v, _ in pairs} if sum(1 for x, _ in pairs if x == v) > 1)

    data = ("".join(f"{v}\t{c}\n" for v, c in pairs)).encode("utf-8")

    # Validate the required pairs *before* writing anything, so a failed check cannot
    # leave a half-bad asset behind — and print the Unihan line each one came from, so
    # "the pair is in the file" is auditable rather than asserted.
    have = set(pairs)
    bad, untraceable = [], []
    for spec in args.check:
        variant, canonical = spec.split(":")
        if (variant, canonical) not in have:
            bad.append(spec)
            continue
        src, tgt = f"U+{ord(variant):04X}", f"U+{ord(canonical):04X}"
        printed = 0
        for line in raw.splitlines():
            parts = line.split("\t")
            if len(parts) != 3 or parts[0] != src or parts[1] not in FIELDS:
                continue
            if tgt in (f.split("<", 1)[0] for f in parts[2].split()):
                print(f"  {spec} <- {line}")
                printed += 1
        if not printed:
            untraceable.append(spec)
    if bad or untraceable:
        for spec in bad:
            print(f"{spec}: NOT in the built table", file=sys.stderr)
        for spec in untraceable:
            print(f"{spec}: in the table but not traceable to a parsed field",
                  file=sys.stderr)
        raise SystemExit(1)
    if args.check:
        print(f"checked {len(args.check)} required pairs: present and traceable")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    dst = out / "kanji_variants.txt"
    dst.write_bytes(data)

    prov = (
        "Kanji variant table (Unihan kSemanticVariant + kZVariant) — "
        "vendored, do not edit by hand.\n\n"
        f"Entries    : {len(pairs)} pairs over {len({v for v, _ in pairs})} distinct variants\n"
        f"             ({multi} variants have more than one canonical candidate in Unihan;\n"
        "             the file keeps every pair and the lookup fold resolves them on the\n"
        "             corpus — see docs/bundled-dictionaries.md)\n"
        f"File       : kanji_variants.txt\n"
        f"Size       : {len(data)} bytes\n"
        f"SHA-256    : {hashlib.sha256(data).hexdigest()}\n\n"
        f"Source     : {SOURCE}\n"
        f"Files      : Unihan_Variants.txt ({len(names)} files in the bundle)\n"
        f"Revision   : Unicode {unihan_version}, data of {unihan_date}\n"
        f"Retrieved  : {args.retrieved}\n"
        f"License    : {LICENSE}\n\n"
        "Direction  : variant -> canonical, where canonical is the side present in\n"
        "             PP-OCRv6_small_ncnn/vocab.json and variant is the side that is\n"
        "             not. Both-sides-known and both-sides-unknown pairs are dropped;\n"
        "             only BMP CJK pairs are kept (the Kotlin API is Char-based).\n\n"
        "Generated by tools/build_kanji_variants.py — rerun rather than editing.\n"
    )
    (out / "PROVENANCE.txt").write_text(prov, encoding="utf-8")

    print(f"wrote {dst} ({len(pairs):,} pairs, {len(data):,} bytes)")
    print(f"wrote {out / 'PROVENANCE.txt'}")


if __name__ == "__main__":
    main()
