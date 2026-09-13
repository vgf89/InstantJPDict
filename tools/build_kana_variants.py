#!/usr/bin/env python3
"""Build the vendored kana variant table (#75 lookup-query normaliser).

**Source is JMdict's own kana cross-references, not a hand-written list.** JMdict
records, inside a single entry, more than one kana reading for the same word, and
tags the non-standard ones with `<re_inf>` (`&ok;` out-dated/obsolete kana usage,
`&sk;` search-only kana form, `&rk;` rarely-used kana form, `&ik;` irregular kana).
Two readings of one entry that differ at exactly one position by a character on a
historical-kana row are JMdict saying "these are the same word, written two ways"
(`かつかざん`/`かっかざん`, `あはれ`/`あわれ`, `あづま`/`あずま`). That alternation is the
evidence this builder turns into a table; a pair ships only if JMdict attests it.

Rows (the standard 歴史的仮名遣い -> 現代仮名遣い correspondences):
  wagyou   ワ行  ゐ->い ゑ->え
  dakugyou だ行  ぢ->じ づ->ず
  sokuon   促音  つ->っ (and katakana)
  yoon     拗音  や->ゃ ゆ->ゅ よ->ょ (and katakana)
  smallv   小書き  あ->ぁ … — DROPPED, the variant side is ordinary modern usage

Direction is a rule, not taken per-pair from JMdict, because JMdict tags both sides
of the same alternation depending on the entry: **the canonical side is the modern
orthography** — the small form for a size row, `い`/`え` for ワ行, `じ`/`ず` for
だ行 — which is the form the imported dictionary's headwords use. A reading that
carries `&re_inf>` on the variant side is counted as direction evidence, not as a
requirement.

`を`/`ヲ` are deliberately NOT emitted. They are the ordinary modern accusative
particle, so folding them would rewrite correct modern queries (`本を読む` ->
`本お読む`); JMdict attests the を->お alternation only inside a handful of lexical
items (`をことてん`, `みやこをどり`), where the change is word-specific and not a
row-wide rule. The generator drops the row member and records the count it dropped.

Output is byte-deterministic (sorted, LF, one `variant<TAB>canonical` per line) with
a PROVENANCE.txt sidecar, mirroring tools/build_kanji_variants.py.

    curl -L -o /tmp/JMdict_e.gz http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz
    python3 tools/build_kana_variants.py --jmdict /tmp/JMdict_e.gz \\
        --out-dir app/src/main/assets/variants \\
        --check ゐ:い ゑ:え づ:ず つ:っ よ:ょ ゆ:ゅ \\
        --reject を:お ヲ:オ づ:ず
"""
import argparse
import gzip
import hashlib
import re
import sys
from datetime import date
from pathlib import Path

JM_SOURCE = "http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
JM_LICENSE = "CC BY-SA 4.0 (EDRDG, https://www.edrdg.org/edrdg/licence.html)"

ENTRY = re.compile(r"<entry>(.*?)</entry>", re.S)
RELE = re.compile(r"<r_ele>(.*?)</r_ele>", re.S)
REB = re.compile(r"<reb>([^<]+)</reb>")
REINF = re.compile(r"<re_inf>&?([A-Za-z]+);?</re_inf>")
# The tags that mark a kana reading as not-the-standard-form.
OLD_TAGS = {"ok", "sk", "rk", "ik"}

ROWS = {
    "wagyou": [("ゐ", "い"), ("ゑ", "え"), ("を", "お"),
               ("ヰ", "イ"), ("ヱ", "エ"), ("ヲ", "オ")],
    "dakugyou": [("ぢ", "じ"), ("づ", "ず"), ("ヂ", "ジ"), ("ヅ", "ズ")],
    "sokuon": [("つ", "っ"), ("ツ", "ッ")],
    "yoon": [("や", "ゃ"), ("ゆ", "ゅ"), ("よ", "ょ"),
             ("ヤ", "ャ"), ("ユ", "ュ"), ("ヨ", "ョ")],
    "smallv": [("あ", "ぁ"), ("い", "ぃ"), ("う", "ぅ"), ("え", "ぇ"), ("お", "ぉ"), ("わ", "ゎ"),
               ("ア", "ァ"), ("イ", "ィ"), ("ウ", "ゥ"), ("エ", "ェ"), ("オ", "ォ"), ("ワ", "ヮ")],
}

# Ordinary modern usage: the variant side is a standard modern kana, so shipping the
# row would rewrite correct modern queries. Folding `を` would turn 「本を読む」 into
# 「本お読む」; folding `あ`/`い`/`う`/`え`/`お`/`わ` would rewrite「ああ」as「あぁ」.
# JMdict attests both alternations, but only inside a handful of lexical items
# (をことてん, みやこをどり; すいません/すぃません) — a word-specific spelling, not a
# row-wide historical-kana rule. The row member is dropped and the count recorded.
EXCLUDED = {
    ("を", "お"): "ordinary modern usage (the accusative particle)",
    ("ヲ", "オ"): "ordinary modern usage (the accusative particle)",
    ("あ", "ぁ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("い", "ぃ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("う", "ぅ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("え", "ぇ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("お", "ぉ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("わ", "ゎ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("ア", "ァ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("イ", "ィ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("ウ", "ゥ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("エ", "ェ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("オ", "ォ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
    ("ワ", "ヮ"): "ordinary modern usage; the 小書き row is gairaigo spelling, not 旧仮名遣い",
}


def readings(body):
    """[(reading, {tags})] for one entry."""
    out = []
    for ele in RELE.findall(body):
        reb = REB.search(ele)
        if not reb:
            continue
        out.append((reb.group(1), set(REINF.findall(ele))))
    return out


def census(jm_text):
    """Count JMdict attestations per (variant, canonical) row member.

    An attestation is one entry holding two same-length readings that differ at
    exactly one position, and that position is this row member. `direction` counts
    the subset where the variant side carried an `re_inf` tag.
    """
    hits = {}
    for body in ENTRY.findall(jm_text):
        rs = readings(body)
        for i in range(len(rs)):
            for j in range(len(rs)):
                if i == j:
                    continue
                (a, ta), (b, tb) = rs[i], rs[j]
                if len(a) != len(b) or a == b:
                    continue
                diff = [(x, y, k) for k, (x, y) in enumerate(zip(a, b)) if x != y]
                if len(diff) != 1:
                    continue
                x, y, _ = diff[0]
                for row in ROWS.values():
                    if (x, y) not in row:
                        continue
                    h = hits.setdefault((x, y), {"n": 0, "direction": 0, "rows": set()})
                    h["n"] += 1
                    if ta:
                        h["direction"] += 1
                    for name, members in ROWS.items():
                        if (x, y) in members:
                            h["rows"].add(name)
    return hits


def script_pair(variant):
    """The same code point in the other kana script, or None."""
    o = ord(variant)
    if 0x3041 <= o <= 0x3096:
        return chr(o + 0x60)
    if 0x30A1 <= o <= 0x30F6:
        return chr(o - 0x60)
    return None


def build(hits):
    """(variant, canonical) -> citation, for every attested row member."""
    out = {}
    for row in ROWS.values():
        for variant, canonical in row:
            if (variant, canonical) in EXCLUDED:
                continue
            h = hits.get((variant, canonical))
            if h:
                out[(variant, canonical)] = ("JMdict re_inf %s, %d reading pair(s), %d "
                                             "with the variant tagged %s"
                                             % ("/".join(sorted(h["rows"])), h["n"],
                                                h["direction"], "/".join(sorted(h["rows"]))))
                continue
            # Script closure: JMdict records the alternation in the other script only
            # (`ユ`->`ュ` is attested, hiragana `ゆ`->`ゅ` is not). The row is a
            # kana-inventory fact, so the counterpart is emitted and cited as such.
            other = script_pair(variant)
            if other is None:
                continue
            h = hits.get((other, script_pair(canonical)))
            if h:
                out[(variant, canonical)] = ("JMdict re_inf %s, %d reading pair(s) in the "
                                             "other script (%s->%s); script counterpart"
                                             % ("/".join(sorted(h["rows"])), h["n"],
                                                other, script_pair(canonical)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jmdict", required=True, help="JMdict_e.gz from edrdg.org")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--retrieved", default=date.today().isoformat())
    ap.add_argument("--check", nargs="*", default=[])
    ap.add_argument("--reject", nargs="*", default=[])
    args = ap.parse_args()

    with gzip.open(args.jmdict, "rt", encoding="utf-8", errors="replace") as f:
        jm_text = f.read()
    rev = next((l.strip(" <!->") for l in jm_text[:6000].splitlines()
                if re.match(r"\s*<!--\s*(Rev\s+[\d.]+|JMdict created:)", l)), "?")

    hits = census(jm_text)
    citations = build(hits)

    dropped = []
    for row in ROWS.values():
        for variant, canonical in row:
            if (variant, canonical) in EXCLUDED:
                dropped.append((variant, canonical, hits.get((variant, canonical), {}).get("n", 0)))
            elif (variant, canonical) not in citations:
                dropped.append((variant, canonical, 0))

    pairs = sorted(citations)
    data = ("".join(f"{v}\t{c}\n" for v, c in pairs)).encode("utf-8")

    have = set(pairs)
    bad = []
    for spec in args.check:
        variant, canonical = spec.split(":")
        if (variant, canonical) not in have:
            bad.append(spec)
        else:
            print(f"  {spec} <- {citations[(variant, canonical)]}")
    for spec in args.reject:
        variant, canonical = spec.split(":")
        if (variant, canonical) in have:
            print(f"{spec}: present but required ABSENT", file=sys.stderr)
            bad.append(spec)
    if bad:
        for spec in bad:
            print(f"{spec}: check failed", file=sys.stderr)
        raise SystemExit(1)
    if args.check or args.reject:
        print(f"checked {len(args.check)} required (present) and "
              f"{len(args.reject)} rejected (absent) pairs")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "kana_variants.txt").write_bytes(data)

    detail = "".join(f"  {v} -> {c}\t{citations[(v, c)]}\n" for v, c in pairs)
    prov = (
        "Kana variant table — vendored, do not edit by hand.\n\n"
        f"Entries    : {len(pairs)} pairs over {len({v for v, _ in pairs})} distinct variants\n"
        f"File       : kana_variants.txt\n"
        f"Size       : {len(data)} bytes\n"
        f"SHA-256    : {hashlib.sha256(data).hexdigest()}\n\n"
        f"Source     : {JM_SOURCE}\n"
        f"Revision   : {rev}\n"
        f"Retrieved  : {args.retrieved}\n"
        f"License    : {JM_LICENSE}\n\n"
        "Direction  : variant -> canonical, canonical = the modern orthography (the form\n"
        "             the imported dictionary's headwords use): the small form for a size\n"
        "             row, い/え for ワ行, じ/ず for だ行. The direction is a row rule, not\n"
        "             taken per pair, because JMdict tags either side of the same\n"
        "             alternation depending on the entry.\n"
        "Rows       : wagyou ワ行 ゐ->い ゑ->え; dakugyou だ行 ぢ->じ づ->ず; sokuon 促音\n"
        "             つ->っ; yoon 拗音 や->ゃ ゆ->ゅ よ->ょ; each in hiragana and katakana.\n"
        "             The 小書き row (あ->ぁ …) and を->お are dropped: their variant side\n"
        "             is ordinary modern usage, so folding them would break correct lookups.\n\n"
        f"Dropped ({len(dropped)} row members — no JMdict attestation, or excluded):\n"
        + "".join(f"  {v} -> {c}\t{n} reading pair(s){' — ' + EXCLUDED[(v, c)] if (v, c) in EXCLUDED else ' — no JMdict attestation'}\n"
                  for v, c, n in dropped) +
        "\nPer-pair citations:\n" + detail +
        "Generated by tools/build_kana_variants.py — rerun rather than editing.\n"
    )
    (out / "kana_variants.PROVENANCE.txt").write_text(prov, encoding="utf-8")

    print(f"wrote {out / 'kana_variants.txt'} ({len(pairs)} pairs, {len(data)} bytes)")
    print(f"dropped {len(dropped)} row members: "
          + ", ".join(f"{v}->{c}" for v, c, _ in dropped))


if __name__ == "__main__":
    main()
