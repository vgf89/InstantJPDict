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

Scoring the same candidates with an n-gram trained on kanji-only subsequences
(to rank on content rather than conjugation) was measured and did **not** help:
rank 3 of 7 against the plain model's 1 of 7. Once the component filter has cut
the field to single digits, conjugation noise stops mattering.

