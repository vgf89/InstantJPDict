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

### Direction (finalized after Phase 0)

| # | what ships | status |
|---|---|---|
| 1 | **Feature 1 = the alternatives list only.** Component-derived candidates (characters the head cannot emit) are appended to the per-character popup the app already has (`LineResult.alternatives` / `AlternativesUiState`). No text change, no threshold, no trigger, no over-correction risk. | ship |
| 2 | **Substitution auto-apply = PARKED.** Revival test is a single host run of the **word gate**: build an inflected-surface/reading index from JMdict and require the candidate to form a known word with the surrounding kana while the emitted character does not. Expected ceiling stated up front: pool coverage (~35–45% of substitutions) caps it to a narrow auto-apply on ~15–30% of substitutions. If the gate fails, general correction is **#73** (visual verifier / learned corrector). | parked |
| 3 | **Variants = table-driven.** Lookup folding plus offering the obsolete form in the popup; never auto-rewrite displayed text to a variant. A pair folds only if its canonical side occurs **at least as often** as its variant in the corpus — 92 of 112 measured pairs pass, 20 dropped (`壜`→`罈` maps an everyday form onto one the corpus never uses, and a fold *replaces* the key). | ship |
| 4 | **Feature 2 = clickable blank, vertical-first.** Contest-blank candidates where evidence exists, manual IME entry always available, **no auto-fill**. | ship |
| 5 | **LM asset = conditional, and the condition differs by step.** Step 1 (substitution list) does **not** need it: shape alone reaches 19/45 top-3 at the loose tier and the shipped tier is a 14-candidate list. Step 3 (deletion list) **does**: the deletion pool is 767 candidates on median with a degenerate shape ordering, so the LM is the only ranker available. Ship the ~7 MB model with Step 3, not with Step 1. | conditional |
| 6 | Blank auto-fill and substitution auto-apply stay recorded as **open**, with their measured gates (M4/M6 and §3b M1). Neither blocks Steps 1–3. | open |

Sequencing, with the acceptance measure for each step:

- **Step 1 — no new assets.** `ComponentTable` + component-derived candidates in the alternatives
  list, ranked by IDF mass. Acceptance: on the rendered benches, how often the list contains the
  truth (pool coverage), entries added per character (median), and whether the paired-bench list
  stays clean (no noise).
- **Step 2 — LM, only if it earns it.** Add `CharLm` (~7 MB), re-measure the same numbers, keep only
  if the top-3 improvement justifies the asset and the load cost. Phase 1's LM half is gated on this.
- **Step 3 — Feature 2, vertical-first.** Clickable blank on the vertical trigger, populated per the
  contest/confident split; manual IME entry always; no auto-fill.
- Optional: a `#44` comment carrying the Phase 0 tables (Aozora excerpts only).

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
| gap trigger, **vertical** | pitch ratio ≥ 1.6 | measured recall 1.00, false 0.00% (M5) |
| gap trigger, **horizontal** | pitch ratio ≥ 1.8 **and** component agreement | measured recall 0.20 at 1.8 alone; 1.6 gives precision 0.13, so horizontal needs the second signal (M5) |
| confident blank | blank score ≥ 0.9 | `'\u3000'` entry vs best non-blank |
| contest blank | blank < 0.9 | use the top-K **kanji-only** majority components |
| majority components | component in ≥ half the top-K | never a strict intersection (`眩` alone empties it) |
| substitution candidates | IDF mass shared ≥ 0.5 | ≥0.9 = the auto-apply tier |
| auto-apply | **OPEN after M1** — see §9 | at ≤0.2% it yields ~1 unique fix per 100 rendered cases; at 0.5% it gives 7 fixes at 71% precision |
| over-correction budget | 0.2% agreed | M1 measured; budget curve in §3b |
| alternatives (default path) | candidates where truth ∈ pool | 19/57 wrong rendered positions; zero over-correction risk |

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

## 3b. Phase 0 results — measured

### M1 — over-correction curve: **auto-apply does not survive the ≤0.2% budget**

1,039 aligned kanji positions over 318 lines (both paired benches + all 100 rendered
cases). Populations differ enormously, which is the first finding:

| population | positions | wrong | truth inside the component pool | at IDF ≥0.7 | at IDF ≥0.9 |
|---|---|---|---|---|---|
| paired game-UI benches | 562 | **10** | 1/10 | 0 | 0 |
| rendered OOV cases | 477 | 57 | 19/57 | 14 | 11 |

So the corrector's entire opportunity is ~11–19 positions across 100 rendered cases, and
the paired benches are useless as a test bed for it — **evaluate on the OOV-rich rendered
benches or not at all.**

| budget (over-correction of correct characters) | best achieved | applied | precision | tier / margin |
|---|---|---|---|---|
| 0.05% / 0.1% / **0.2%** | 0.000% | 2 | 100% | 0.3 / 5.0 |
| 0.5% / 1.0% | 0.412% | 11 | 45% | 0.7 / 2.0 |
| 2.0% | 1.646% | 24 | 29% | 0.9 / 0.8 |
| *(rendered only)* 0.5% | 0.476% | 8 | 62% | 0.7 / 2.0 |
| *(rendered only)* 0.5% | 0.476% | 7 | **71%** | 0.9 / 2.0 |

At the agreed ≤0.2% budget the yield is **2 applied corrections, and they are the same
position rendered twice (one unique fix)**. At 0.5% — 2.4× the budget — it is 7 applied,
5 right, i.e. a net −2 to −3 errors per 477 positions.

**The failure mode is the LM's frequency bias, not the components.** Worked examples at
each budget level (rendered cases):

```
budget 0.2%  tier 0.9 margin 5.0   2 applied, 100% right, 0 rewritten
  [RIGHT]  az_58dc_0  曇 -> 壜  (truth 壜, idf 1.00, gain +5.68, comps 二/厶/日/雨)
budget 0.5%  tier 0.7 margin 2.0   8 applied, 62% right, 2 correct rewritten
  [OVER-CORRECT] epub_fuk_13  羊 -> 美  (truth 羊, idf 1.00, gain +4.42)
  [RIGHT]        az_58dc_0    曇 -> 壜  (truth 壜, idf 1.00, gain +5.68)
  [RIGHT]        az_58dc_1    曇 -> 壜  (truth 壜, idf 1.00, gain +3.78)
  [wrong-fix]    az_58dc_2    還 -> 環  (truth 壜, idf 0.74, gain +2.06)
budget 2.0%  tier 0.9 margin 0.8   15 applied, 47% right, 8 correct rewritten
  [OVER-CORRECT] 羊 -> 美, 雪 -> 鱈, 二 -> 余, 貧 -> 齎
```

Every over-correction replaces a *rarer* character with a *more frequent* one (`羊`→`美`,
`雪`→`鱈`, `二`→`余`). That is the n-gram doing exactly what it was trained to do, and it
is the opposite of what this feature needs. The deletion pipeline escaped this only
because dictionary ∩ components had already cut the candidate set to ~7; a per-position
corrector has no equivalent gate, and a character n-gram cannot supply one.

**Consequence:** Feature 1 ships as **alternatives-on-tap** (zero over-correction risk,
and the truth is present in the pool for 19/57 wrong rendered positions). Auto-apply is
**open** — see §9; the options are (a) keep ≤0.2% and accept ~1 fix per 100 cases,
(b) relax to 0.5% for ~7 fixes at 71% precision and ~0.48% over-correction, (c) drop
auto-apply. A word-level context model is what would make auto-apply defensible (that is
#73's territory, not a char n-gram's).

### M2 — OCR context vs clean context

Same candidates, ranked in the OCR text (production) versus the clean source text:
target ranked 1st in **10/67** versus **13/67** (top-3 unchanged at 13/67). So the oracle
leak in the earlier Aozora numbers is real but modest (~23% relative on rank-1); production
numbers should use the OCR context, and quoted results above do.

### M5 — trigger per orientation: **vertical is near-perfect, horizontal is not**

| bench | deletions (median ratio) | ordinary | rule | recall | false rate | precision |
|---|---|---|---|---|---|---|
| horizontal (trails ×2) | 1.67 (p25 **1.00**) | 1.00 | ≥1.6 | 0.60 | 1.10% | **0.13** |
| | | | ≥1.8 | 0.20 | 0.66% | 0.08 |
| vertical (vert_large) | 2.00 (p25 1.92) | 1.00 | **≥1.6** | **1.00** | **0.00%** | **1.00** |
| | | | ≥1.8 / ≥2.0 | 0.75 | 0.00% | 1.00 |

Horizontal deletion gaps are sometimes indistinguishable from ordinary spacing (p25 = 1.00
— the character vanished *without* leaving a wider gap), and the false-positive rate makes
1.6 unusable there on its own. Ship **orientation-specific thresholds**: vertical 1.6
(recall 1.00, precision 1.00), horizontal ≥1.8 **and** require additional evidence
(component agreement) before showing an affordance.

### M3 — LM corpus and pruning: **order/count/Wikipedia buy nothing above a plateau**

Bench: the rendered OOV cases (51 substitutions, 38 deletions), candidates ordered by IDF
mass shared with the emitted character — the shipped rule — and ranked in the OCR context.

| corpus | order | prune | entries | ≈ size | sub rank-1 | sub top-3 | del rank-1 |
|---|---|---|---|---|---|---|---|
| aozora | 3 | ≥1 | 1,314,503 | **6.6 MB** | 12/51 | 12/51 | 7/38 |
| aozora | 3 | ≥2 | 632,551 | 3.2 MB | 9/51 | 11/51 | 6/38 |
| aozora | 3 | ≥5 | 354,188 | 1.8 MB | 8/51 | 9/51 | 5/38 |
| aozora | 4 | ≥5 | 1,437,581 | **7.2 MB** | 12/51 | 12/51 | 7/38 |
| aozora | 4 | ≥1 | 3,568,707 | 17.8 MB | 12/51 | 12/51 | 7/38 |
| aozora+wiki | 4 | ≥5 | 3,196,258 | 16.0 MB | 12/51 | 12/51 | 7/38 |
| aozora+wiki | 4 | ≥1 | 8,023,765 | 40.1 MB | 12/51 | 12/51 | 7/38 |

**Accuracy is flat from ~7 MB to 40 MB** and across corpus choice (6.6 MB order-3 already
reaches the plateau). Consequences for the asset: ship **order 4 pruned at count ≥5
(≈7 MB)**, or order 3 at count ≥1 if reviewability matters more than 1 MB — and spend the
remaining budget on *corpus size*, not on n-gram order or count, because neither buys
measurable ranking quality on this bench.

**ja Wikipedia does not help this task.** Ranks are identical with and without it, and its
coverage of the top OOV kanji is far worse than Aozora's in comparable samples: `呟` 7 vs
74, and **zero** occurrences of `溌` `伜` `俥` `囘` `欝` `燵` `扨` `壜` `尠`. The intended
benefit — "rare-but-modern" characters — is not where these error classes live: they are
literary and variant forms, which novels use and encyclopedia prose does not.
*Caveat:* the sample is the dump's first 400 MB (oldest pages, page-id order), so re-measure
on a recent slice before writing Wikipedia off entirely. Directionally the corpus should be
chosen by **where the error classes live**, not by "more data is better".

**Cap caveat (matters for reading any candidate-pool number here).** Ordering candidates by
shared-component *count* with a cap of 80 put the truth in the pool for only 18/51
substitutions — so a rank of "10/51" was measuring the cap, not the LM: within the pool the
LM ranks the truth 1st in 10/18 (56%), consistent with M1. Always rank the candidate pool by
IDF mass (the shipped rule) and report pool coverage next to any rank.

**Deletion pools: the IDF ordering is degenerate, and that was my error.** A deletion pool
is *defined* as "carries all of the required components", so every member shares all of
them: every IDF-mass key is exactly `-1.0` and a cap then truncates an all-tied list. Any
deletion rank taken from a capped, IDF-ordered pool measured the cap and the tie-break, not
the LM. Re-measured with the pool uncapped and ranked by the LM alone, coverage beside the
rank (`scripts/oov_pool_report.py`):

| mode | pool | median size | truth in pool | rank-1 | top-3 |
|---|---|---|---|---|---|
| substitutions | IDF mass ≥ 0.9 | 5 | 9/59 | 9/9 | 9/9 |
| substitutions | **IDF mass ≥ 0.7 (shipped tier)** | **14** | 12/59 | **10/12** | **12/12** |
| substitutions | IDF mass ≥ 0.5 | 33 | 18/59 | 10/18 | 13/18 |
| substitutions | any component | 2,455 | 45/59 | 13/45 | 19/45 |
| deletions | majority-AND, uncapped | **767** | **28/38 (74%)** | 11/28 | 15/28 |

The majority vote collapses to a **single component in 26 of 38** deletion cases, which is
why those pools run to hundreds of candidates and why shape carries no signal there: for
deletions the LM is the only available ranker, while for substitutions shape alone already
reaches 19/45 top-3 and the LM's job is to order a 14-candidate list.


### Still to run

**M4** (blank auto-fill operating point) and **M6** (reading-side alignment for confident
blanks) — both only matter if Feature 2 auto-fill is reopened, which is deferred.



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

> **M1 changed this phase.** Auto-apply does not survive the ≤0.2% budget (§3b), so the
> shipping order is: **candidate generation (3.1) + alternatives UI (3.4) first** — zero
> over-correction risk, and the truth is in the pool for 19/57 wrong rendered positions.
> The scorer (3.2) and the apply/mark path (3.3) stay built but **behind the open
> auto-apply decision** in §9; default them OFF until that decision lands.

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
| **Feature 1 auto-apply (OPEN after M1)** | measured trade: ≤0.2% budget → ~1 unique fix per 100 rendered cases; 0.5% → 7 fixes at 71% precision (net −2/−3 errors per 477 positions). A character n-gram cannot separate these cases because its frequency bias prefers the common character (`羊`→`美`). Options: keep the strict budget, relax to 0.5%, or ship alternatives-only. A word-level context model would be needed to do better (#73). |
| The corrector rewrites already-correct text at scale | bounded by the keep-prior and the tier/margin gates; at ≤0.2% it is 0.000% measured, at 0.5% it is 0.41–0.48% |
| The paired benches cannot evaluate this feature (10 wrong kanji positions, 1 in pool) | evaluate only on the OOV-rich rendered benches |
| Horizontal lines behave much worse than vertical | M5: vertical trigger recall 1.00 / precision 1.00; horizontal recall 0.60 / precision 0.13 → orientation-specific thresholds and an extra signal for horizontal; never blend the two in reporting |
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
