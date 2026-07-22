# Quick Build corpus matrix results

Generated: 2026-07-22T06:09:31.057295+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /nix/store/dccfc40cyivwlmd4qbl6lbfq4fzjfp95-zulu-ca-jdk-17.0.12/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.3.0/f375396c5723812604a2a3bce1d27d3bd56a16c9/kotlin-compose-compiler-plugin-embeddable-2.3.0.jar
- **composeRuntimeJar**: quickbuild-daemon/build/compose-test-runtime/compose-runtime.jar
- **minApi**: 30

## Summary

- Apps: 14, Edits: 55
- Passed: 55, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (11845ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=1319ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=1194ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (37036ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=16357ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=18857ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (3035ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=1720ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=1357ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=572ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (2859ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=2147ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=354ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=1217ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (1840ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=541ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=3411ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=1651ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=1037ms, relink=1048ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (3058ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=294ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=586ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=784ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=342ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (4569ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=825ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=1139ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=919ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=183ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (6275ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1130ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=4840ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=5856ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=4663ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=7806ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=2082ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (1157ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=261ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (673ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=569ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=707ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=662ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=135ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (827ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=289ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=276ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=151ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=1034ms, relink=165ms, reconfigure=3109ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (1451ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=1435ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=1951ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=1148ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=106ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (2602ms), classes=17

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=599ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=801ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=465ms | 2 | PASS |  |
| 04-charposition-copy-ctor | ctor-add | code | compile | compile=231ms | 1 | PASS |  |
| 05-intpair-swap | new-api | code | compile | compile=164ms | 1 | PASS |  |
| 06-textbidi-explicit-direction | cross-file-api-add | code | compile | compile=179ms | 1 | PASS |  |
| 07-stringlatin1-appendto-perf | method-body | code | compile | compile=171ms | 1 | PASS |  |

Output equivalence: PASS -- 17 classes match (set + CRC)

## streetcomplete-lib (kotlin) -- PASS

Baseline: PASS (2554ms), classes=22

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-collections-most-common | new-api | code | compile | compile=406ms | 1 | PASS |  |
| 02-vector3d-destructuring | method-body | code | compile | compile=344ms | 2 | PASS |  |
| 03-anglemath-radians-float-overload | new-api | code | compile | compile=407ms | 1 | PASS |  |

Output equivalence: PASS -- 23 classes match (set + CRC)
