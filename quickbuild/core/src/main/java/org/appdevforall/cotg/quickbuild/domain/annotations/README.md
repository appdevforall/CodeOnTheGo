# `domain/annotations/` - does a change feed an annotation processor

Decides whether a code edit could have moved annotation-processor (KSP/kapt) output, so the classifier knows when the live reload path must give way to a full Gradle rebaseline. Compares each changed file against a baseline captured from the proxy app build, using a text-only scan (no compiler front-end) that is deliberately over-inclusive: "not sure" means rebaseline. Pure JVM; no Android.

| File | Purpose |
| --- | --- |
| [`AnnotationImpact.kt`](AnnotationImpact.kt) | The `AnnotationImpact` interface (plus `Inactive`, `SwitchableAnnotationImpact`, and the real `AnnotationImpactAnalyzer`) that maps changed code files to a rebaseline reason or null. |
| [`AnnotationBaseline.kt`](AnnotationBaseline.kt) | The proxy app build's scanned source set (per-file facts plus anchor type names) that every later edit is compared against. |
| [`AnnotationProcessorProfile.kt`](AnnotationProcessorProfile.kt) | Which annotations count as processor input, derived from the reported processor coordinates; recognizes Room/Hilt/Moshi/Glide/AutoValue, turns conservative on any unrecognized processor. |
| [`SourceAnnotationScanner.kt`](SourceAnnotationScanner.kt) | Extracts `AnnotationFacts` from Kotlin/Java text without a parser; strips comments, masks string literals, fingerprints the declaration surface, excludes function bodies. |
| [`AnnotationFacts.kt`](AnnotationFacts.kt) | The value types the scanner produces: `AnnotationFacts` (package, imports, annotations, declared/referenced type names, declaration fingerprint) and `AnnotationUse`. |
