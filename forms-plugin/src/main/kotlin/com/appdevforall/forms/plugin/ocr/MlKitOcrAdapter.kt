package com.appdevforall.forms.plugin.ocr

/**
 * **Stub for the follow-up integration commit.** Tonight's deliverable is
 * the pure-JVM heuristic classifier + tests; the actual ML Kit call needs
 * Android instrumentation (Bitmap, Tasks API, Google Play Services), so
 * it lives here as documentation until the next commit wires it in.
 *
 * ## What this class will do
 *
 * Bridge between ML Kit Text Recognition v2 and [HeuristicFieldClassifier].
 * The flow:
 *
 *     Step1CaptureFragment.onPhotoTaken(bitmap)
 *       -> MlKitOcrAdapter.recognize(bitmap): OcrResult
 *       -> HeuristicFieldClassifier.classify(ocrResult): List<FormField>
 *       -> wizardViewModel.replaceFields(fields)
 *
 * ## ML Kit Text Recognition v2 mapping
 *
 * The recognizer returns a `com.google.mlkit.vision.text.Text` whose
 * structure mirrors [OcrResult] one-to-one:
 *
 * | ML Kit `Text`         | Local                |
 * |-----------------------|----------------------|
 * | `Text.text`           | (concatenation)      |
 * | `Text.textBlocks`     | [OcrResult.blocks]   |
 * | `TextBlock.text`      | [OcrBlock.text]      |
 * | `TextBlock.boundingBox` | [OcrBlock.bounds]  |
 * | `TextBlock.lines`     | [OcrBlock.lines]     |
 * | `Line.text`           | [OcrLine.text]       |
 * | `Line.boundingBox`    | [OcrLine.bounds]     |
 * | `Line.elements`       | [OcrLine.elements]   |
 * | `Element.text`        | [OcrElement.text]    |
 * | `Element.boundingBox` | [OcrElement.bounds]  |
 *
 * Conversion is straightforward — drop the `language` / `cornerPoints` /
 * `recognizedLanguage` fields we don't use, take the bounding rect, copy
 * the text, recurse. Skip elements where `boundingBox == null` (rare,
 * reserved for non-Latin scripts ML Kit hasn't fully localized).
 *
 * ## Recognizer choice
 *
 * ML Kit v2 ships separate recognizers per script family:
 *
 * - `LATIN`: English, Spanish, French, Portuguese, German, Vietnamese, …
 * - `DEVANAGARI`: Hindi, Marathi, Sanskrit, …
 * - `CHINESE`: Simplified + Traditional
 * - `JAPANESE`
 * - `KOREAN`
 *
 * For ADFA's NGO partners we'd plumb a script preference through the
 * wizard and pick the matching recognizer + matching [ClassifierLocale].
 * Unsupported scripts (Tamil, Bengali, Amharic, Swahili) need either
 * Tesseract via JNI or a separate model — out of scope for R2.
 *
 * ## Why deferred
 *
 * - Constructing a `Bitmap` requires an Android runtime (or Robolectric).
 * - The recognizer call is a `Task<Text>` — async with Tasks API, not
 *   trivially testable on the JVM.
 * - Google Play Services is mocked-only on Robolectric, and the realistic
 *   coverage we'd want is at the instrumented-test level (real recognizer
 *   on a real device). That's the natural home for fixtures-on-disk
 *   tests in a follow-up `forms-plugin/src/androidTest/...` suite.
 *
 * ## Dependency
 *
 * Adding ML Kit to the forms-plugin needs one line in `build.gradle`:
 *
 *     implementation("com.google.mlkit:text-recognition:16.0.0")
 *
 * Plus, optionally, per-script recognizers:
 *
 *     implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")
 *
 * APK bloat: ~3 MB for Latin alone. The team's existing apk-viewer-plugin
 * already pulls Google Play Services dependencies, so the marginal cost
 * is small.
 *
 * ## Reference
 *
 * https://developers.google.com/ml-kit/vision/text-recognition/v2/android
 *
 * For the wizard wiring side, see how the existing CV path passes the
 * detected-fields list back to the ViewModel:
 * [com.appdevforall.forms.plugin.wizard.Step1CaptureFragment.handleCvResult].
 */
@Suppress("unused", "EmptyClass")
class MlKitOcrAdapter {
    // Intentionally no implementation — this class is a documentation
    // anchor for the follow-up integration commit. Don't construct it.
    // When the integration lands, this file will gain a `recognize(bitmap)`
    // method that returns an [OcrResult].
}
