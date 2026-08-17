# `domain/classify/` - which build route a change takes

Picks the cheapest still-correct build path for a coalesced changed-set, and names why a session baseline stops being trustworthy. Classification is by path shape, not file content (the one content-aware step is delegated to `domain/annotations/`). Pure logic, unit-testable without a project on disk.

| File | Purpose |
| --- | --- |
| [`BuildRoute.kt`](BuildRoute.kt) | The route types (`FullGradleBuild`, `ResourcesOnly`, `AssetsOnly`, `CodeOnly`, `CodeAndResources`, `NoOp`, `WarmCompile`), the `recompilesCode` flag, and the `InvalidationReason` enum of why a baseline needs a full Gradle rebuild. |
| [`ChangeClassifier.kt`](ChangeClassifier.kt) | Routes a changed-set: manifest/Gradle-config/unsupported/non-app-module changes force a full build, otherwise splits code/resource/asset into the cheapest route; also exposes path-shape helpers (`hasRecognizedShape`, `namesResource`). |
