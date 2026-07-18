# Quick Build corpus matrix results

Generated: 2026-07-17T06:19:43.729244+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/libs/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.20/ed866de74ad3d49086a27bbd75952cd186479436/kotlin-stdlib-2.3.20.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/CodeOnTheGo/.claude/worktrees/wf_11d33599-42a-7/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **minApi**: 30

## Summary

- Apps: 7, Edits: 26
- Passed: 26, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (2297ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=176ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=119ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (459ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=174ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=160ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=47ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (208ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=49ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=46ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=76ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (328ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=101ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=69ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=61ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=42ms, relink=72ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (829ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=74ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=157ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=217ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=79ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (211ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=81ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=172ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=94ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=157ms, relink=73ms, reconfigure=848ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (240ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=61ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=103ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=52ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)
