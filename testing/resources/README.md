# Test resources

This directory contains resources for unit tests related to the tooling API server, the projects API and LSP modules.

- `test-project`: A basic project used for testing almost everything related to the tooling API
  (multi-module support, build cancellations, dependency & task resolutions, model builders, etc.).
  This project is not buildable i.e. running `assembleDebug` in this project will fail.
- `sample-project`: A basic project which can be built, used in some rare cases (like testing configuration cache support).
- `kotlin-debug-fixture`: A single Kotlin source file used as the debuggee for the Kotlin debugger
  work (ADFA-4175). It is copied into a project made from CoGo's Kotlin template rather than built
  here; see its own README for the line map and known limitations.
