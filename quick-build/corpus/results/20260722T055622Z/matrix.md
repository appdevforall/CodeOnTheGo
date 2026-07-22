# Quick Build corpus matrix results

Generated: 2026-07-22T05:56:22.277293+00:00

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

- Apps: 1, Edits: 7
- Passed: 7, Failed: 0, Skipped: 0

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (5588ms), classes=17

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=688ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=549ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=493ms | 2 | PASS |  |
| 04-charposition-copy-ctor | ctor-add | code | compile | compile=383ms | 1 | PASS |  |
| 05-intpair-swap | new-api | code | compile | compile=415ms | 1 | PASS |  |
| 06-textbidi-explicit-direction | cross-file-api-add | code | compile | compile=296ms | 1 | PASS |  |
| 07-stringlatin1-appendto-perf | method-body | code | compile | compile=431ms | 1 | PASS |  |

Output equivalence: PASS -- 17 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
