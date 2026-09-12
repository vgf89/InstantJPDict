# OCR correction + clickable blanks — implementation plan

**Issue:** #44 (OOV kanji), with #73 as the backburner alternative.
**Goal:** correct confidently-wrong kanji using component + variant + LM evidence, and let
the user fill in characters the recogniser dropped, **without touching the model**.

**Architecture:** both features are a post-processing pass over evidence the recogniser
*already caches* (`LineResult.rawAlternatives`, `alternatives`, `charBoxes`, `overrides`).
No decode rewrite, no model change, no retraining. The only new runtime data is a pruned
character LM and a kanji variant table; the KRADFILE component table already ships.

**Read before starting:**
- `~/.hermes/skills/software-development/instantjpdict-ocr-accuracy/` — every measured
  number below, plus the probe scripts that produced them. Do not re-derive from scratch.
- `docs/bundled-dictionaries.md` (vendored-data pattern: PROVENANCE.txt + deterministic
  build tool), `docs/repro-runbook.md` (model rebuild path).

**Phase 0 gates the design.** Do not start Phase 3/4 before its numbers exist.

---

## 0. Decisions already made — do not re-litigate

| # | Decision |
|---|---|
| a | Corrections run at **every character position**; substitution errors are confidently wrong, so there is no trigger. The emitted character competes as a candidate with a **keep-prior**, and competing candidates are **re-ranked by the LM**. |
| b | Accepted: the corrector is a **~20–30% coverage feature at 83–100% precision**, not a general kanji fixer. Say so in the UI copy and the issue. |
| c | **Modern forms by default.** The variant table is used for lookup folding (shipped) and to *offer* the outdated form in the alternatives list when the evidence suggests the source used it. Never auto-rewrite display to a variant. |
| d | Prune the LM. Second corpus: **ja Wikipedia** (better modern coverage, incl. rare-but-modern kanji). **Budget 20 MB for the first pass**, reassess after pruning numbers exist. |
| e | Kanji-only LM: **measured negative, dropped** (see §1). Confident-blank candidate generation is an **open question** — no auto-fill for Feature 2. |
| f | Where no candidates exist, fall back to a **clickable blank** the user fills by hand. |
| 1 | Auto-apply **only** where measured ≥90% (substitution near-identity relation, IDF ≥0.9 / shares all emitted components). Blanks always show the list. |
| 2 | Over-correction budget **≤0.2%** of correctly-recognised characters, with a full budget curve and **concrete examples at every level** before shipping. |
| 3 | Variants: modern by default, outdated form surfaced **as an alternative only**; lookups collapse to modern. |
| 4 | LM asset budget **20 MB**. |
| 5 | Confident blank with no candidates: **show nothing fabricated, but keep the position clickable** for manual IME entry (the app has `showManualInput`). |

**Standing project rules:** commit locally, never push without explicit instruction;
commit footer `*penned by Hermes Agent + opencode-go/deepseek-v4.1-flash on madoka*`;
build/verify in a throwaway container; on-device verification via the APK-over-HTTP flow;
**copyrighted-book material (the user's EPUBs, the game benches) stays in `/tmp`** — only
derived facts or public-domain (Aozora) examples may be committed or posted to the issue.

---

## 1. What is measured (the basis for every threshold)

**Shipped this cycle:** lookup variant folds `e06a1df`; 160 over-cut kanji restored +
GELU-fusion runbook fix `068b81c`; blank-recovery policy `b4c87e1` (error-neutral at every
floor → `DISABLED` default); KRADFILE component table `81248cb` (12,156 kanji, CC BY-SA).

**Error modes** — the two features split exactly along this line:

| bench | deletions | substitutions |
|---|---|---|
| EPUB `呟` spot test, 28 rendered cases | **28** | 0 |
| Aozora top-12 OOV kanji, 72 rendered cases | 13 | **59 (82%)** |

**Trigger (deletions, 231 paired lines):** width/pitch ratio spans a dropped character at
median **2.00** vs **1.00** for ordinary intervals. ≥1.6 → recall 0.89, false 0.40%;
≥1.8 → 0.68 / 0.13%; ≥2.0 → 0.58 / 0.09%. Rejected detectors, do not retry: probability
(error-neutral at every floor), ink under blank (precision 0.002), lexical plausibility.

**Candidate sources (13 EPUB deletions):**

| source | set size | top-1 | top-3 |
|---|---|---|---|
| raw dictionary (inflected-surface rule) | 229 | 1/13 | 3/13 |
| **dictionary ∩ krad components** | **7** | **6/12** | **10/12** |
| krad components as generator | 767 | 6/13 | 7/13 |
| union of both | 844 | 1/13 | 3/13 |

**Substitution candidate rules (51 cases):**

| rule | covers | median set | top-1 (LM) | top-3 (shape+LM) |
|---|---|---|---|---|
| shares ≥1 component | 45/51 | 2,523 | 42% | 49% |
| shares ≥3 | 15/51 | 9 | 73% | 80% |
| shares all emitted components | 9/51 | 4 | **9/9** | 9/9 |
| IDF mass shared ≥0.5 | 18/51 | 50 | 56% | 67% |
| **IDF mass shared ≥0.7** | 12/51 | 12 | **83%** | **12/12** |
| IDF mass shared ≥0.9 | 9/51 | 4 | 9/9 | 9/9 |

88% of substitutions share a component with the truth (the head emits a component-space
neighbour: `伜`→`仲`/`体`/`件`, `俥`→`庫`, `壜`→`曇`, `溌`→`発`, `燵`→`焼`); 6/51 share none
(true variants: `囘`→`回`, `欝`→`鬱`).

**Auto-insert gates (25 deletions):** list-size ≤3 → 33%, ≤8 → 40%, all → 36%; LM margin
≥0.2 → 67% (9 cases), ≥0.4 → 71% (7); size ≤20 **and** margin ≥0.4 → 60%. Hence decision 1.

**Orientation matters and is never blended:** for deletions, vertical rank-1 50% / top-3
83% vs horizontal 10% / 48%. Horizontal top-5s carry symbol junk (`＆ ゅ ゥ ッ`), so the
majority vote collapses to a generic component (`口` → 2,836 candidates). Always exclude
non-kanji from the vote (`odp.kanji`) and report per orientation.

**Rejected, with reasons (do not rebuild):** text LM alone for selection (rank 3–83 of
229 — the candidates are one inflection class); kanji-only LM ("rank on content not
conjugation") — 3rd of 7 vs 1st of 7, so no help once the component filter has cut the
field; bidirectional LM — marginally better (30→24, 83→59), not the missing piece.

---

## 2. Design

### 2.1 Where the pass runs

`OcrAccessibilityService` recognises a line on demand → `OcrEngine.ctcDecode` builds a
`LineResult`. `OcrOverlayStateController.refreshLinesWithThreshold(engine, threshold)`
already re-decodes **from cache without re-running the model** and carries `overrides`
across. The new pass hangs off exactly that path:

1. recognition produces the raw `LineResult` (unchanged);
2. `OcrOovProcessor.process(line, dictionary, lm, components, variants)` returns a
   `ProcessedLine { corrections, gaps, suggestions }`;
3. the UI applies them through mechanisms that already exist: `LineResult.overrides[i]`
   for an applied character, `GAP_CHAR` for a blank, `AlternativesUiState` for the list,
   `showManualInput` for hand entry.

### 2.2 Evidence available (`app/src/main/java/com/holopengin/instantjpdict/OcrOverlayStateController.kt:12`)

| field | use |
|---|---|
| `rawAlternatives: List<List<Pair<Char,Float>>>` | top-15 per **timestep**; **blank is the `'\u3000'` entry** (`OcrEngine.kt:2159`) → the confident/contest split |
| `alternatives: List<MutableList<Pair<Char,Float>>>` | top-15 per **emitted character** |
| `charBoxes: List<JpDictRect>` | pixel rects per character → the pitch-ratio trigger (may be empty until crop geometry is known; fall back to timestep columns, as `reDecodeLineResult` does) |
| `overrides: MutableMap<Int, Pair<Char,Float>>` | already rendered, already carried across re-decode |
| `isVertical` | orientation split |

**Missing and added in Phase 2:** the timestep index of each emitted character, and the
ability to insert a synthetic character position (for a gap) keeping `text`/`charBoxes`/
`alternatives` index-aligned. Note `OcrOverlayStateController.kt:366` already special-cases
`GAP_CHAR` in lookup — preserve that.

### 2.3 Policies (all constants come from Phase 0; no taste)

| gate | initial value | notes |
|---|---|---|
| gap trigger | pitch ratio ≥ 1.8 | between two decoded characters; 1.6 for max recall, 2.0 for max precision |
| confident blank | blank score ≥ 0.9 | `'\u3000'` entry vs best non-blank |
| contest blank | blank < 0.9 | use the top-K **kanji-only** majority components |
| majority components | component in ≥ half the top-K | never a strict intersection (`眩` alone empties it) |
| substitution candidates | IDF mass shared ≥ 0.5 | ≥0.9 = auto-apply tier |
| auto-apply | IDF ≥ 0.9 or all emitted components present | marks the character (see §6) |
| over-correction budget | ≤ 0.2% | M1 gate |

### 2.4 Assets

| asset | source | licence | status |
|---|---|---|---|
| `components/krad_components.txt` | EDRDG KRADFILE | CC BY-SA 4.0 | **shipped** |
| `lm/char_lm.txt` (≤20 MB) | Aozora (HF mirror) **+ ja Wikipedia dump** | PD / CC BY-SA 4.0 | new, Phase 1 |
| `variants/kanji_variants.txt` | Unihan `kSemanticVariant`/`kZVariant` | Unicode licence | new, Phase 1 |

---

## 3. Phase 0 — measurements that gate the design

All of these run on the host with the existing probe harness
(`instantjpdict-ocr-accuracy/scripts/{pipeline_probe,oov_bench_measure,oov_policy_and_similarity}.py`)
and the shipped INT8 model. Nothing here touches app code.

### M1 — over-correction budget curve (gates decision 2)
**Method:** apply the corrector at every position of both paired benches (208+10 lines)
and the 100 rendered cases; for each operating point compute (a) share of *correct*
characters rewritten, (b) net CER delta, (c) precision of applied corrections.
**Deliverable:** a table at budgets 0.05%, 0.1%, 0.2%, 0.5%, 1%, 2% **plus 10 worked
examples per level** — context, emitted character, proposed character, LM margin, IDF
mass. Game/EPUB examples stay in `/tmp`; use Aozora excerpts for anything shared.
**Gate:** the shipped operating point must hold ≤0.2% rewrites **and** a net CER gain
(improvement, not merely non-regression) on both benches, split by orientation.

### M2 — production-context ranking
Re-rank with the head's own OCR text as the context (every number in §1 used the clean
source text, which is slightly oracle at the corrected position). If rank-1 drops
materially, the operating points move and decisions 1/2 need revisiting.

### M3 — LM corpus + pruning curve (gates decision 4)
Add ja Wikipedia as a second corpus; report for the top-40 OOV kanji which corpus covers
which (**especially rare-but-modern kanji** — this is the input to the "should some of
these be in the CTC head instead?" question, see §9). Then prune the 4-gram by order and
count floor and plot rank-1/top-3 on the error-class bench against asset size.
**Deliverable:** the ≤20 MB model that maximises ranking accuracy **on our error classes**
(NB: an n-gram cannot be pruned *per error class* — the lever is corpus choice + count/order
pruning evaluated on a bench weighted to those classes; say this in the issue so it is not
misread as a per-class model).

### M4 — blank auto-fill operating point (input to the deferred feature)
Evaluate `majority-IDF mass ≥ t ∧ list ≤ N ∧ LM margin ≥ m` on the 41 rendered blank
cases. Not a shipping gate — this is the evidence that would reopen decision 1 later.

### M5 — trigger in pixel space, per orientation
Calibrate the pitch ratio on `charBoxes` geometry (the 1.6–2.0 scale was measured in
timesteps; the ratio is scale-invariant in principle) and report recall/false-rate **per
orientation** at 1.6/1.8/2.0 on both benches.

### M6 — reading-side alignment generator (the one open candidate source)
For a confident blank, the kana on **both** sides plus the gap width constrain the missing
word. Test: build a lookup from dictionary **readings** and search entries whose reading
matches the surrounding kana with a length permitted by the gap, then LM-rank. This is the
proposal for §9's open question — measure coverage on the 13 Aozora deletions (currently
0 candidates for nouns/conjunctions under the one-sided rule).

---

## 4. Phase 1 — assets and loaders

**Task 1.1 — `tools/build_char_lm.py`** (mirror `tools/build_component_table.py`).
Inputs: the Aozora mirror + a ja Wikipedia dump extract; knobs `--order`, `--min-count`,
`--max-entries`; output `app/src/main/assets/lm/char_lm.txt` (one `context\tcount` per
line, sorted, LF) + `PROVENANCE.txt` (sources, dates, licences, SHA-256, entry count).
Test: rerun → byte-identical; a second run with a stricter `--min-count` is smaller and
still contains the top context of a known string.
Commit with the footer.

**Task 1.2 — corpus preparation script** (same tool or `tools/fetch_lm_corpora.sh`):
document the exact dump URL, the extraction (tag-strip is sufficient for LM training), and
the licence text. Wikipedia text is CC BY-SA 4.0 — it must travel in `PROVENANCE.txt` and
`docs/bundled-dictionaries.md`.

**Task 1.3 — `tools/build_kanji_variants.py`** → `variants/kanji_variants.txt`
(`variant<TAB>canonical`, deterministic, + PROVENANCE). Source Unihan `kSemanticVariant` /
`kZVariant`; keep only single-character CJK pairs and mark the direction (obsolete →
modern) where Unihan states it. Test: `囘→回`, `欝→鬱` present; file byte-identical on rerun.

**Task 1.4 — `util/ComponentTable.kt`**: load `krad_components.txt` once (lazy, off the
main thread), expose `componentsOf(ch: Char): CharArray`, `kanjiWith(all: CharArray): List<Char>`
via a precomputed inverted index, and `idfMass(chars): Float`. Unit tests: 呟 → `亠 口 幺 玄`;
`kanjiWith(口,亠)` contains 呟; IDF ordering (車 > 一); missing character → empty, never throws.

**Task 1.5 — `util/CharLm.kt`**: load `char_lm.txt`, score `avgLogProb(window: CharSequence)`
with backoff (order-4 → 1), return `Float.NEGATIVE_INFINITY`-equivalent for unseen. Format
decision: **text first** (reviewable, deterministic); if load >200 ms or RSS >40 MB on
device, add a packed binary variant behind the same API. Unit tests: known string scores
higher than a shuffled one; unseen context falls back rather than crashing; load is idempotent.

**Task 1.6 — `util/KanjiVariants.kt`**: load the variant table; expose
`canonical(ch)`, `obsoleteFormsOf(ch)`. Unit test: `囘→回`, `欝→鬱`; unknown char → itself.

**Task 1.7 — docs**: add both assets to `docs/bundled-dictionaries.md` (source, licence,
entries, generator, regeneration command) and the new tools to `docs/repro-runbook.md`.

---

## 5. Phase 2 — evidence accessors + synthetic positions

**Task 2.1 — `util/LineEvidence.kt`**: from a `LineResult`, produce per emitted character:
`{ index, timestepStart, timestepEnd, blankScore, topK: List<Pair<Char,Float>> }`, reading
`rawAlternatives` + `alternatives`. Unit test on a synthetic `LineResult`: the `'\u3000'`
entry is recognised as the blank score (**assert this invariant** — it silently breaks the
confident/contest split if it changes); a contest timestep reports blank < 0.9; a confident
one ≥ 0.9.

**Task 2.2 — `util/GapDetector.kt`**: given the per-character geometry (prefer
`charBoxes`; fall back to timestep spans), return gaps as
`{ insertAt: Int, pitchRatio: Float, spanPx: Float }` for adjacent pairs whose spacing
exceeds `GAP_RATIO * median`. Unit tests: a synthetic 3-character line with a double gap
reports one gap with ratio ≈2; a uniform line reports none; vertical and horizontal both work.

**Task 2.3 — synthetic position insertion**: `LineResult.withGapCharAt(index, column)` that
inserts `GAP_CHAR` into `text`, a box into `charBoxes` (interpolated from neighbours), and
an entry into `alternatives`, keeping all lists index-aligned. Unit test: lengths stay equal,
`overrides`/`alternatives` still resolve at the shifted indices, and lookup treats the
inserted char as un-lookupable (`OcrOverlayStateController.kt:366` behaviour preserved).

---

## 6. Phase 3 — Feature 1: kanji correction

**Task 3.1 — `util/OovCandidates.kt`** (pure, no Android): given an emitted character and
the component table, yield `Candidate(char, sharedIdfFraction, sharesAllComponents)`:
component neighbours via the inverted index (cheap set intersection), plus variant forms,
plus dictionary surfaces when a reading rule applies. Unit test with the measured pairs:
for `仲` and the 伜 family, `伜` is present with IDF ≥0.9 (shares 化); for 壜/曇 the relation
direction is asserted explicitly.

**Task 3.2 — `util/KanjiCorrector.kt`**: for each position, take the emitted character **as
a candidate with a keep-prior**, score all candidates with `CharLm` in the line context,
and emit `Correction{index, from, to, margin, idfFraction}` only when the winner beats the
emitted character by `MARGIN_MIN`. Unit tests: (a) a correct character is never rewritten at
the default margin; (b) a near-identity substitution is corrected; (c) an emitted character
with the best LM score is kept.

**Task 3.3 — apply + mark**: write corrections through `LineResult.overrides[i]`; keep the
original in a parallel map (`correctionSource: Map<Int, Char>`) so the UI can style the
character (dotted underline) and offer revert + the full alternatives list. Wire into
`OcrOverlayStateController.refreshLinesWithThreshold` so corrections survive re-decode.

**Task 3.4 — alternatives list**: extend `AlternativesUiState.candidates` at corrected and
OOV positions with the generated candidates (ranked), `GAP_CHAR` included where applicable,
`AlternativeChar.isSelected` set on the current pick. Include **obsolete variant forms** of
the current character when the variant table says the source may have used one (decision 3).

**Task 3.5 — settings**: `PREF_OOV_SUGGESTIONS` (default on — alternatives only, no text
change) and `PREF_OOV_CORRECTIONS` (auto-apply, only the ≥90% tier; default off until M1
passes). Follow the existing `OcrEngine.PREFS_NAME` + `PREF_*` pattern and MainActivity's
toggle plumbing.

**Task 3.6 — verification**: unit tests green (`./gradlew :app:testDebugUnitTest`); M1 curve
holds ≤0.2% and net CER gain; then build the benchmark APK and hand it over for on-device
perception check (§10).

---

## 7. Phase 4 — Feature 2: clickable blanks

**Task 4.1 — confidence split**: per gap, read the blank score from the evidence:
- **contest blank** (< 0.9): majority components from the top-K **kanji-only** alternatives
  → `ComponentTable.kanjiWith(...)` ∩ dictionary candidates → LM ranking.
- **confident blank** (≥ 0.9): no component evidence. Dictionary reading rule only
  (measured: verbs yes, nouns/conjunctions no). If M6 lands, use reading-side alignment.
- **no candidates**: insert the clickable blank anyway (decision 5) with an empty list.

**Task 4.2 — affordance**: insert `GAP_CHAR` for a detected gap (Task 2.3) and render it as
a tappable marker; tap opens the alternatives popup with the ranked candidates and the
**manual IME entry** option (`showManualInput`, `OcrAccessibilityService.kt:1606`).
A filled blank becomes an `overrides[i]` entry like any other correction.

**Task 4.3 — policy + settings**: reuse `BlankRecovery`'s measured constants pattern;
`PREF_BLANK_AFFORDANCE` (default on — it marks, never silently inserts text). Keep the
existing `blankThreshold`/`DISABLED` behaviour untouched. **No auto-fill in this phase**:
decision 1 puts it at ≥90% only, and the blank-mode gates measured 33–71%; M4 is the
evidence that would reopen it.

**Task 4.4 — verification**: unit tests for the trigger and the population logic; then
on-device: vertical prose with a known dropped character shows a tap-fillable blank, a
uniform line shows none, and the manual entry path works.

---

## 8. Phase 5 — docs, tracker, skill

- `docs/bundled-dictionaries.md` + `docs/repro-runbook.md` (Phase 1 assets/tools).
- Comment on #44 with the Phase 0 tables (derived facts and Aozora excerpts only).
- Update the `instantjpdict-ocr-accuracy` skill: new probes, the shipped operating points,
  the rejected approaches (so nobody retries them).
- Reference `#73` for the learned-corrector backburner; note explicitly that this plan is
  the "no retraining" path.

---

## 9. Risks and open questions

| risk / question | stance |
|---|---|
| The corrector rewrites already-correct text at scale | bounded by M1 (≤0.2%), the keep-prior, and the ≥90% tier; every correction is marked and reversible |
| Horizontal lines behave much worse than vertical | ship the list either way; auto-apply only where the per-orientation numbers hold; never blend the two in reporting |
| Confident blanks have no candidate source (nouns/conjunctions) | **open** — M6 (reading-side alignment) is the experiment; auto-fill deferred (decision e/f) |
| Rare-but-modern kanji that are absent upstream (`vocab.json`) can never be emitted by the head | post-hoc insertion only, unless we retrain the head with extra classes — that is #73's territory; M3's Wikipedia coverage numbers decide whether it is worth raising separately |
| 20 MB LM load time / RSS on a phone | text format first, measure; packed binary behind the same API if needed; load off the main thread |
| Character-position bookkeeping (`text` ↔ `charBoxes` ↔ `alternatives`) | Task 2.3 pins the invariant with a unit test; a desync would corrupt tapping and lookup |

## 10. Verification protocol

1. Unit tests: `export ANDROID_HOME=… JAVA_HOME=… && ./gradlew :app:testDebugUnitTest`
   — all green before any handover.
2. Host measurements: Phase 0 scripts, numbers recorded in the plan/issue with model,
   preprocess constants, bench and line counts (a CER without those is unreproducible).
3. On-device: build the benchmark APK (`IJPD-<shortsha>.apk`), serve it over HTTP per the
   standing flow, and state which numbers/behaviour should change (vertical prose, first).

## Appendix — file inventory

**Existing:** `OcrEngine.kt` (`GAP_CHAR`, `ctcDecode`, `reDecodeLineResult`),
`OcrOverlayStateController.kt` (`LineResult`, `AlternativeChar`, `AlternativesUiState`,
`refreshLinesWithThreshold`), `OcrAccessibilityService.kt` (`showManualInput`), `LineOverlayView.kt`,
`util/{JapaneseUtil,BlankRecovery}.kt`, `assets/components/krad_components.txt`,
`tools/{build_component_table,prune_ctc_head}.py`.

**New (planned):** `util/{ComponentTable,CharLm,KanjiVariants,LineEvidence,GapDetector,OovCandidates,KanjiCorrector}.kt`,
`assets/lm/char_lm.txt`, `assets/variants/kanji_variants.txt`,
`tools/{build_char_lm,build_kanji_variants}.py`, tests beside each new Kotlin file.
