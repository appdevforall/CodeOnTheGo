# Quick Build corpus matrix results

Generated: 2026-07-22T06:06:47.831013+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /nix/store/dccfc40cyivwlmd4qbl6lbfq4fzjfp95-zulu-ca-jdk-17.0.12/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 1, Edits: 3
- Passed: 3, Failed: 0, Skipped: 0

## streetcomplete-lib (kotlin) -- PASS

Baseline: PASS (5134ms), classes=22

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-collections-most-common | new-api | code | compile | compile=400ms | 1 | PASS |  |
| 02-vector3d-destructuring | method-body | code | compile | compile=349ms | 2 | PASS |  |
| 03-anglemath-radians-float-overload | new-api | code | compile | compile=474ms | 1 | PASS |  |

Output equivalence: PASS -- 23 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
