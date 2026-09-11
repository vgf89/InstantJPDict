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

### Installation is idempotent

`DictionaryImporter.importBundledAsset` deletes any existing dictionary with the
same title before importing, so re-tapping the button repairs the install rather
than stacking a second copy of all 124k rows.

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
