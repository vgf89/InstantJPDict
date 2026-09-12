#!/usr/bin/env python3
"""Pack a char n-gram text table into the fixed-width binary the app binary-searches.

The app cannot parse a ~17 MB text table at startup, and building a HashMap of ~1.4M
entries costs tens of MB of heap. Instead the table ships as a sorted, fixed-width
binary: one 10-byte record per entry, so a lookup is a binary search over a byte range
with no allocation and no startup cost beyond mapping the file.

RECORD (little endian, 10 bytes)
    ngram   8 bytes  up to 4 UTF-16 code units, zero-padded on the right
    count   2 bytes  occurrences, **saturated** at 65535

Zero padding is unambiguous: U+0000 never occurs in Japanese text, and it sorts before
every real code unit, so a shorter n-gram sorts immediately before its extensions
("あ" < "あい") and a single binary search keyed on the padded n-gram finds an entry of
any order.

Saturation is deliberate: counts are only ever used as ratios in a back-off chain, so a
16-bit count preserves every distinction that matters and halves the record size. The
packer reports how many entries saturate so the loss is visible rather than assumed.

    python3 tools/pack_char_lm.py --table /tmp/char_lm_ship.txt --out /tmp/char_lm.bin
    python3 tools/pack_char_lm.py --table ... --out ... --verify
"""
import argparse
import struct
from pathlib import Path

MAGIC = b"CLM1"
HEADER = struct.Struct("<4sII")      # magic, entry count, max order
MAX_ORDER = 4
MAX_COUNT = 65535


def parse(text):
    for line in text.splitlines():
        if not line:
            continue
        ngram, _, count = line.rpartition("\t")
        if not ngram or not count:
            continue
        yield ngram, int(count)


def key_of(ngram):
    """The 8-byte zero-padded key: shorter n-grams sort before their extensions."""
    units = [ord(c) for c in ngram]
    if len(units) > MAX_ORDER:
        raise ValueError(f"n-gram longer than order {MAX_ORDER}: {ngram!r}")
    return struct.pack("<4H", *(units + [0] * (MAX_ORDER - len(units))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--table", required=True, help="text table from tools/build_char_lm.py")
    ap.add_argument("--out", required=True)
    ap.add_argument("--verify", action="store_true",
                    help="re-read the output and check counts against the text table")
    args = ap.parse_args()

    text = Path(args.table).read_text(encoding="utf-8")
    records = []
    saturated = 0
    longest = 0
    skipped_astral = 0
    for ngram, count in parse(text):
        # Supplementary-plane characters are single Python code points above U+FFFF and
        # cannot occupy one 16-bit field (they are surrogate pairs in UTF-16). They never
        # matter here — the candidates being ranked are BMP kanji — and the project keeps
        # the same BMP-only convention for the variant table. Counted, not silently dropped.
        if any(ord(c) > 0xFFFF for c in ngram):
            skipped_astral += 1
            continue
        longest = max(longest, len(ngram))
        if count > MAX_COUNT:
            saturated += 1
        records.append((key_of(ngram), min(count, MAX_COUNT)))
    records.sort(key=lambda r: r[0])

    keys = [k for k, _ in records]
    if len(set(keys)) != len(keys):
        raise SystemExit("duplicate n-gram keys after padding — refusing to write")

    out = Path(args.out)
    with out.open("wb") as f:
        f.write(HEADER.pack(MAGIC, len(records), longest))
        for key, count in records:
            f.write(key)
            f.write(struct.pack("<H", count))

    size = out.stat().st_size
    print(f"entries      : {len(records):,} (max order {longest})")
    print(f"file         : {out} ({size:,} bytes, {size / len(records):.1f} B/entry)")
    print(f"saturated    : {saturated:,} counts clipped at {MAX_COUNT}")
    print(f"skipped      : {skipped_astral:,} n-grams containing supplementary-plane chars")

    if args.verify:
        raw = out.read_bytes()
        magic, count, order = HEADER.unpack_from(raw, 0)
        assert magic == MAGIC, magic
        assert count == len(records), (count, len(records))
        by_key = dict(records)
        checked = 0
        for i in range(0, len(records), max(1, len(records) // 500)):
            off = HEADER.size + i * 10
            key = raw[off:off + 8]
            got = struct.unpack_from("<H", raw, off + 8)[0]
            want = by_key[key]
            assert got == want, (key, got, want)
            checked += 1
        print(f"verify       : {checked} sampled records match the text table, "
              f"header count matches, order {order}")
        # a search-order invariant the loader depends on: a prefix sorts before its extension
        first_of_2 = next(k for k, _ in records if k[2:4] != b"\x00\x00" and k[4:] == b"\x00" * 4)
        ext = first_of_2[:4] + b"\x00\x01\x00\x00"
        exts = [k for k, _ in records if k[:4] == first_of_2[:4] and k[4:6] == b"\x00\x01"]
        if exts:
            assert first_of_2 < exts[0], "prefix must sort before extension"
            print("verify       : prefix sorts before its extension (back-off order holds)")


if __name__ == "__main__":
    main()
