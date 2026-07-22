# Quick Build corpus matrix results

Generated: 2026-07-22T07:36:20.322954+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-a2-secuso/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 1, Edits: 1
- Passed: 1, Failed: 0, Skipped: 0

## sudoku (java) -- PASS

Baseline: PASS (2986ms), classes=27

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-generator-real | method-body | code | compile | compile=509ms | 1 | PASS |  |

Output equivalence: PASS -- 27 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
