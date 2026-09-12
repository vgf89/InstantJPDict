#!/usr/bin/env python3
"""Build a Japanese character n-gram language model for the OCR rescorer (#44).

WHY this exists
---------------
The correction layer needs a text prior to (a) rank the component-filtered
candidates a dropped or substituted rare kanji leaves behind and (b) give the
rescorer an opinion that can overrule a *confidently* wrong recogniser. The
measured sizing answer (skill: "LM asset sizing: measure the plateau, then spend
on corpus") is that accuracy is **flat from ~7 MB to 40 MB**, so the shipped
model should be the smallest point on the plateau:

    order 4, prune the highest order at count >= 5, Aozora Bunko
    => ~1.44M entries, ~7.2 MB packed (5 B/entry), ~17 MB as a UTF-8 text table
       (measured on the first 5,000,000 chars of the mirror)

This tool builds exactly that table deterministically from the documented
public corpora, prints the entry/size numbers, and can reproduce the whole
(order x prune x corpus) accuracy table on a rendered bench.

This file is the deliverable. **No built asset is committed**: the model is
conditional on a later measurement, so what ships is the generator, the file
format and the `--measure` mode. When the asset is eventually built, its
`PROVENANCE.txt` (written beside the model, see `--out`) must carry the Aozora
public-domain statement and the ja-Wikipedia CC BY-SA 4.0 attribution.

FILE FORMAT (deterministic; this is the contract)
-------------------------------------------------
    one line per n-gram, LF endings, UTF-8, whole table sorted:

        <n-gram><TAB><count>

    * the first field is the n-gram written as its literal characters. For a
      next-character lookup the n-gram's leading n-1 characters are the
      **context** and its final character is the candidate, so a line is
      literally `context<TAB>count` once you know which character is being
      predicted.
    * orders 1 .. --order are all present; a reader reconstructs
      P(c | context) = count(n-gram) / count(context) with stupid backoff.
    * the **highest order only** is pruned at `--min-count` (KenLM's typical
      use). Shorter orders keep every observed n-gram, because a pruned
      highest order cannot be scored without its context counts. Lower-order
      counts are projections of the *unpruned* highest order.
    * sort order is (order, then codepoint of the n-gram), a total order, so
      two runs on the same input are byte-identical (compare the printed
      SHA-256).
    * TAB, CR and LF are stripped from the corpus before counting: they cannot
      be represented in a TAB-delimited line-oriented format.

CORPUS HANDLING
---------------
Aozora Bunko (public domain) — HuggingFace mirror
`globis-university/aozorabunko-clean`, a single ~240 MB gzip'd JSONL:

    https://huggingface.co/datasets/globis-university/aozorabunko-clean/resolve/main/aozorabunko-dedupe-clean.jsonl.gz

One JSON object per line with a `text` field. The stream is ordered, so "the
first N million characters" is taken by streaming the gzip (never expanding it
to disk) and concatenating `text` until N characters have been accumulated.
`--aozora-text FILE` reads a pre-extracted plain-text file instead (what the
measurement samples at /tmp/aozora_sample.txt are); `--aozora-jsonl FILE|URL`
points at a local copy of the mirror.

ja Wikipedia (CC BY-SA 4.0) — optional, only if the error classes move to
encyclopedia prose (they did not: Wikipedia added nothing for the tested
classes). Two documented traps:

  1. `dumps.wikimedia.org` returns **HTTP 403** to Python's default
     user-agent. Send a descriptive User-Agent that includes a contact URL.
     A **bounded range request** is usable: the bz2 stream is ordered (page
     ids are chronological), so the first N hundred MB is a fine arbitrary
     sample and truncation yields everything before it intact.
  2. **Do not parse the dump line by line.** A `<text`/`</text>` toggle over
     `readline()` parsed 84,454 pages and yielded **0 characters**. Read large
     decompressed chunks and run
     `re.finditer(r"<text[^>]*>(.*?)</text>", buf, re.S)` over a rolling
     buffer, keeping ~1 MB of tail so matches that straddle a chunk boundary
     are not lost. The dump **HTML-escapes its own markup** (`&lt;ul&gt;`,
     `&quot;`), so `html.unescape` must run *before* stripping tags or the
     corpus is littered with entity names.

`--wiki-text FILE` reads a pre-extracted plain-text sample;
`--wiki-dump FILE|URL` reads a local `.xml.bz2` or fetches the live dump.

MEASURE MODE
------------
`--bench DIR|FILE [DIR|FILE ...]` reproduces the size/quality table: for every
(order, prune, corpus) configuration the component candidate pool of each
rendered OOV case is ranked with the freshly built n-gram and the truth's
rank-1/top-3 and pool coverage are reported. The ranking reimplements the
skill's `scripts/oov_m3_lm.py`:

  * both modes score with the order-1..order **forward windows** that contain
    the position, substituting the candidate into the bench record's own `text`
    field (`row["text"]`, not the OCR `pred`), averaged as a log-prob;
  * substitutions take their candidate pool from the components of the
    character the head actually emitted (`emitted_as`), ordered by IDF mass
    shared with it;
  * deletions take it from the components the head's top-K kanji at the gap
    (`topk_kanji`) agree on (majority vote, agreement fraction
    `ceil(k/2)`), requiring **all** agreed components, and the ordering key
    there is degenerate (see `build_pool`), so deletion ranks are cap-limited;
  * a candidate pool is capped at `POOL_CAP` before ranking. Pool coverage
    (truth in the full pool, truth in the capped pool, and how many pools the
    cap truncates) is printed once above the table — it is config-independent,
    so it is not repeated per cell — because a rank without its pool coverage
    measures the cap, not the model.

With no `--bench`, the tool prints how to make one
(`oov_render.py` + `oov_rasterise.py` + `oov_bench_measure.py`) and exits 0.

Determinism note: set iteration order is not stable across runs (PYTHONHASHSEED
randomises `str` hashing), so the tool never lets a set decide an order or a
truncation: candidate pools are put in a **total** order (`(-key, char)`) before
any `POOL_CAP` truncation; component lists are sorted before they are counted,
so both `Counter` insertion order and the majority-vote fallback are fixed; and
IDF sums iterate sorted components because float addition is not associative.
The build and the whole `--measure` table are therefore reproducible across
processes and seed values. `--measure` prints full-pool and capped-pool coverage
plus how many pools the cap truncates, because a rank without its pool coverage
measures the cap, not the model.

CAP-LIMITED DELETION RANKS (read this before quoting a del number): the
deletion ordering key is by construction degenerate — the pool is *defined* as
"kanji carrying all of the agreed components", so every member shares the whole
of that set and every key is exactly -1.0. Where the agreed set is a single
generic component (common on horizontal renders and wherever the head's top-K
collapses) the pool runs to hundreds and the 200-cap then truncates an all-tied
list. Those del ranks are cap artefacts; the coverage line and the cap-hit count
are printed to make that legible, and the honest statement is "rank within the
capped pool", never a bare rank.

USAGE
-----
    # the default plateau, print numbers, write nothing
    python3 tools/build_char_lm.py --aozora-text /tmp/aozora_sample.txt

    # write the asset + its provenance (do NOT commit the asset)
    python3 tools/build_char_lm.py --aozora-chars 5000000 \
        --out /tmp/char_lm_order4_min5.tsv

    # reproduce the measured (order x prune x corpus) table
    python3 tools/build_char_lm.py --measure --corpus both \
        --bench /tmp/oov_bench2/results_epub2.jsonl /tmp/oov_az \
        --wiki-text /tmp/wiki_ja40m.txt
"""
import argparse
import bz2
import codecs
import collections
import gzip
import hashlib
import html
import json
import math
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

AOZORA_URL = ("https://huggingface.co/datasets/globis-university/"
              "aozorabunko-clean/resolve/main/aozorabunko-dedupe-clean.jsonl.gz")
WIKI_URL = ("https://dumps.wikimedia.org/jawiki/latest/"
            "jawiki-latest-pages-articles.xml.bz2")
# Wikipedia 403s Python's default agent; a descriptive UA with a contact URL is
# the documented workaround.
USER_AGENT = ("InstantJPDict-char-lm-builder/1.0 "
              "(+https://github.com/holopengin/InstantJPDict; OCR LM research)")
AOZORA_LICENSE = "Public domain (Aozora Bunko)"
WIKI_LICENSE = ("CC BY-SA 4.0 — attribution to Wikipedia contributors is "
                "required and must travel in the PROVENANCE sidecar")
AOZORA_ATTRIB = ("Aozora Bunko works are public domain or free to redistribute "
                 "(https://www.aozora.gr.jp/guide/kijyunn.html)")
WIKI_ATTRIB = ("Contains information from ja.wikipedia.org, which is made "
               "available under CC BY-SA 4.0 "
               "(https://creativecommons.org/licenses/by-sa/4.0/)")

# Packed-asset estimate used consistently in the skill's sizing table. The
# serialised UTF-8 table is larger; both are printed.
BYTES_PER_ENTRY = 5
# Stupid backoff penalty, matching ngram_probe.py / oov_m3_lm.py so the
# measured table is reproducible. Absolute PPL is not a KenLM number; only the
# ordering across configurations is the signal.
ALPHA = 0.4
# Candidate-pool cap from oov_m3_lm.py; report pool coverage beside every rank,
# because a rank without its coverage measures the cap, not the model.
POOL_CAP = 200
# The measured grid in the skill's table.
ORDER_GRID = (3, 4)
MIN_GRID = (1, 2, 5)

STRIP = str.maketrans({"\t": "", "\n": "", "\r": ""})
PAGE_RE = re.compile(r"<text[^>]*>(.*?)</text>", re.S)
TAG_RE = re.compile(r"<[^>]+>")
CHUNK = 8 << 20          # ~8 MB decompressed chunks (the line-parser trap)
TAIL = 1 << 20           # ~1 MB rolling buffer tail for straddling matches


def clean(text):
    """Drop characters the flat TAB/LF format cannot represent."""
    return text.translate(STRIP)


def _read_clean_head(fh, chars):
    """First `chars` *cleaned* characters of a text file, streamed."""
    parts, got = [], 0
    while got < chars:
        chunk = fh.read(1 << 20)
        if not chunk:
            break
        c = clean(chunk)
        parts.append(c)
        got += len(c)
    return "".join(parts)[:chars]


# --------------------------------------------------------------------------- #
# corpus readers
# --------------------------------------------------------------------------- #
def _open_url(url, ua=USER_AGENT):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    return urllib.request.urlopen(req, timeout=180)


def _iter_aozora(fh, limit_chars):
    """Yield `text` per gzip'd jsonl line until `limit_chars` accumulated."""
    total = 0
    for raw in fh:
        if not raw.strip():
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            continue
        text = clean(obj.get("text") or "")
        if not text:
            continue
        total += len(text)
        yield text
        if total >= limit_chars:
            return


def read_aozora(chars, text_path=None, jsonl=None):
    """First `chars` characters of the Aozora corpus."""
    if text_path:
        with open(text_path, encoding="utf-8", errors="replace") as f:
            return _read_clean_head(f, chars)
    src = jsonl or AOZORA_URL
    local = Path(src).exists() if not src.startswith(("http://", "https://")) else False
    if local:
        fh = gzip.open(src, "rb")
    else:
        fh = gzip.GzipFile(fileobj=_open_url(src))
    try:
        return clean("".join(_iter_aozora(fh, chars)))[:chars]
    finally:
        fh.close()


def _decompressed_chunks(raw, target=CHUNK, read=1 << 20):
    """Decompress a (possibly truncated) bz2 stream, ~`target` bytes at a time.

    A bounded range request / truncated file simply ends the stream early; all
    bytes before the truncation are intact (EOFError is the documented tail).
    """
    dec = bz2.BZ2Decompressor()
    buf = bytearray()
    while True:
        data = raw.read(read)
        if not data:
            break
        try:
            out = dec.decompress(data)
        except (EOFError, OSError):
            break
        buf += out
        if len(buf) >= target:
            yield bytes(buf)
            buf.clear()
    if buf:
        yield bytes(buf)


def _wiki_pages(bytes_chunks):
    """`<text>` bodies from decompressed chunks over a rolling text buffer."""
    dec = codecs.getincrementaldecoder("utf-8")("replace")
    tail = ""
    for bchunk in bytes_chunks:
        buf = tail + dec.decode(bchunk)
        last = 0
        for m in PAGE_RE.finditer(buf):
            yield m.group(1)
            last = m.end()
        leftover = buf[last:]
        tail = leftover[-TAIL:] if len(leftover) > TAIL else leftover


def _wikitext_plain(page):
    """Unescape THEN strip tags: the dump HTML-escapes its own markup."""
    return TAG_RE.sub("", html.unescape(page))


def read_wiki(chars, text_path=None, dump=None):
    """First `chars` characters of a ja Wikipedia dump prefix."""
    if text_path:
        with open(text_path, encoding="utf-8", errors="replace") as f:
            return _read_clean_head(f, chars)
    src = dump or WIKI_URL
    local = Path(src).exists() if not src.startswith(("http://", "https://")) else False
    raw = open(src, "rb") if local else _open_url(src)
    parts, got = [], 0
    try:
        for page in _wiki_pages(_decompressed_chunks(raw)):
            plain = clean(_wikitext_plain(page))
            if not plain:
                continue
            parts.append(plain)
            got += len(plain)
            if got >= chars:
                break
    finally:
        raw.close()
    return "".join(parts)[:chars]


def load_corpus(args):
    """(training text, provenance descriptors) for the requested --corpus."""
    want = {"aozora": ["aozora"], "wiki": ["wiki"],
            "both": ["aozora", "wiki"]}[args.corpus]
    parts, desc = [], []
    if "aozora" in want:
        if args.aozora_text:
            src, kind = args.aozora_text, "plain-text sample"
        else:
            src, kind = (args.aozora_jsonl or AOZORA_URL), "HF mirror jsonl.gz"
        text = read_aozora(args.aozora_chars, args.aozora_text, args.aozora_jsonl)
        parts.append(text)
        desc.append({"name": "Aozora Bunko", "source": src, "form": kind,
                     "license": AOZORA_LICENSE, "attribution": AOZORA_ATTRIB,
                     "chars": len(text)})
    if "wiki" in want:
        if args.wiki_text:
            src, kind = args.wiki_text, "plain-text sample"
        else:
            src, kind = (args.wiki_dump or WIKI_URL), "xml.bz2 dump prefix"
        try:
            text = read_wiki(args.wiki_chars, args.wiki_text, args.wiki_dump)
        except urllib.error.HTTPError as e:
            sys.exit(f"wiki fetch failed ({e.code}); see the docstring traps "
                     f"(descriptive User-Agent) or pass --wiki-text FILE")
        parts.append(text)
        desc.append({"name": "ja Wikipedia", "source": src, "form": kind,
                     "license": WIKI_LICENSE, "attribution": WIKI_ATTRIB,
                     "chars": len(text)})
    return "".join(parts), desc


# --------------------------------------------------------------------------- #
# counting, pruning, serialising
# --------------------------------------------------------------------------- #
def count_ngrams(text, order):
    """counts[o] = dict n-gram -> count; shorter orders by projection."""
    counts = {order: collections.Counter(
        text[i:i + order] for i in range(len(text) - order + 1))}
    for o in range(order - 1, 0, -1):
        c = collections.Counter()
        for gram, n in counts[o + 1].items():
            c[gram[:-1]] += n
        counts[o] = c
    return counts


def prune(counts, order, min_count, max_entries):
    """Highest order pruned at `min_count`, then capped at `max_entries`.

    Only the highest order is touched (KenLM's typical use). The optional
    --max-entries ceiling drops the *lowest-count* highest-order n-grams,
    ties broken by the output total order, and never removes a context row.
    """
    levels = {o: counts[o] for o in range(1, order)}
    top = {g: v for g, v in counts[order].items() if v >= min_count}
    if max_entries is not None and len(top):
        lower = sum(len(levels[o]) for o in range(1, order))
        allowed = max_entries - lower
        if allowed < 0:
            sys.exit(f"--max-entries {max_entries:,} is below the {lower:,} "
                     f"context rows an order-{order} model must keep; lower "
                     f"--order or raise the cap")
        if len(top) > allowed:
            keep = sorted(top.items(), key=lambda kv: (-kv[1], kv[0]))[:allowed]
            top = dict(keep)
            print(f"  --max-entries {max_entries:,}: kept {allowed:,} of the "
                  f"highest-order n-grams (lower orders never capped)")
    levels[order] = top
    return levels


def serialise(levels, order):
    """Byte-deterministic payload: sorted (order, codepoint) n-gram<TAB>count."""
    lines = []
    for o in range(1, order + 1):
        lines.extend(f"{g}\t{levels[o][g]}\n" for g in sorted(levels[o]))
    return "".join(lines).encode("utf-8")


def entries_of(levels, order):
    return sum(len(levels[o]) for o in range(1, order + 1))


# --------------------------------------------------------------------------- #
# scoring (reimplemented from the skill's ngram_probe.py / oov_m3_lm.py)
# --------------------------------------------------------------------------- #
def logp(window, levels, uni_total):
    penalty = 0.0
    for o in range(len(window), 0, -1):
        gram = window[len(window) - o:]
        count = levels[o-1].get(gram, 0)
        if count:
            if o == 1:
                prob = count / uni_total
            else:
                ctx = levels[o-2].get(gram[:-1], 0)
                if not ctx:
                    penalty += math.log(ALPHA)
                    continue
                prob = count / ctx
            return penalty + math.log(prob)
        penalty += math.log(ALPHA)
    return penalty + math.log(1.0 / uni_total)


def local_score(text, i, cand, levels, uni_total, order):
    """Average log-prob over the order-1 windows that contain the position.

    Averaging over a wider window dilutes the margin to ~0 (the surrounding
    text is identical for every candidate); the order-1 windows are the ones
    the candidate actually changes.
    """
    s = text[:i] + cand + text[i + 1:]
    tot, n = 0.0, 0
    for end in range(i, min(len(s), i + order)):
        w = s[end - order + 1:end + 1]
        if len(w) == order:
            tot += logp(w, levels, uni_total)
            n += 1
    return tot / n if n else -1e9


# --------------------------------------------------------------------------- #
# bench: component candidate pools + ranking
# --------------------------------------------------------------------------- #
def kanji(c):
    o = ord(c)
    return (0x3400 <= o <= 0x4DBF) or (0x4E00 <= o <= 0x9FFF) or (0xF900 <= o <= 0xFAFF)


def load_components(path):
    table = {}
    for line in open(path, encoding="utf-8"):
        if ":" in line:
            k, comps = line.rstrip("\n").split(":", 1)
            table[k] = comps.split()
    return table


def inverted_index(table):
    inverted = collections.defaultdict(set)
    for k, comps in table.items():
        if kanji(k):
            for c in comps:
                inverted[c].add(k)
    return inverted


def _idf(inverted, n_kanji, comps):
    # sorted(): float addition is not associative, so an unordered set would
    # give last-bit differences under PYTHONHASHSEED and reorder near-ties.
    return sum(math.log(n_kanji / max(len(inverted[c]), 1))
               for c in sorted(comps))


def build_pool(kind, row, table, inverted, n_kanji):
    """Candidate pool for one bench row: (truth, ordered_pool, need_txt).

    `ordered_pool` is the **full** pool in the total order described below; the
    caller truncates to POOL_CAP, never this function, so the cap always falls
    on a well-defined boundary. `need_txt` is the deletion rule's required
    component set ("" for substitutions).

    Substitution (`status == "substituted"`): the reference character is the
    record's `emitted_as` (what the head actually wrote). Its krad components
    that appear anywhere in the table are the evidence; the pool is every
    *other* kanji sharing at least one of them (**union** over shared
    components — deliberately looser than the deletion rule). Ordered by IDF
    mass shared with `emitted_as`, normalised by the emitted character's total
    IDF mass.

    Deletion (`status == "deleted"`): the evidence is the head's top-K kanji at
    the gap, from the record's `topk_kanji` string (top-10 non-blank decoded
    candidates, filtered to kanji, first 5) — NOT the `configs.*.components`
    field. A component is *required* when carried by at least
    `ceil(len(topk_kanji) / 2)` of those kanji (majority voting, never a strict
    intersection: one outlier such as 眩 kills the filter); if that selects
    nothing it falls back to the single most common component (char tie-break).
    The pool is every kanji carrying **ALL** required components, i.e. an AND
    over the majority-agreed set (`need <= comps(k)`). When the vote collapses
    to one generic component (a horizontal render's symbol junk, or a single
    surviving `topk_kanji`) this AND degenerates into "has that one component"
    and the pool is hundreds strong — which is a property of the bench row, not
    of a looser matching rule.

    Ordering before the cap (both kinds): key = `(-shared_idf_ratio, char)`, a
    **total** order, applied before any truncation, so no result depends on set
    iteration order. NOTE for deletions the ratio is **degenerate**: the pool is
    defined as `need <= comps(k)`, so every member shares all of `need` and
    every key is exactly -1.0; the cap then truncates an all-tied list by the
    codepoint tie-break alone. `--measure` prints full-vs-capped coverage and
    the cap-hit count so that distortion is visible.
    """
    if kind == "sub":
        emitted = row.get("emitted_as", "")
        comps = sorted(c for c in table.get(emitted, []) if c in inverted)
        if not comps:
            return None
        denom = _idf(inverted, n_kanji, comps)
        pool = {k for k in set().union(*(inverted[c] for c in comps)) if k != emitted}
        cands = sorted(
            pool,
            key=lambda k: (-_idf(inverted, n_kanji,
                                 set(table.get(k, [])).intersection(comps)) / denom, k))
        return row["char"], cands, ""
    top = [c for c in row.get("topk_kanji", "") if kanji(c)]
    if not top:
        return None
    cnt = collections.Counter()
    for c in top:
        # sorted(): Counter insertion order drives most_common's tie-break, and
        # that must not depend on set iteration order (PYTHONHASHSEED).
        for comp in sorted(set(table.get(c, []))):
            cnt[comp] += 1
    need = {comp for comp, n in cnt.items() if n >= math.ceil(len(top) / 2)}
    if not need:
        need = {min(cnt, key=lambda c: (-cnt[c], c))}   # strongest, char tie-break
    pool = {k for k in set().union(*(inverted[c] for c in sorted(need) if c in inverted))
            if need <= set(table.get(k, []))}
    if not pool:
        return None
    d = _idf(inverted, n_kanji, need)
    cands = sorted(
        pool,
        key=lambda k: (-_idf(inverted, n_kanji,
                             set(table.get(k, [])).intersection(need)) / d, k))
    return row["char"], cands, "".join(sorted(need))


def load_bench(paths):
    rows = []
    for spec in paths:
        p = Path(spec)
        if p.is_file():
            files = [p]
        elif p.is_dir():
            files = sorted(p.rglob("results*.jsonl"))
        else:
            files = sorted(Path().glob(spec))   # a glob pattern
        if not files:
            print(f"  warning: no results*.jsonl for {spec}", file=sys.stderr)
        for f in files:
            print(f"  bench file: {f}", flush=True)
            for line in open(f, encoding="utf-8"):
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def measure(args):
    """Reproduce the (order x prune x corpus) size/quality table."""
    if not args.bench:
        print("--measure needs a rendered bench directory.\n\n"
              "Regenerate one with the skill's scripts:\n"
              "  1. scripts/oov_render.py     # one line per PDF page, "
              "vertical + horizontal\n"
              "  2. scripts/oov_rasterise.py  # PDF -> per-case crops + .json "
              "(asserts the page text)\n"
              "  3. scripts/oov_bench_measure.py --bench-dir DIR \\\n"
              "         --out DIR/results.jsonl   # decode + align + candidate "
              "pools\n"
              "then re-run:  python3 tools/build_char_lm.py --measure "
              "--bench DIR")
        return 0

    comp_path = args.components or (Path(__file__).resolve().parents[1]
                                    / "app/src/main/assets/components/krad_components.txt")
    if not Path(comp_path).exists():
        sys.exit(f"component table not found: {comp_path} (build it with "
                 f"tools/build_component_table.py or pass --components)")
    table = load_components(comp_path)
    inverted = inverted_index(table)
    n_kanji = len([k for k in table if kanji(k)])

    rows = load_bench(args.bench)
    # The candidate pool depends only on the bench + component table, so its
    # coverage is config-independent and is reported once, not per cell.
    cases = {"sub": [], "del": []}
    stats = {k: {"n": 0, "full": 0, "capped_hit": 0, "over": 0, "sizes": []}
             for k in ("sub", "del")}
    for row in rows:
        status = row.get("status")
        if status == "substituted":
            kind = "sub"
        elif status == "deleted" and row.get("topk_kanji"):
            kind = "del"
        else:
            continue
        built = build_pool(kind, row, table, inverted, n_kanji)
        if not built:
            continue
        truth, full, need_txt = built
        capped = full[:POOL_CAP]          # truncate only after a total order
        st = stats[kind]
        st["n"] += 1
        st["full"] += truth in full
        st["capped_hit"] += truth in capped
        st["over"] += len(full) > POOL_CAP
        st["sizes"].append(len(full))
        if kind == "del":
            st.setdefault("single", 0)
            st["single"] += len(need_txt) == 1
        cases[kind].append((truth, capped, row.get("text", ""),
                            int(row.get("index", 0)), row.get("mode", "")))
    for kind in ("sub", "del"):
        st = stats[kind]
        if not st["n"]:
            continue
        sizes = sorted(st["sizes"])
        print(f"bench {kind}: {st['n']} cases; truth in the FULL pool "
              f"{st['full']}/{st['n']}, in the {POOL_CAP}-capped pool "
              f"{st['capped_hit']}/{st['n']}; pool exceeds the cap in "
              f"{st['over']}/{st['n']} (median full pool "
              f"{sizes[len(sizes) // 2]})", flush=True)
        if kind == "del":
            print(f"  required-component set: {st['single']}/{st['n']} cases "
                  f"need ONLY ONE component (vote collapsed / fallback), which "
                  f"is what makes those pools hundreds strong", flush=True)
            if st["over"]:
                print("  note: the deletion ordering key is degenerate (every "
                      "candidate shares all required components), so the cap "
                      "truncates an all-tied list — read del ranks as "
                      "cap-limited", flush=True)
    if not any(cases.values()):
        sys.exit("no rankable cases: does the bench hold substituted/deleted rows?")

    # Corpora for the grid. --measure never triggers an implicit 4.7 GB dump
    # fetch: the Wikipedia rows are evaluated only when a local source is named.
    aoz = read_aozora(args.aozora_chars, args.aozora_text, args.aozora_jsonl)
    corpora = [("aozora", aoz)]
    if args.corpus in ("wiki", "both"):
        if args.wiki_text or args.wiki_dump:
            wiki = read_wiki(args.wiki_chars, args.wiki_text, args.wiki_dump)
            corpora = ([("wiki", wiki)] if args.corpus == "wiki"
                       else [("aozora", aoz), ("aozora+wiki", aoz + wiki)])
        else:
            print("  note: no --wiki-text/--wiki-dump, so the Wikipedia rows are "
                  "skipped (the dump is never fetched implicitly)", flush=True)

    print(f"\n{'corpus':<12} {'order':>5} {'prune':>6} {'entries':>12} "
          f"{'~packed':>9} {'sub r1':>8} {'sub t3':>8} {'del r1':>8} {'del t3':>8}")
    for label, train in corpora:
        counts = count_ngrams(train, max(ORDER_GRID))
        for order in ORDER_GRID:
            for min_count in MIN_GRID:
                levels = prune(counts, order, min_count, None)
                levels = [levels[o] for o in range(1, order + 1)]
                uni_total = sum(levels[0].values())
                entries = sum(len(l) for l in levels)
                cells = []
                for kind in ("sub", "del"):
                    ranks = []
                    for truth, cands, text, i, _mode in cases[kind]:
                        if not cands:
                            continue
                        ranked = sorted(cands, key=lambda c: -local_score(
                            text, i, c, levels, uni_total, order))
                        ranks.append(ranked.index(truth) + 1 if truth in ranked else -1)
                    cells.append((sum(1 for x in ranks if x == 1),
                                  sum(1 for x in ranks if 1 <= x <= 3), len(ranks)))
                print(f"{label:<12} {order:>5} {'>=' + str(min_count):>6} "
                      f"{entries:>12,} {entries * BYTES_PER_ENTRY / 1e6:>7.1f}MB "
                      f"{cells[0][0]:>4}/{cells[0][2]:<3} {cells[0][1]:>4}/{cells[0][2]:<3} "
                      f"{cells[1][0]:>4}/{cells[1][2]:<3} {cells[1][1]:>4}/{cells[1][2]:<3}",
                      flush=True)
                del levels
        del counts
    return 0


# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--order", type=int, default=4,
                    help="highest n-gram order (default 4)")
    ap.add_argument("--min-count", type=int, default=5,
                    help="prune the highest order below this count (default 5)")
    ap.add_argument("--max-entries", type=int, default=None,
                    help="ceiling on total table entries (drops the rarest "
                         "highest-order n-grams)")
    ap.add_argument("--corpus", choices=["aozora", "wiki", "both"],
                    default="aozora", help="training corpus (default aozora)")
    ap.add_argument("--aozora-chars", type=int, default=5_000_000,
                    help="Aozora characters to take (default 5,000,000)")
    ap.add_argument("--wiki-chars", type=int, default=10_000_000,
                    help="Wikipedia characters to take (default 10,000,000)")
    ap.add_argument("--aozora-text", default=None, help="local plain-text sample")
    ap.add_argument("--aozora-jsonl", default=None, help="local mirror .jsonl.gz")
    ap.add_argument("--wiki-text", default=None, help="local plain-text sample")
    ap.add_argument("--wiki-dump", default=None, help="local .xml.bz2 or URL")
    ap.add_argument("--out", default=None,
                    help="write the table here (no asset is committed); "
                         "writes <out>.provenance.txt too")
    ap.add_argument("--measure", action="store_true",
                    help="reproduce the size/quality table on a rendered bench")
    ap.add_argument("--bench", nargs="+", default=None,
                    help="rendered bench dir(s) holding results*.jsonl")
    ap.add_argument("--components", default=None,
                    help="krad component table (default: the vendored asset)")
    args = ap.parse_args()

    if args.measure:
        return measure(args)

    text, desc = load_corpus(args)
    print(f"corpus: {'+'.join(d['name'] for d in desc)} "
          f"({len(text):,} chars)")
    counts = count_ngrams(text, args.order)
    levels = prune(counts, args.order, args.min_count, args.max_entries)
    entries = entries_of(levels, args.order)
    payload = serialise(levels, args.order)
    sha = hashlib.sha256(payload).hexdigest()

    per_order = ", ".join(
        f"{o}-gram {len(levels[o]):,}" for o in range(1, args.order + 1))
    print(f"order {args.order}, min-count >= {args.min_count}: "
          f"{entries:,} entries  ({per_order})")
    print(f"estimated size: {entries * BYTES_PER_ENTRY / 1e6:.2f} MB packed "
          f"({BYTES_PER_ENTRY} B/entry, the skill's sizing convention), "
          f"{len(payload) / 1e6:.2f} MB as UTF-8 text")
    print(f"table SHA-256: {sha}")

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(payload)
        prov = [
            "Character n-gram language model — generated, do not edit by hand.",
            "",
            f"Entries    : {entries:,}",
            f"Order      : {args.order}",
            f"Min count  : >= {args.min_count} (highest order only)",
            f"File       : {out.name}",
            f"Size       : {len(payload)} bytes ({entries * BYTES_PER_ENTRY / 1e6:.2f} MB packed estimate)",
            f"SHA-256    : {sha}",
            "",
            "Format     : one `<n-gram><TAB><count>` line per n-gram, LF endings,",
            "             sorted by (order, codepoint). See tools/build_char_lm.py.",
            "",
        ]
        for d in desc:
            prov += [f"Source     : {d['name']} — {d['source']}",
                     f"  form     : {d['form']}",
                     f"  chars    : {d['chars']:,}",
                     f"  license  : {d['license']}",
                     f"  attrib   : {d['attribution']}",
                     ""]
        prov += [
            "Generated by tools/build_char_lm.py — rerun rather than editing.",
        ]
        if any(d["name"].startswith("ja Wikipedia") for d in desc):
            prov.append("Attribution above must ship with the asset "
                        "(ja Wikipedia CC BY-SA 4.0).")
        prov.append("")
        (out.parent / f"{out.name}.provenance.txt").write_text(
            "\n".join(prov), encoding="utf-8")
        print(f"wrote {out} ({len(payload):,} bytes)")
        print(f"wrote {out.parent / (out.name + '.provenance.txt')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
