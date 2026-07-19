# Quick Build corpus matrix results

Generated: 2026-07-19T18:13:49.672960+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/CodeOnTheGo/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.3.0/f375396c5723812604a2a3bce1d27d3bd56a16c9/kotlin-compose-compiler-plugin-embeddable-2.3.0.jar
- **composeRuntimeJar**: quickbuild-daemon/build/compose-test-runtime/compose-runtime.jar
- **minApi**: 30

## Summary

- Apps: 11, Edits: 40
- Passed: 40, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (2292ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=176ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=140ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (611ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=222ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=221ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (533ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=194ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=153ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=76ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (227ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=58ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=61ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=83ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (302ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=52ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=74ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=47ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=46ms, relink=73ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (537ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=49ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=117ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=160ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=73ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (451ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=68ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=190ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=215ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=76ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (385ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=53ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=176ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=133ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=277ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=135ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=71ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (240ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=45ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (181ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=75ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=80ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=76ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=131ms, relink=80ms, reconfigure=750ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (206ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=101ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=90ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=84ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)
