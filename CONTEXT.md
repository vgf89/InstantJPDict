# InstantJPDict

On-device Japanese OCR dictionary as an Android Accessibility Service — captures screen text and returns dictionary entries from offline JMDict.

## Language

**Rec model**:
CTC line recognizer — PP-LCNetV4 backbone (`hidden 120 / depth 2 / SiLU`) → `1×7` neck → head `18710` classes (`0=blank`, `1..18708=chars`, `18709=space`).
_Avoid_: recognizer, CRNN, SVTR

**Det model**:
DB segmentation `960×960` → threshold `0.3` → unclip `1.1` → contours → `JpDictRect`.
_Avoid_: detector, DBNet, segmentation model

**Line**:
One `JpDictRect` — either horizontal (`width ≥ height`, reading left→right) or vertical (`height > width`, reading top→bottom, right-to-left).
_Avoid_: box, region, text line

**CharBox**:
Per-character `JpDictRect` derived from CTC `charCols` (`(t+0.5)*avgColW`) via `computeCharBoxes`.
_Avoid_: glyph box, character rect

**Crop**:
`Bitmap` slice per `JpDictRect` fed to rec (`48×W` after resize, exact `W`, padded to mult-of-8).
_Avoid_: patch, snippet, ROI

**Bucket**:
Legacy static rec widths `64/128/256/480` — removed in #23. Rec is one dynamic-width model (`rec_dyn`, exact `W`, stride `8`, `seqLen=W/8`).
_Avoid_: width bucket, bin, size variant

**Vocab**:
`PP-OCRv6_small_rec_onnx/vocab.json` — `18710` entries (`blank` excluded, `18709` = space); `char_vocab.json` is legacy.
_Avoid_: charset, dictionary, character_list

**CTC greedy**:
Argmax per timestep (`18710`), skip `blank 0`, collapse repeats, `GAP_CHAR` handling, top-15 alternatives.
_Avoid_: CTC decode, beam search

**OcrEngine**:
`app/src/main/java/com/holopengin/instantjpdict/OcrEngine.kt` facade — `detect` (ncnn) + `recognizeStreaming` (dynamic-width ncnn rec) + `computeCharBoxes`.
_Avoid_: OCR engine, recognizer, detector

**JpDictRect**:
Axis-aligned rect (`left, top, right, bottom`) in screen pixels.
_Avoid_: BoundingBox, Rect

**LineResult**:
`text` + `charBoxes` + `alternatives` + `isVertical` + `rawAlternatives` + `seqLenTotal`/`cropW/H/X/Y` per `Line`.
_Avoid_: OCR result, recognition result

**PP-OCRv6**:
PaddleOCR v6 small (`PP-LCNetV4` + 2-layer attention) — the canonical model family (`model.safetensors` + `config.json`).
_Avoid_: PP-OCR, PaddleOCR, OCRv6
