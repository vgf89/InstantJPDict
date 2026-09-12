#!/usr/bin/env python3
"""Build the vendored kanji variant table (#44 lookup fold + popup variants).

Two sources, because the relation needs two signals:

1. **Unihan `kSemanticVariant` + `kZVariant`** — "the same character written differently".
   Direction rule: canonical = the side present in the recogniser's `vocab.json`, variant =
   the side that is not. Both-sides-known and both-sides-unknown pairs are dropped, because
   folding exists to map a form something downstream cannot handle onto one it can.
   (`囘` -> `回`, `欝` -> `鬱` come out of this half.)

2. **JMdict `oK`/`rK` kanji-form tags, INTERSECTED with Unihan's simplified/traditional
   axis** (added for #44 after `摑`/`掴` was reported missing). Each source alone is unsafe:
   Unihan's s/t axis also links characters that are *different words* in Japanese
   (`誌`/`志`, `製`/`制`), and JMdict's tags alone also mark rare *spellings* rather than old
   orthography (`長` for 丈/たけ, `階` for 品/しな). Their intersection is the kyujitai class.
   Direction comes from JMdict (a Japanese dictionary saying "out-dated"/"rarely-used"),
   never from Unihan: its s/t orientation is the Chinese one and disagrees for a measurable
   share of pairs (it calls 煙 the old form of 烟).

   JMdict's tag values are XML entities (`&oK;`) declared in a DTD the download does not
   carry, so a real XML parser sees empty tag text and silently finds nothing — the
   entities are read textually here. Pairs are extracted POSITIONALLY: inside one entry,
   an oK/rK-tagged form and the entry's untagged form are compared character by character,
   and a single differing position is a pair. Single-character-only extraction misses
   `摑`/`掴`, because Japanese words are usually written with more than one character
   (掴む/摑む).

Only BMP CJK pairs are kept: the Kotlin API is `canonical(ch: Char): Char`, and
supplementary-plane ideographs are also a class the head cannot emit. Output is
byte-deterministic (sorted, LF, one `variant<TAB>canonical` per line) with a PROVENANCE.txt
sidecar, mirroring tools/build_component_table.py.

    curl -L -o /tmp/Unihan.zip https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    curl -L -o /tmp/JMdict_e.gz  http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz
    python3 tools/build_kanji_variants.py --zip /tmp/Unihan.zip --jmdict /tmp/JMdict_e.gz \\
        --vocab app/src/main/assets/PP-OCRv6_small_ncnn/vocab.json \\
        --out-dir app/src/main/assets/variants \\
        --check 囘:回 欝:鬱 摑:掴 國:国 會:会 \\
        --reject 誌:志 製:制 長:丈 階:品
"""
import argparse
import gzip
import hashlib
import json
import re
import sys
import zipfile
from datetime import date
from pathlib import Path

SOURCE = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"
JM_SOURCE = "http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
LICENSE = "Unicode License v3 (https://www.unicode.org/license.txt)"
JM_LICENSE = "CC BY-SA 4.0 (EDRDG, https://www.edrdg.org/edrdg/licence.html)"
FIELDS = ("kSemanticVariant", "kZVariant")
ST_FIELDS = ("kSimplifiedVariant", "kTraditionalVariant")
OLD_TAGS = {"oK", "rK"}

# BMP CJK ranges: Ext A, URO, Compatibility Ideographs (and its supplement).
CJK_RANGES = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF))

ENTRY = re.compile(r"<entry>(.*?)</entry>", re.S)
KELE = re.compile(r"<k_ele>(.*?)</k_ele>", re.S)
KEB = re.compile(r"<keb>(.*?)</keb>", re.S)
KEINF = re.compile(r"<ke_inf>&([A-Za-z]+);</ke_inf>|<ke_inf>([A-Za-z]+)</ke_inf>")


def is_cjk(ch):
    o = ord(ch)
    return any(lo <= o <= hi for lo, hi in CJK_RANGES)


def parse_unihan_variants(text, fields=FIELDS):
    """`U+4E18<TAB>kSemanticVariant<TAB>U+3400<kMatthews` lines -> [(src, tgt, field)].

    Each target carries an optional trailing provenance tag (`<kMatthews`, `<kHanYu`,
    `<kIRG_GSource`, …) which is not part of the character; a target field may also list
    several characters, and those are returned separately.
    """
    pairs = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or parts[1] not in fields:
            continue
        src = parts[0]
        if not src.startswith("U+"):
            continue
        for target in parts[2].split():
            field = target.split("<", 1)[0]  # strip <kMatthews-style tag
            if field.startswith("U+"):
                pairs.append((src, field, parts[1]))
    return pairs


def to_char(field):
    """`U+4E18` -> the character, or None if it is not a single BMP code unit."""
    cp = int(field[2:], 16)
    if cp > 0xFFFF or 0xD800 <= cp <= 0xDFFF:
        return None
    return chr(cp)


def build_unihan(text, vocab):
    """(variant, canonical) -> citation, by the vocabulary direction rule."""
    out = {}
    for src_field, tgt_field, field in parse_unihan_variants(text):
        src, tgt = to_char(src_field), to_char(tgt_field)
        if src is None or tgt is None or src == tgt:
            continue
        if not (is_cjk(src) and is_cjk(tgt)):
            continue
        in_src, in_tgt = src in vocab, tgt in vocab
        if in_src == in_tgt:
            continue  # both known (nothing to gain) or both unknown
        variant, canonical = (tgt, src) if in_src else (src, tgt)
        out.setdefault((variant, canonical),
                       f"Unihan {field} {src_field}->{tgt_field}")
    return out


def build_jmdict(jm_text, st_links, vocab):
    """(variant, canonical) -> citation, for entry-tagged old forms Unihan also links.

    Both forms must be emittable (in vocab): the pair then serves *model output* — the head
    can emit either side, and only the modern side is a dictionary headword.
    """
    out = {}
    for body in ENTRY.findall(jm_text):
        seq = re.search(r"<ent_seq>(\d+)</ent_seq>", body)
        seq = seq.group(1) if seq else "?"
        tagged, plain = [], []
        for ke in KELE.findall(body):
            keb = KEB.search(ke)
            if not keb:
                continue
            form = keb.group(1)
            tags = {a or b for a, b in KEINF.findall(ke)}
            hit = tags & OLD_TAGS
            (tagged if hit else plain).append((form, sorted(hit)[0] if hit else ""))
        if not tagged or not plain:
            continue
        base = plain[0][0]
        for form, tag in tagged:
            if len(form) != len(base):
                continue
            diff = [i for i in range(len(form)) if form[i] != base[i]]
            if len(diff) != 1:
                continue
            o, m = form[diff[0]], base[diff[0]]
            if o == m or not (is_cjk(o) and is_cjk(m)):
                continue
            if o not in vocab or m not in vocab:
                continue          # must be emittable on both sides to serve model output
            if frozenset((o, m)) not in st_links:
                continue          # not the same character in different orthography
            out.setdefault((o, m), f"JMdict ent_seq {seq} ke_inf {tag}")
    return out


def unihan_st_links(text):
    """frozenset({a, b}) for every pair on the simplified/traditional axis (either way)."""
    links = set()
    for src_field, tgt_field, _ in parse_unihan_variants(text, ST_FIELDS):
        src, tgt = to_char(src_field), to_char(tgt_field)
        if src and tgt and src != tgt:
            links.add(frozenset((src, tgt)))
    return links


def resolve_chains(citations):
    """Point every variant at the TERMINAL canonical.

    A canonical that is itself a variant elsewhere leaves a chain (`冩` -> `寫` -> `写`).
    The query-side fold is a single character pass, so a chained entry stops one step
    short of the form a dictionary actually indexes and the lookup misses anyway. Chains
    are followed to the end; pairs inside a genuine cycle (both directions present, e.g.
    `干`/`乾`) are left alone and recorded as cycles for the fold to exclude.
    """
    by_variant = {}
    for (v, c) in citations:
        by_variant.setdefault(v, []).append(c)

    def terminal(start):
        cur, seen = start, set()
        while cur in by_variant:
            if cur in seen:
                return None            # cycle
            seen.add(cur)
            cur = by_variant[cur][0]
        return cur

    out, chained = {}, []
    for (v, c), cite in citations.items():
        t = terminal(c)
        if t is None or t == c:
            out[(v, c)] = cite
            continue
        out[(v, t)] = f"{cite} [chained via {c}]"
        chained.append((v, c, t))
    return out, chained


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", required=True, help="Unihan.zip from unicode.org")
    ap.add_argument("--jmdict", help="JMdict_e.gz from edrdg.org (adds the oK/rK half)")
    ap.add_argument("--vocab", required=True,
                    help="PP-OCRv6_small_ncnn/vocab.json — decides the direction")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--retrieved", default=date.today().isoformat(),
                    help="retrieval date recorded in PROVENANCE.txt")
    ap.add_argument("--check", nargs="*", default=[],
                    help="assert these `variant:canonical` pairs are present")
    ap.add_argument("--reject", nargs="*", default=[],
                    help="assert these `variant:canonical` pairs are ABSENT")
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
    citations = build_unihan(raw, vocab)
    n_unihan = len(citations)
    n_jm = 0
    jm_revision = "not used"
    if args.jmdict:
        with gzip.open(args.jmdict, "rt", encoding="utf-8", errors="replace") as f:
            jm_text = f.read()
        head = jm_text[:2000]
        jm_revision = next((l.strip(" <!->") for l in head.splitlines()
                            if "JMdict" in l and "Rev" in l), "?")
        st = unihan_st_links(raw)
        jm = build_jmdict(jm_text, st, vocab)
        n_jm = len(jm)
        overlap = [p for p in jm if p in citations]
        for pair, cite in jm.items():
            if pair in citations:
                citations[pair] += f"; {cite}"
            else:
                citations[pair] = cite
        print(f"JMdict half: {n_jm:,} pairs from oK/rK x Unihan axis "
              f"({len(overlap):,} already in the Unihan half)")

    citations, chained = resolve_chains(citations)
    for v, c, t in chained:
        print(f"chain resolved: {v} -> {c} -> {t} (fold must reach {t})")

    pairs = sorted(citations)
    multi = sum(1 for v in {v for v, _ in pairs} if sum(1 for x, _ in pairs if x == v) > 1)
    cycles = sorted({p for p in citations if (p[1], p[0]) in citations})
    data = ("".join(f"{v}\t{c}\n" for v, c in pairs)).encode("utf-8")

    # Validate before writing anything, so a failed check cannot leave a half-bad asset
    # behind — and print the source line each required pair came from, so "the pair is in
    # the file" is auditable rather than asserted.
    have = set(pairs)
    bad, untraceable = [], []
    for spec in args.check:
        variant, canonical = spec.split(":")
        if (variant, canonical) not in have:
            bad.append(spec)
        elif not citations[(variant, canonical)]:
            untraceable.append(spec)
        else:
            print(f"  {spec} <- {citations[(variant, canonical)]}")
    for spec in args.reject:
        variant, canonical = spec.split(":")
        if (variant, canonical) in have:
            print(f"{spec}: present but required ABSENT ({citations[(variant, canonical)]})",
                  file=sys.stderr)
            bad.append(spec)
    if bad or untraceable:
        for spec in bad:
            print(f"{spec}: check failed", file=sys.stderr)
        for spec in untraceable:
            print(f"{spec}: in the table but not traceable to a source", file=sys.stderr)
        raise SystemExit(1)
    if args.check or args.reject:
        print(f"checked {len(args.check)} required (present) and "
              f"{len(args.reject)} rejected (absent) pairs")

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "kanji_variants.txt").write_bytes(data)

    detail = "".join(f"  {v} -> {c}\t{citations[(v, c)]}\n" for v, c in pairs)
    prov = (
        "Kanji variant table — vendored, do not edit by hand.\n\n"
        f"Entries    : {len(pairs)} pairs over {len({v for v, _ in pairs})} distinct variants\n"
        f"             ({n_unihan} from Unihan kSemanticVariant/kZVariant;\n"
        f"              {n_jm} from JMdict oK/rK intersected with Unihan's\n"
        "              simplified/traditional axis — see the builder's docstring)\n"
        f"             ({multi} variants have more than one canonical candidate; the file\n"
        "             keeps every pair and the lookup fold resolves them on the corpus —\n"
        "             see docs/bundled-dictionaries.md)\n"
        f"File       : kanji_variants.txt\n"
        f"Size       : {len(data)} bytes\n"
        f"SHA-256    : {hashlib.sha256(data).hexdigest()}\n\n"
        f"Source 1   : {SOURCE}\n"
        f"Files      : Unihan_Variants.txt ({len(names)} files in the bundle)\n"
        f"Revision   : Unicode {unihan_version}, data of {unihan_date}\n"
        f"Source 2   : {JM_SOURCE}\n"
        f"Revision   : {jm_revision}\n"
        f"Retrieved  : {args.retrieved}\n"
        f"License    : {LICENSE}\n"
        f"             {JM_LICENSE}\n\n"
        "Direction  : variant -> canonical. Unihan half: canonical is the side present in\n"
        "             PP-OCRv6_small_ncnn/vocab.json, variant the side that is not; both-known\n"
        "             and both-unknown pairs dropped. JMdict half: both sides emittable, the\n"
        "             direction taken from JMdict's out-dated/rarely-used kanji tag.\n"
        "             Only BMP CJK pairs (the Kotlin API is Char-based).\n\n"
        f"Cycle pairs (both directions present, fold must exclude them): {len(cycles)}\n"
        + "".join(f"  {a} <-> {b}\n" for a, b in cycles) +
        f"\nChains resolved to the terminal canonical ({len(chained)}):\n"
        + "".join(f"  {v} -> {c} -> {t}\n" for v, c, t in chained) +
        "\nPer-pair citations:\n" + detail +
        "Generated by tools/build_kanji_variants.py — rerun rather than editing.\n"
    )
    (out / "PROVENANCE.txt").write_text(prov, encoding="utf-8")

    print(f"wrote {out / 'kanji_variants.txt'} ({len(pairs):,} pairs, {len(data):,} bytes)")
    print(f"wrote {out / 'PROVENANCE.txt'} ({len(cycles)} cycle pairs recorded)")


if __name__ == "__main__":
    main()
