# Quick Build corpus matrix results

Generated: 2026-07-18T01:51:10.076348+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.20/ed866de74ad3d49086a27bbd75952cd186479436/kotlin-stdlib-2.3.20.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/adfa-4128-quick-build/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 1, Edits: 3
- Passed: 3, Failed: 0, Skipped: 0

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (1841ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=269ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=234ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=227ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
