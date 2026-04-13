# Nano Solver — Android Overlay Auto-Solver for Matiks

## Context

Matiks (matiks.in) is a competitive math game with time-critical modes (Sprint: most answers in 60s, Fast & First: first to answer wins). The goal is to build an Android overlay app that perceives the screen, extracts the math problem, solves it, and inputs the answer — all within a few hundred milliseconds. This is fundamentally a **perception-action loop** problem.

The user wants to both build the best-performing solver AND deeply understand every technical decision.

---

## Architecture: The Pipelined Perception-Action Loop

The naive loop is sequential: Capture → OCR → Solve → Input → repeat.  
The optimized loop is **pipelined**: while submitting answer N, start capturing for problem N+1.

```
Frame t:   [Capture] → [OCR] → [Solve] → [Input]
Frame t+1:            [Capture] → [OCR] → [Solve] → [Input]
```

This overlap cuts effective latency nearly in half. It's the same principle used in CPU instruction pipelining.

### The 4 Stages

**Stage 1 — Perceive (Screen Capture)**
- **API**: `android.media.projection.MediaProjection`
- **Why not Accessibility for capture?** AccessibilityService reads the *view hierarchy* (XML-like tree), not pixels. Games often render via Canvas/OpenGL where the hierarchy has no meaningful text nodes. MediaProjection captures the raw GPU framebuffer — always correct.
- **Key class**: `ImageReader` with `ACQUIRE_LATEST_IMAGE` strategy — drop stale frames instead of queuing them.
- **Optimization**: Don't capture full screen. Define a `Rect` bounding box around the known question area. This reduces pixel data by ~60–80%.

**Stage 2 — Extract (OCR)**
- **Engine**: Google ML Kit Text Recognition v2 (`com.google.mlkit:text-recognition:16.x`)
- **Why ML Kit over Tesseract?** ML Kit runs a neural model on-device via TFLite, optimized for Android's Neural Networks API (NNAPI) which uses the device's DSP/NPU. Tesseract is a classical LSTM — slower (~800ms) and less accurate on stylized game fonts.
- **Why ML Kit over PaddleOCR?** ML Kit is a production SDK with zero setup. PaddleOCR needs model bundling and a custom inference pipeline. Start with ML Kit; PaddleOCR is an upgrade path if you need fine-tuned accuracy.
- **Image preprocessing pipeline** (before OCR, done in C++ via RenderScript or CameraX):
  1. Crop to question Rect
  2. Grayscale conversion (halves data)
  3. Binary threshold (makes digits stark black-on-white — OCR loves this)
- **Expected latency**: ~50–120ms on a mid-range device with preprocessing.

**Stage 3 — Solve (Math Parser)**
- **Language**: Kotlin
- **Approach**: Write a minimal recursive descent parser, NOT `eval()` or a regex split.
- **Why a custom parser?** It's ~30 lines of Kotlin, executes in <0.1ms, and you'll learn one of the most fundamental CS algorithms. A regex split breaks on multi-digit numbers and negative signs. `eval()` doesn't exist in Kotlin/Java without a script engine (slow).
- **What it handles**: `+`, `-`, `×`/`*`, `÷`/`/`, parentheses, negative numbers, decimal results.
- **Caching**: Use a `HashMap<String, Long>` keyed by the raw problem string. Cache hit = 0ms solve time.

**Stage 4 — Act (Input Injection)**
- **API**: `android.accessibilityservice.AccessibilityService` with `performAction()`
- **Why not root-based injection?** Root requires a custom ROM or bootloader unlock — eliminates 95% of users. AccessibilityService is a first-class Android API that works on all devices.
- **Input strategy**:
  1. Find the answer `EditText` node via `findAccessibilityNodeInfosByViewId()` or by traversing the window's node tree.
  2. Set text via `ACTION_SET_TEXT` bundle (instant, no keystroke simulation needed).
  3. Find and click the submit button via `ACTION_CLICK`.
- **Fallback**: If Matiks uses a custom-rendered input (canvas-based), fall back to `GestureDescription` to simulate taps at known coordinates.

---

## Complete Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Language | **Kotlin** | Coroutines for async, concise syntax, null safety |
| Screen capture | **MediaProjection API** | Raw framebuffer access, no root, fastest option |
| Frame buffering | **ImageReader (ACQUIRE_LATEST_IMAGE)** | Always work on the freshest frame, never queue stale ones |
| Image preprocessing | **Android Bitmap API + RenderScript** | GPU-accelerated grayscale + threshold in <5ms |
| OCR | **Google ML Kit Text Recognition v2** | On-device, NNAPI-accelerated, offline, 50-120ms |
| Math solver | **Custom Kotlin recursive descent parser** | <0.1ms, handles all operators, no dependencies |
| Answer caching | **HashMap<String, Long>** | O(1) lookup for repeated problems |
| Input injection | **AccessibilityService (ACTION_SET_TEXT + ACTION_CLICK)** | Non-root, reliable, works on all Android versions |
| Overlay UI | **WindowManager TYPE_ACCESSIBILITY_OVERLAY** | Always on top, works over other apps, no permission dialog |
| Async pipeline | **Kotlin Coroutines (Dispatchers.Default)** | Non-blocking OCR + solve off the main thread |
| Build | **Gradle + Android SDK 26+** | MediaProjection stable from API 21, NNAPI from API 27 |

---

## Project Structure

```
nano-solver/
├── app/src/main/
│   ├── service/
│   │   ├── NanoAccessibilityService.kt   ← Stage 4: input injection
│   │   └── OverlayService.kt             ← Overlay window management
│   ├── capture/
│   │   └── ScreenCaptureManager.kt       ← Stage 1: MediaProjection + ImageReader
│   ├── ocr/
│   │   ├── TextExtractor.kt              ← Stage 2: ML Kit wrapper
│   │   └── ImagePreprocessor.kt          ← Grayscale + threshold
│   ├── solver/
│   │   ├── MathParser.kt                 ← Stage 3: recursive descent parser
│   │   └── SolverCache.kt                ← HashMap cache
│   ├── pipeline/
│   │   └── SolverPipeline.kt             ← Orchestrates all 4 stages + pipelining
│   └── ui/
│       └── OverlayView.kt                ← Minimal overlay HUD
├── AndroidManifest.xml                   ← SYSTEM_ALERT_WINDOW + FOREGROUND_SERVICE permissions
└── build.gradle
```

---

## Implementation Phases

### Phase 1 — Permissions & Skeleton (Day 1)
- Set up Android project with target API 27+
- Request permissions: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `RECORD_AUDIO` (not needed but MediaProjection needs foreground service)
- Create `OverlayService` that shows a floating "Start" button via `WindowManager`
- Learn: Android service lifecycle, `WindowManager.LayoutParams`, permission model

### Phase 2 — Screen Capture (Day 2)
- Implement `ScreenCaptureManager` using `MediaProjectionManager.createScreenCaptureIntent()`
- Set up `VirtualDisplay` → `ImageReader` pipeline
- Log captured frame dimensions and FPS
- Learn: `MediaProjection`, `VirtualDisplay`, `ImageReader`, `Image.Plane` byte buffers

### Phase 3 — OCR Pipeline (Day 3)
- Implement `ImagePreprocessor`: `Bitmap` → grayscale → binary threshold
- Implement `TextExtractor`: wrap ML Kit's `TextRecognizer`
- Test: hardcode a screenshot of Matiks, verify extracted string matches
- Learn: `InputImage`, ML Kit async API, `Task<T>` callbacks vs `await()` with coroutines

### Phase 4 — Math Solver (Day 4)
- Implement `MathParser` as a recursive descent parser
- Handle: integer arithmetic, `×`/`÷` symbols (Matiks may use Unicode), operator precedence
- Unit test with 50+ cases
- Learn: tokenization, AST concepts, operator precedence via grammar rules

### Phase 5 — Input Injection (Day 5)
- Implement `NanoAccessibilityService`
- Traverse window node tree to find the answer input field in Matiks
- Use `ACTION_SET_TEXT` then `ACTION_CLICK` on submit
- Test with a simple form app first
- Learn: `AccessibilityNodeInfo`, view hierarchy, `AccessibilityService` event types

### Phase 6 — Pipeline Integration (Day 6)
- Wire all stages together in `SolverPipeline`
- Implement pipelining: launch next capture coroutine while submit is in-flight
- Add `SolverCache` for repeated problems
- Measure end-to-end latency with `System.nanoTime()` logs at each stage boundary

### Phase 7 — Tuning (Day 7+)
- Add targeted capture rect (crop to question area only)
- Profile with Android Studio CPU Profiler — find the bottleneck stage
- If OCR is the bottleneck: try preprocessing improvements or PaddleOCR
- If input is the bottleneck: pre-cache the node reference, don't re-find it every frame

---

## Latency Budget (Target: <300ms total)

| Stage | Target | Key Lever |
|---|---|---|
| Screen capture | <5ms | `ACQUIRE_LATEST_IMAGE`, targeted crop |
| Image preprocessing | <5ms | RenderScript GPU pipeline |
| OCR (ML Kit) | <120ms | Preprocessing quality, smaller crop |
| Math solve | <1ms | Custom parser, cache |
| Input injection | <50ms | Cache node reference |
| **Total** | **<181ms** | Pipelining hides most of this |

With pipelining, the **perceived latency** (from problem appearing to answer submitted) approaches the longest single-stage time (~120ms OCR), not the sum.

---

## Key Android Permissions Required

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
```

---

## Verification / Testing Plan

1. **Unit tests**: `MathParser` — 50 cases covering all operators, edge cases, Unicode symbols
2. **OCR accuracy test**: Run `TextExtractor` on 20 screenshots of Matiks questions, check match rate
3. **Latency measurement**: Instrument each stage with `System.nanoTime()`, log to Logcat, build a histogram
4. **End-to-end test**: Run against Matiks Sprint mode, count problems solved in 60s vs manually
5. **Stress test**: Run pipeline continuously for 5 minutes, watch for memory leaks via Android Profiler (ImageReader buffers must be explicitly closed)

---

## Modifications from metadata.md

| metadata.md says | This plan changes | Why |
|---|---|---|
| "Use coroutines or RxJava" | Coroutines only | RxJava is heavyweight; coroutines are idiomatic Kotlin and have less overhead |
| No mention of pipelining | Added explicit pipeline overlap | This is the single biggest latency win |
| "Image preprocessing" mentioned briefly | Full preprocessing pipeline specified | Binary thresholding is critical for ML Kit accuracy on colored game UIs |
| No project structure | Full module breakdown added | Helps map each concept to a file during learning |
| No latency budget | Added per-stage targets | Forces you to measure, not guess |
| AutoGod framework mentioned | Dropped | Adds a dependency for no gain; AccessibilityService directly is better for learning |