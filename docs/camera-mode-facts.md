# How a bitmap reaches OCR today, and how separable the results UI is

Read-only survey of `/home/holopengin/Projects/InstantJPDict`.
All references are `path:line`. Facts only.

---

## 1. From screen grab to model input

**Capture.** `OcrAccessibilityService.triggerCapture` is the only capture site:

- `app/src/main/java/com/holopengin/instantjpdict/OcrAccessibilityService.kt:331` —
  `private fun triggerCapture(onSuccessAction: (Bitmap) -> Unit)`
- `:332` `takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {`
  `takeScreenshot` is an `AccessibilityService` API; `Display.DEFAULT_DISPLAY` is the whole screen at native resolution.
- `:337-339` on success:
  `val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, true)` then `buffer.close()`.

So the bitmap handed downstream is a **software ARGB_8888 copy of the full display**, in the display's colour space. It is triggered by the floating button (`:294-306`), which hides itself, posts 50 ms, then calls `triggerCapture { showScreenshotOverlay(it) }`.

**Entry into the engine.** `showScreenshotOverlay(bitmap)` (`:353`) stores it as the field `screenshotBitmap` (`:355`) and launches the OCR job (`:797`):
- `:803` `val lineBoxes = withContext(Dispatchers.IO) { ocrEngine.detect(bitmap) }`
- `:846-851` `ocrEngine.recognizeStreaming(bitmap, lineBoxes) { results -> … }` inside `withContext(Dispatchers.IO)`.

**What `detect` does to the bitmap** — `OcrEngine.detect(bitmap: Bitmap)` (`OcrEngine.kt:317`):
1. Reads size from the input, no fixed assumption: `:319-320` `val origW = bitmap.width.toFloat(); val origH = bitmap.height.toFloat()`.
2. Longest-side resize: `:324-331`
   `` val modelSize = DET_MODEL_SIZE.coerceIn(320, 960) `` … `val scale = targetLong.toFloat() / maxOf(origW, origH)` … `Bitmap.createScaledBitmap(bitmap, resizeW, resizeH, true)`.
   `DET_MODEL_SIZE = 896` by default (`:70`); `detLongSide` returns `DEF_DET_LONG_SIDE = 960` (`:249-250`, `:65`).
3. Letterbox to a square, **opaque mid-grey 128** pad, image centred: `:334-341`
   `canvas.drawColor(Color.rgb(128, 128, 128))` … `canvas.drawBitmap(resized, (modelSize - resizeW) / 2f, (modelSize - resizeH) / 2f, null)`; `resized.recycle()` at `:342`.
4. NCHW `3 × modelSize × modelSize` float input, ImageNet normalisation via a precomputed LUT: `:344-367`, LUT at `:202-209` (`(v/255 - mean)/std`, means 0.485/0.456/0.406). Channels are read straight from the ARGB8888 pixel bytes (`px shr 16 and 0xFF`, etc. `:359-361`) via `letterbox.getPixels(...)` (`:354`).
5. `val probArr = det.infer(imgData, modelSize, modelSize)` (`:370`; `DetNcnn.detect/infer` at `DetNcnn.kt:34`).
6. Contours are mapped **back to original bitmap pixel space** by inverting the resize+letterbox offset: `:479-488`
   `val resScaleW = origW / resizeW.toFloat()` … `val bx = ((minX - imgLeft) * resScaleW).roundToInt()…`. Then unclip/merge/degenerate-filter/vertical-shrink/ruby-trim/split/sort (`:490-581`).

**Net assumptions of `detect`:** any non-degenerate `Bitmap` (uses its own `width/height`), RGB-readable software bitmap (`getPixels`/`createBitmap`), any aspect ratio (letterboxed), returns boxes in that bitmap's pixel coordinates. If `detNcnn` is null it returns `emptyList()` (`:318`).

**What `recognizeStreaming` consumes** — (`:2103`):
- Jobs = the passed boxes, dropping `< 4 px` sides: `:2113-2116`; orientation rule `isVerticalBox(box) = box.height() >= box.width() * 1.25f` (`:640-641`).
- Crops come from the **same bitmap**: `:947-953`
  `val cropX = maxOf(job.bbox.left, 0)` … `Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)`.
- Per crop: portrait crops (`ch >= cw * 3 / 2`) are rotated 270° (`:1087-1090`); resized to `48 × targetW` (`REC_TARGET_H = 48` at `:162`; `Bitmap.createScaledBitmap(src, targetW, targetH, true)` at `:863`); pixels greyed + normalised `gray/127.5 - 1` into `3 × 48 × modelW` NCHW, `modelW` padded up to a multiple of 8 (`:862`, `:867`), `seqLen = modelW/8` (`:868`). No page-level rotation of any kind.

`OcrEngine` itself is only `Context`-bound: `class OcrEngine(private val context: Context)` (`:42`), constructed as `ocrEngine = OcrEngine(this)` in `OcrAccessibilityService.onCreate` (`OcrAccessibilityService.kt:153`). Nothing in the engine touches WindowManager, accessibility, or display metrics.

---

## 2. What `recognizeStreaming` / `computeCharBoxes` require beyond det output

`recognizeStreaming(bitmap, lineBoxes, onLinesRecognized)` takes a `Bitmap` plus `List<JpDictRect>` **in that bitmap's pixel space** — crops are clamped to the bitmap: `OcrEngine.kt:947-950`
`val cropW = minOf(bitmap.width - cropX, job.bbox.width()).coerceAtLeast(1)`. Boxes from `detect(bitmap)` are already in the same space by construction (`:479-488`).

`computeCharBoxes(...)` (`OcrEngine.kt:1977`) signature:
```
text, charCols: FloatArray, seqLenTotal: Int,
cropX: Int, cropY: Int, cropW: Int, cropH: Int,
isVertical: Boolean, pixels: IntArray? = null, pixW: Int = 0, pixH: Int = 0
```
- `charCols` = CTC timestep column per emitted char (floats); `seqLenTotal` = total timesteps (`:1979-1981`).
- Horizontal: `val avgColW = cropW.toFloat() / seqLenTotal.toFloat()` (`:1993`); cells are crop-local `0..cropW`; output at `:2012-2017`
  `JpDictRect((cropX + xl).roundToInt(), cropY, (cropX + xr).roundToInt(), cropY + cropH)`.
- Vertical: `val avgColW = cropH.toFloat() / seqLenTotal.toFloat()` (`:2024`); output at `:2077-2083`
  `JpDictRect(cropX, (cropY + yt).roundToInt(), cropX + cropW, (cropY + yt + ch).roundToInt())`.
- Optional ink-snapping pixels are **crop-local** and only used when `BOX_LAYOUT_MODE == BOX_SNAP`: `:1998-2000`, and the caller fills them from the crop (`:984-993`, `pixels.size == pixW*pixH` checked at `:1860`).

Called from `processOneBatch` with the absolute crop origin (`:1017-1023`):
`computeCharBoxes(recText, result.charCols, result.seqLenTotal, job.bbox.left, job.bbox.top, job.bbox.width(), job.bbox.height(), job.isVertical, snapPx, snapW, snapH)`.

**No display metrics, no window offsets, no status/navigation-bar compensation** appear in either function. Coordinates are screen pixels *only because* the input bitmap is the full-display screenshot and `detect` maps letterboxed output back to that bitmap. This is stated explicitly for the status strip at `OcrAccessibilityService.kt:370-373`:
> "All display-only: OCR box coordinates are never shifted, so hit-testing cannot desync."

`statusBarHeightPx()` (`:2374`) and the strip fade (`createOverlayDisplayBitmap`, `:2393`) are display-only and never touch box geometry.

---

## 3. How the results popup is rendered, and its coupling to the service

**Everything is built programmatically inside `OcrAccessibilityService`, with `this` as the `Context`.** There is no extracted renderer class.

Overlay root + window:
- `showScreenshotOverlay` (`:353-888`) constructs the root as an anonymous `FrameLayout` (`:395-464`), adds `StatusStripScrimView` (`:469`), `content_container` (`:477`), the screenshot `ImageView` (`:487`), debug text/progress (`:671-697`), confidence controls (`:714`).
- Window params: `:359-368` `type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`, `MATCH_PARENT`; added at `:732` `windowManager?.addView(screenshotOverlay, params)`.

Line boxes / glyphs:
- `addLineToResults` (`:890`) builds one `LineOverlayView` per line (`:923-930`), positioned **directly** with `FrameLayout.LayoutParams(leftMargin = lineLeft - m, topMargin = lineTop - m)` (`:927-930`); detection boxes also placed as plain border views with `leftMargin = box.left; topMargin = box.top` (`:812-820`).

Dictionary popup:
- `showResultsUi` (`:1136`) builds `correction_ui_root` `LinearLayout` (`:1189`) holding the dictionary panel (`updateDictionaryPanel` `:1219` → `renderHeadwordSection` `:1321`, `renderSensesForReading` `:1391`, `renderSenseGroup` `:1903`, `renderDefinition` `:1947`, ruby/flow helpers `:1731-1902`), the correction/neighbour panel (`createCorrectionPanel` `:1440`), the alternatives panel (`toggleAlternativesPanel` `:1497`, `updateAlternativesPanelContent` `:1635`, `refreshCandidateList` `:1595`) and the manual IME (`showManualInput` `:1699`).

Tap handling:
- Character taps: `LineOverlayView.onTouchEvent` (`LineOverlayView.kt:197-266`) fires `onCharClick(idx)` → the callback wired at `:923-925` → `performLookup` (`:1053`).
- Neighbour chips / alternatives: `setOnClickListener`s in `fillLineNeighborContainer` (`:1022`) and the alternatives-panel builders.

**Service-only bindings (a bar to reuse in an Activity):**
- Window type `TYPE_ACCESSIBILITY_OVERLAY` (`:360`) and `windowManager` from `getSystemService(WINDOW_SERVICE)` (`:215`) — no window of that type can be added by an Activity.
- The render helpers all reach for service fields: `screenshotOverlay` (`:706`, `:848`, `:951`, `:2035`, `:2091`, `:2418`…), `screenshotBitmap` (`:1515`, `:1700`), `textViews`/`lineViews` (`:134-135`).
- Top-level add/remove of the overlay: `:732` and `hideScreenshotOverlay` (`:2412`).
- Back: `registerBackCallback` uses `root.findOnBackInvokedDispatcher()` (`:2169`) plus the root's view-tree `dispatchKeyEvent` (`:405-411`), because *back is not routed to a `TYPE_ACCESSIBILITY_OVERLAY` window* (`:2158-2163`).
- Coroutine scope `serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())` (`:87`) runs the OCR job (`:797`), lookups (`:1092`) and key-repeat (`:2232`).
- Screen-state `BroadcastReceiver` (`:107-132`) and `onAccessibilityEvent` auto-dismiss on another app's full-screen window (`:2434-2449`).
- Manual IME: `getSystemService(INPUT_METHOD_SERVICE)` and `rootLayout.windowToken` (`:1719`, `:1728`).
- Sizing reads `resources.displayMetrics` and `rootLayout.width/height` (`:1001-1002`, `:1137-1138`, `:1441-1442`, `:1509-1510`, `:1599-1600`).

**What an Activity would need to host the same UI:** a full-screen `FrameLayout` as the content view to play the role of `screenshotOverlay` (tag lookups depend on it), a source `Bitmap` for crop previews, a Main-dispatcher scope, and non-service back handling. The ~1000 lines of private builder methods (`showResultsUi` and everything it calls) are not separable without being lifted into a Context/View-agnostic renderer. See the column summary below.

---

## 4. What `OcrOverlayStateController` owns, and its coupling

Plain Kotlin class, no Android service dependency: `class OcrOverlayStateController` (`OcrOverlayStateController.kt:135`), no constructor args; injected collaborators are `deinflector`, `dictionaryProvider`, `gson` (`:149-151`).

Owns:
- View-transform state: `currentScale`, `currentTransX`, `currentTransY` (`:153-155`).
- Selection/tap state: `currentTappedIdx`, `currentTappedLineIdx`, `currentTappedCharIdxInLine` (`:208-210`), `lastHighlightedCoords` (`:214`), `lastNeighborHighlightedLine/Char` (`:219-220`), `navGraph` built from char boxes (`:238-247`), `lastLandscapeGravity`/`lastPortraitGravity` (`:216-217`).
- OCR results: `activeLineBoxes` (`:204`), `activeLineResults: MutableList<LineResult?>` (`:212`), `activeAllChars`/`activeAllAlternatives` (`:205-206`); `updateGlobalData`/`rebuildNavGraph` (`:226-248`).
- Lookup: `suspend fun lookup(lineIdx, charIdx): Result?` (`:403`) — deinflect via `prepareSearchCandidates` (`:734`), DB via `provider.findByTexts` (`:424`), `processResults` (`:772`), JMdict redirect BFS (`:430-462`), per-kanji second pass (`:488-511`).
- Dictionary formatting: `formatDictionaryResults` (`:895`) → `FormattedEntry`/`FormattedReadingGroup`/`FormattedSenseGroup`/`FormattedSense`/`DefinitionNode`; `parseDefinition` (`:1030`). Pure data, no views.
- Alternatives: `getAlternativesUiState` (`:535`), `alternativeCharsFor` (`:554`), `gapCandidates` (`:578`), `refreshLinesWithThreshold` (`:161`, calls `OcrEngine.reDecodeLineResult`), `isBlankAt`/`selectBlankPosition` (`:388-401`).
- Navigation maths: `navigate`/`navigateLines`/`ensureCursorPosition`/`updateGravity`/`centerOnCharacter`/`isNearCharacter` (`:299-732`), `calculateDisplayBoxes` (`:847`), gamepad action mapping (`:813-836`).
- Lifecycle: `resetState()` (`:270`).
- Late injection: `installOovSuggestions` (`:625`), `installCharLm` (`:635`).

**Coupling:** none to service APIs. It references `JpDictRect` (`SystemSupport.kt:12`), `OcrEngine.GAP_CHAR` (`:389`, `:411`, `:561`), the `uniffi.nav_graph_core` binding, and `util/*` helpers. It is instantiated as a plain field in the service (`OcrAccessibilityService.kt:85`) and configured in `onCreate` (`:154-156`) and `onServiceConnected`-era loading (`:176-192`). Transferable to an Activity as-is.

---

## 5. Camera-specific hazards

- **No camera code exists.** `README.md:20` `- [ ] Camera mode`; no `CameraX`/`ImageCapture`/`ExifInterface` reference anywhere in `app/src/main`.
- **Lighting/EXIF:** no `ExifInterface` use anywhere in the repo. `detect`/`recognizeStreaming` handle line orientation only (270° rotate for tall crops, `OcrEngine.kt:1087-1090`); a photo whose *page* is rotated 90° reaches the detector as vertical lines and is processed as tategaki. Screenshots never need EXIF; photos do.
- **Colour space / config:** the only capture path forces `Bitmap.Config.ARGB_8888` via `wrapHardwareBuffer(...).copy(...)` (`OcrAccessibilityService.kt:337-338`). `detect`/rec read pixels with `getPixels`/`createBitmap` and would fail on a hardware bitmap; there is no colour-space conversion — R/G/B bytes feed the ImageNet LUT raw (`OcrEngine.kt:359-361`).
- **Full-resolution memory:** the engine rescales for the net (`detect` longest side ≤ 896, `:327-331`) but the *source* bitmap stays resident and is cropped per batch. A full-res ARGB_8888 photo (e.g. 4000×3000 ≈ 48 MB) plus model buffers: det letterbox `896²` ARGB (~3.1 MB) + `3*896*896` float array (~9.2 MB), pooled per-thread (`:240-242`, `:334-357`). `REC_BATCH_SIZE = 4` crops pinned per batch (`OcrEngine.kt:173`, `:2134`, `:945`). `detect` recycles its own `resized` (`:342`) but **never the caller's source bitmap**; `inferResizedRec` documents "does NOT recycle [src]" (`:857`). The overlay clears `screenshotBitmap = null` on close (`OcrAccessibilityService.kt:2422`) rather than recycling it, so an Activity must own its source bitmap's lifetime.
- **Threading:** `detect` is run on `Dispatchers.IO` (`:803`); `recognizeStreaming` is a `suspend` fn whose per-crop inference runs on `Dispatchers.Default` (`OcrEngine.kt:1081`) and whose callbacks are posted back to the main `Handler` (`:2131`, `:1045`). The service's calling scope is `Dispatchers.Main` (`OcrAccessibilityService.kt:87`). A camera path needs the equivalent Main+IO structure; the engine itself is thread-agnostic but not safe to call concurrently on one instance.
- **Coordinates relative to a view:** in the overlay, box coordinates equal *display* pixels because the root window is `MATCH_PARENT` with `FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS` (`:362-363`) at the display origin, and `box.left`/`box.top` are used directly as `FrameLayout` margins (`:816-819`, `:927-930`). An Activity's content view is inset by the system bars and does not generally share the bitmap's origin, so an Activity-hosted overlay must map bitmap (display) pixels into view pixels — unless the photo is displayed in a view whose origin and scale coincide with the bitmap. This is the concrete "coordinates are interpreted relative to a view" bite. `LineOverlayView` internally subtracts `lineLeft`/`lineTop` and adds its ink margin (`LineOverlayView.kt:94`, `:122-123`), so it is reusable given correct placement.
- **`isNearCharacter`/tap hit-testing** assume the same display-pixel transform (scale + translation) as the drawing (`OcrOverlayStateController.kt:719-732`, density at `OcrAccessibilityService.kt:452`); preserving the transform model keeps them consistent.

---

## Summary: reusable as-is vs needs a new seam

| Reusable as-is | Needs a new seam |
|---|---|
| `OcrEngine` — `detect`, `recognizeStreaming`, `computeCharBoxes`, `reDecodeLineResult`; only needs a `Context` (`OcrEngine.kt:42`, `:317`, `:2103`, `:1977`) | Screen capture: `triggerCapture`/`takeScreenshot` are `AccessibilityService`-only (`OcrAccessibilityService.kt:331-351`); camera needs `CameraX`/`ImageCapture` or a picked file |
| `OcrOverlayStateController` — lookup, dictionary formatting, selection, nav, gamepad mapping; no service APIs (`OcrOverlayStateController.kt:135`) | Overlay window creation: `TYPE_ACCESSIBILITY_OVERLAY` + `windowManager.addView` (`:360`, `:732`) → Activity content view / `setContentView` |
| `LineOverlayView` — a plain `View` drawing glyphs from `charBoxes` + hit-testing (`LineOverlayView.kt:16-267`) | The popup builders `showResultsUi` and its callees (`:1136-2035`) are service-private methods using `this` as Context and reaching for `screenshotOverlay`/`screenshotBitmap` fields — need extracting into a Context+root-View renderer |
| `SystemSupport` (`JpDictRect`, `AndroidDictionaryProvider`, gravity/keycode helpers), `TapDisambiguator`, `DoubleTapZoom`, `OverlayBackdrop`, all `util/*` | Back handling: `registerBackCallback` + view-tree `dispatchKeyEvent` (`:405-411`, `:2167`) → Activity `onBackPressed`/`OnBackInvokedCallback` |
| `AndroidDictionaryProvider` (Room) — `SystemSupport.kt:84` | Job scoping: `serviceScope` (`:87`, `:797`, `:1092`) → `lifecycleScope` |
| The bundle of injected collaborators set in `onCreate` (`:154-156`, `:176-192`) | Screen-state receiver / `onAccessibilityEvent` auto-dismiss (`:107-132`, `:2434`) → Activity lifecycle equivalents |
| Coordinate→view placement *model* (display pixels used directly) | Coordinate mapping when the Activity content view is inset/offset from the bitmap origin (see Q5) |
| | EXIF orientation handling for camera photos (absent today) |
