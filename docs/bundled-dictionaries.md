# Bundled dictionaries

Dictionaries vendored into the APK so a feature works with no network and no
file picker. Each one ships with a `PROVENANCE.txt` beside it recording the
source, revision, license and SHA-256 of the artifact.

Everything here is a generated file. Do not edit by hand — regenerate from the
script named below and commit the result.

## Pitch accents — `pitch/kanjium_pitch_accents.zip`

Yomitan term-meta-bank v3 dictionary of Tokyo pitch accents, in-app behind the
**Show pitch accent in dictionary popup** checkbox (see #43). Installed with the
**Install Bundled Pitch Dictionary** button, which needs no network.

| | |
|---|---|
| Source | [mifunetoshiro/kanjium](https://github.com/mifunetoshiro/kanjium) — `data/source_files/raw/accents.txt` |
| Revision | `685d4d723d6d20bf9beb169103aeac188eb067ad` |
| License | CC BY-SA 4.0 |
| Entries | 124,134 |
| Generator | `tools/build_pitch_dict.py` |

Regenerate:

```sh
curl -L -o /tmp/accents.txt \
  https://raw.githubusercontent.com/mifunetoshiro/kanjium/685d4d723d6d/data/source_files/raw/accents.txt
python3 tools/build_pitch_dict.py /tmp/accents.txt
```

The script writes both the zip and its `PROVENANCE.txt`. Output is
byte-deterministic (sorted keys, fixed separators, fixed zip timestamps), so a
rebuild with the same input produces an identical file — a mismatch means the
input or the script changed, not that the build is flaky.

### Why vendored rather than downloaded

The upstream republishes `accents.txt` extremely rarely — four commits touching
it in about twelve years, most recently 2024-06-16 — and publishes no releases
or tags, so there is no versioned URL to track. A pinned copy in the APK keeps
the feature fully offline, avoids adding the `INTERNET` permission for it, and
still satisfies CC BY-SA as long as attribution travels with the copy (which is
what `PROVENANCE.txt` and #70 are for).

### Built-in, not user-editable

The pitch dictionary is installed as a **built-in** dictionary
(`dictionary_meta.builtIn`). Built-ins are app data rather than user data:

- it does not appear in **Manage Dictionaries** — nothing to reorder, nothing
  to delete;
- there is no delete path for it;
- the app installs it automatically at startup whenever none is present, so the
  presence check doubles as self-repair after a wiped or corrupted database.

There is no install/reinstall button: `builtIn` is the **completion marker**,
set only after every entry is written, and the startup check looks exactly at
it. So a database wipe, a corrupted row and an import killed part-way all
present as "no built-in dictionary" and are repaired on the next launch. A
plain presence check on the title would have treated a partial import as done,
and a button was only needed to paper over that.

`builtIn` was added in schema version 4 with a hand-written migration. Note that
the database is otherwise configured with `fallbackToDestructiveMigration()`,
which would have silently wiped every user-imported dictionary on upgrade — the
migration exists to prevent exactly that.

### Installation is idempotent

`DictionaryImporter.importBundledAsset` deletes any existing dictionary with the
same title before importing, so re-running it repairs the install rather than
stacking a second copy of all 124k rows.

### A note on the source data

Two quirks of `accents.txt` are handled by the generator, and were real bugs
before they were handled:

- Kana-only words ship an **empty reading column** (the term is the reading) —
  16,116 rows, e.g. `あっさり`, `ああ`. Without resolving these the entries are
  dropped entirely.
- Duplicate `(term, reading)` rows carry **multiple accepted accents** and must
  merge their positions, e.g. `１ ひと 0,2`.

One row (`言論機関 げんろんきかん`) declares position 8 for a 7-mora reading. It
is upstream noise; the renderer clamps it.

## Kanji components — `components/krad_components.txt`

Visual decomposition of each kanji into its components, used to propose
characters the recogniser cannot emit (see #44). One line per kanji,
`呟:亠 口 幺 玄`, sorted by codepoint.

| | |
|---|---|
| Source | [EDRDG KRADFILE](http://ftp.edrdg.org/pub/Nihongo/kradzip.zip) — `kradfile` + `kradfile2` |
| License | EDRDG (CC BY-SA 4.0) |
| Entries | 12,156 kanji |
| Generator | `tools/build_component_table.py` |

Regenerate:

```sh
curl -o /tmp/kradzip.zip http://ftp.edrdg.org/pub/Nihongo/kradzip.zip
python3 tools/build_component_table.py --zip /tmp/kradzip.zip \
  --out-dir app/src/main/assets/components
```

Output is byte-deterministic, as with the pitch dictionary. Both files are
merged because their coverage differs (`啦` is in `kradfile2` only); the input
is EUC-JP, not UTF-8.

### Why components, and not a language model

An unemittable character comes back as a **blank**, not a misread: at the `呟`
gap the head's best guess is blank at 0.42 with 咳 0.17 behind it. That blank is
not detectable by probability (recovery is error-neutral at every floor) and not
by ink (92–98% of ordinary inter-character gaps contain ink too).

What the head *does* carry is radical evidence: its top-5 at that gap is
`咳 咬 啦 哮 眩`, and those share 口 and 亠 — both components of `呟`. Filtering
the dictionary's reading-matched candidates by the components the head agrees on
cuts 229 candidates to 7 with `呟` surviving, and the existing text n-gram then
ranks it first. A text n-gram *alone* ranks it 30th of 229, because every
candidate is a `〜く` verb and 「と咲いて」「と働いて」「と呟いて」 are all
ordinary Japanese. The components are the discriminator; the n-gram is only the
tie-break.

## Character n-gram language model — not yet vendored

A character n-gram model trained on public-domain Japanese prose, used by the
correction layer (#44) to rank the component-filtered candidates a dropped or
substituted rare kanji leaves behind, and as the prior that can overrule a
*confidently* wrong recogniser. **No asset is committed yet**: the model is
conditional on a later measurement, so what ships today is the generator, the
format and the `--measure` mode.

| | |
|---|---|
| Source (Aozora) | [globis-university/aozorabunko-clean](https://huggingface.co/datasets/globis-university/aozorabunko-clean) — `aozorabunko-dedupe-clean.jsonl.gz` (~240 MB gzip jsonl, one JSON object per line, `text` field) |
| Source (ja Wikipedia, optional) | `dumps.wikimedia.org/jawiki/latest/jawiki-latest-pages-articles.xml.bz2` |
| License (Aozora) | Public domain (Aozora Bunko) |
| License (ja Wikipedia) | CC BY-SA 4.0 — attribution must travel in the `PROVENANCE.txt` sidecar when the asset is built |
| Generator | `tools/build_char_lm.py` |

Regenerate:

```sh
# default = the measured plateau: order 4, min-count >= 5, first 5M Aozora chars
python3 tools/build_char_lm.py --aozora-chars 5000000 \
  --out build/char_lm_order4_min5.tsv     # writes the table + .provenance.txt

# reproduce the (order x prune x corpus) size/quality table on a rendered bench
python3 tools/build_char_lm.py --measure --corpus both \
  --bench /tmp/oov_bench2/results_epub2.jsonl /tmp/oov_az \
  --wiki-text /tmp/wiki_ja40m.txt
```

Output is byte-deterministic: the same input and knobs give an identical
SHA-256, printed by the tool. Do not edit the table by hand — rerun the tool.

### Format

One `<n-gram><TAB><count>` line per n-gram, LF endings, UTF-8, sorted by
(order, then codepoint). For a next-character lookup the n-gram's leading
`n-1` characters are the **context** and its final character is the candidate,
so the line is literally `context<TAB>count`. Orders 1..`--order` are all
present; a reader reconstructs `P(c | context) = count(n-gram) / count(context)`
with stupid backoff. **Only the highest order is count-pruned** (`--min-count`,
default 5), as in KenLM's typical use; shorter orders keep every observed n-gram
because the highest order cannot be scored without its context counts.

### Sizing — the measured plateau

Accuracy is flat from ~7 MB to 40 MB across the corpus/order/prune grid, so the
shipped model is the smallest model on the plateau: **order 4, min-count ≥ 5,
Aozora** — ~1.44M entries, ~7.2 MB packed (the 5 B/entry convention used in the
sizing table), ~17 MB as UTF-8 text. Adding ja Wikipedia bought nothing for the
tested error classes (rare literary and variant kanji live in novels, not
encyclopedia prose), so the default corpus is Aozora alone.

Two traps are documented in the generator and worth repeating, because both
produced silent garbage before they were handled: a Wikipedia dump returns
**HTTP 403** without a descriptive `User-Agent` carrying a contact URL, and it
must **not** be parsed line by line (a `<text>`/`</text>` toggle over
`readline()` parsed 84,454 pages and yielded 0 characters) — read ~8 MB
decompressed chunks and run the regex over a rolling buffer with a ~1 MB tail,
`html.unescape`-ing **before** stripping tags because the dump escapes its own
markup.

Scoring the same candidates with an n-gram trained on kanji-only subsequences
(to rank on content rather than conjugation) was measured and did **not** help:
rank 3 of 7 against the plain model's 1 of 7. Once the component filter has cut
the field to single digits, conjugation noise stops mattering.

## Kanji variants — `variants/kanji_variants.txt`

Unihan's orthographic variants of one character (`kSemanticVariant` + `kZVariant`),
used to normalise a lookup query onto the form a dictionary keys on (see #44).
One line per pair, `variant<TAB>canonical`, sorted by variant codepoint:

```
囘	回
欝	鬱
壜	罈
```

Consumed by `util/KanjiVariants.kt` (`canonical(ch)`, `obsoleteFormsOf(ch)`) and,
for the measured subset, by `JapaneseUtil.foldLookupVariants`.

| | |
|---|---|
| Source | [Unihan](https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip) — `Unihan_Variants.txt` |
| Revision | Unicode 17.0.0, data of 2025-07-24 (recorded in `PROVENANCE.txt`) |
| License | Unicode License v3 |
| Entries | 593 pairs over 523 distinct variants |
| Generator | `tools/build_kanji_variants.py` |

Regenerate:

```sh
curl -L -o /tmp/Unihan.zip https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
python3 tools/build_kanji_variants.py --zip /tmp/Unihan.zip \
  --vocab app/src/main/assets/PP-OCRv6_small_ncnn/vocab.json \
  --out-dir app/src/main/assets/variants --check 囘:回 欝:鬱
```

Output is byte-deterministic (sorted, LF) apart from the `Retrieved:` date line in
`PROVENANCE.txt`, which is passed in and defaults to today.

### The direction rule

Unihan lists variants in both directions and says nothing about which form a
Japanese dictionary keys on. The surviving direction is decided by the shipped
recogniser's own dictionary:

- **canonical** = the side present in `PP-OCRv6_small_ncnn/vocab.json`;
- **variant** = the side that is not.

A fold exists to map a form the pipeline cannot handle onto one it can, so the
target must be the form the dictionary already knows. Pairs where both sides are
known (nothing to gain) or neither is (the miss just moves) are dropped, as are
supplementary-plane pairs — the API is `Char`-based, one UTF-16 code unit. That
rule is verified in the build: `囘 -> 回` and `欝 -> 鬱` are the asserted pairs.

### Which pairs reach the fold, and why 92 of 593

`JapaneseUtil.foldLookupVariants` carries only pairs whose **variant side actually
occurs in real Japanese text** — otherwise the entry is a guess with no evidence
behind it. Measured over the whole Aozora Bunko corpus as streamed from the HF
clean mirror (16,950 works, 230,196,565 characters, `scripts/variant_count.py`'s
set-then-count pattern):

| variant | occurrences | folds to |
|---|---|---|
| 囘 | 1,493 | 回 |
| 欝 | 1,431 | 鬱 |
| 劒 | 387 | 劍 |
| 慙 | 357 | 慚 |
| 厶 | 305 | 某 |
| 噐 / 輙 | 74 each | 器 / 輒 |
| 齅 / 覉 | 30 each | 嗅 / 羇 |
| … | 1 each (27 variants) | |

**112 of the 593 pairs** qualify, 7,917 occurrences in total. Where Unihan offers
several canonical candidates for one variant (15 of the 112) the fold takes the
form that dominates that same corpus rather than an arbitrary first — `葢 → 蓋`
(蓋 6,811 vs 盖 92), `悋 → 吝` (760 vs 恡 0), `冫 → 氷` (10,435 vs 冰 88),
`﨑 → 崎` (15,233 vs 埼 431). No canonical is itself a key, so the fold is
idempotent, and every pair is asserted against this committed file by
`JapaneseUtilVariantFoldTest`.

### The frequency guard, and why only 92 ship

A fold **replaces** the lookup key, so a pair whose canonical side is the *rarer*
form does not merely fail to help — it rewrites a query that used to resolve into
one that does not. Unihan's `kSemanticVariant` is loose enough to contain those, so
the direction rule is not sufficient on its own. A pair therefore also ships only
when its canonical side occurs **at least as often as its variant** in the same
corpus: **92 of the 112 measured pairs** pass, and 20 are dropped.

| dropped pair | variant occurrences | canonical occurrences |
|---|---|---|
| 壜 → 罈 | 1,322 | **0** |
| 覊 → 羈 | 196 | 183 |
| 躱 → 躲 | 194 | 0 |
| 輙 → 輒 | 74 | 44 |
| 韈 → 襪 | 51 | 25 |
| 鬭 → 鬥 | 23 | 0 |
| …16 more, all ≤ 30 | | |

`壜` is the everyday form for "bottle" and a dictionary headword — it must resolve
to itself, not to `罈`, which the corpus never uses. The same reasoning drops
`覉 → 羇`, `攅 → 攢`, `飇 → 飆`, `槖 → 橐`, `髠 → 髡`, `攵 → 攴`, `覰 → 覷`,
`麕 → 麇`, `瘂 → 啞`, `崪 → 崒`, `趦 → 趑`, `顖 → 囟`, `皡 → 皞` and `霡 → 霢`.
They stay in the asset — the *data* is Unihan's and is still offered by
`obsoleteFormsOf` — but they are not applied to lookups.


### What the fold can and cannot do

It is **query-side only** — `normalize()` builds the lookup key, displayed OCR text
is never rewritten — and it cannot restore a character the head failed to produce.
Every variant in the table is absent from `vocab.json`, so no OCR line can contain
one: on model output these lines still fail as *deletions* (the proposal layer's
job, `docs/ocr-oov-correction-plan.md`). The fold serves text that reached lookup
without the model — a character typed into a manual override, or dictionary-side
text — and is the cheap, zero-over-correction half of the variant problem: true
variant pairs (`囘`→`回`, `欝`→`鬱`) were the named table work that the OOV census
separated out from the modelling work.
