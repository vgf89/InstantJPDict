#!/usr/bin/env python3
"""Regenerate the Kotlin lookup fold from the variant table (#44).

The fold is a strict subset of variants/kanji_variants.txt:

  * the previously shipped measured set is preserved verbatim (it was selected against
    225M characters of Aozora plus the calibration benches; re-deriving it here would
    silently change behaviour beyond what this change is about), and
  * the pairs the table gained from the JMdict half are added where the corpus supports
    the direction (`canonical >= variant`, so a variant the corpus never shows passes
    trivially — that is the `摑` case, absent from Aozora but printed in real books).

Cycle pairs (both directions in the table) are excluded, and no canonical may itself be a
key, so the fold stays idempotent — JapaneseUtilVariantFoldTest enforces both.

    python3 tools/build_variant_fold.py --table app/src/main/assets/variants/kanji_variants.txt \\
        --provenance app/src/main/assets/variants/PROVENANCE.txt \\
        --counts /tmp/kanji_counts.json --kotlin app/src/main/java/.../util/JapaneseUtil.kt
"""
import argparse
import json
import re
from pathlib import Path

KOTLIN_MAP = re.compile(r"internal val MEASURED_VARIANT_FOLD: Map<Char, String> = mapOf\((.*?)\n    \)",
                        re.S)
PAIR = re.compile(r"'(.)' to \"(.+?)\"")
CITATION = re.compile(r"^  (.) -> (.)\t(.*)$", re.M)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--table", required=True)
    ap.add_argument("--provenance", required=True)
    ap.add_argument("--counts", required=True)
    ap.add_argument("--kotlin", required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    table = {}
    for line in Path(args.table).read_text(encoding="utf-8").splitlines():
        if line.strip():
            v, c = line.split("\t")
            table.setdefault(v, set()).add(c)

    prov = Path(args.provenance).read_text(encoding="utf-8")
    jm_pairs = {(v, c) for v, c, cite in CITATION.findall(prov) if "JMdict" in cite}

    kotlin = Path(args.kotlin).read_text(encoding="utf-8")
    m = KOTLIN_MAP.search(kotlin)
    if not m:
        raise SystemExit("could not find MEASURED_VARIANT_FOLD in the Kotlin file")
    existing = [(v, c) for v, c in PAIR.findall(m.group(1))]
    print(f"existing measured pairs: {len(existing)}")

    counts = json.loads(Path(args.counts).read_text(encoding="utf-8"))
    cycles = {v for v, cs in table.items() if v in {c for s in cs for c in s} and len(table) and
              any(table.get(c, set()) == {v} for c in cs)}

    def table_terminal(c):
        """Follow the table's single-candidate steps to the form a dictionary indexes.

        Multi-candidate steps are left alone: which candidate wins there is a corpus
        measurement the shipped fold already encodes (冫 -> 氷, 葢 -> 蓋), and re-deriving it
        here would silently change shipped behaviour.
        """
        seen, cur = set(), c
        while True:
            if cur in seen:
                return None
            seen.add(cur)
            cands = table.get(cur)
            if not cands or len(cands) != 1:
                return cur
            cur = next(iter(cands))

    # Resolve chains *within the fold itself* first: an entry whose canonical is also a key
    # is not idempotent (the shipped 冩 -> 寫 -> 写 was one), so follow the fold's own chain
    # to its end. Cycles are dropped rather than guessed at.
    fold_map = dict(existing)
    existing_resolved = []
    for v, c in existing:
        seen, cur = {v}, c
        while cur in fold_map:
            if cur in seen:
                print(f"  dropping cyclic pair {v} -> {c}")
                cur = None
                break
            seen.add(cur)
            cur = fold_map[cur]
        if cur and cur != c:
            print(f"  fold chain resolved: {v} -> {c} -> {cur}")
        if cur:
            existing_resolved.append((v, cur))
    added, dropped_dir, dropped_cycle = [], [], []
    for v, c in sorted(jm_pairs):
        if v in cycles or c in cycles:
            dropped_cycle.append((v, c))
            continue
        if counts.get(c, 0) < counts.get(v, 0):
            dropped_dir.append((v, c))
            continue
        added.append((v, c))
    print(f"JMdict-half pairs: {len(jm_pairs)}; added {len(added)}, "
          f"dropped {len(dropped_dir)} by direction, {len(dropped_cycle)} by cycle")
    for v, c in dropped_dir:
        print(f"  direction dropped: {v}->{c}  {counts.get(v, 0):,}/{counts.get(c, 0):,}")

    merged = sorted(set(existing_resolved) | set(added))
    keys = {v for v, _ in merged}
    bad = [(v, c) for v, c in merged if c in keys]
    if bad:
        raise SystemExit(f"fold would not be idempotent: {bad}")
    # Every emitted pair must be traceable to the table. Membership is per (variant,
    # canonical) CANDIDATE SET: Unihan gives several variants more than one candidate and
    # the table keeps all of them, so a single-canonical lookup would report false absences.
    missing_added = [(v, c) for v, c in added if c not in table.get(v, set())]
    if missing_added:
        raise SystemExit(f"added fold pairs absent from the table: {missing_added}")
    pre_table = [(v, c) for v, c in existing_resolved if c not in table.get(v, set())]
    if pre_table:
        for v, c in pre_table:
            print(f"  measured pair not in the table (kept): {v} -> {c}")
    print(f"measured pairs not in the table (pre-existing, kept): {len(pre_table)}")

    body = []
    row = []
    for v, c in merged:
        row.append(f"'{v}' to \"{c}\"")
        if len(row) == 4:
            body.append("        " + ", ".join(row) + ",")
            row = []
    if row:
        body.append("        " + ", ".join(row) + ",")
    new_map = ("internal val MEASURED_VARIANT_FOLD: Map<Char, String> = mapOf(\n"
               + "\n".join(body) + "\n\n    )")

    print(f"merged fold: {len(merged)} pairs ({len(added)} new)")
    if args.dry_run:
        print(new_map)
        return
    Path(args.kotlin).write_text(kotlin[:m.start()] + new_map + kotlin[m.end():],
                                 encoding="utf-8")
    print(f"rewrote MEASURED_VARIANT_FOLD in {args.kotlin}")


if __name__ == "__main__":
    main()
