# Quick Build corpus matrix results

Generated: 2026-07-22T05:57:35.367034+00:00

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

- Apps: 13, Edits: 52
- Passed: 52, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (3339ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=219ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=153ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (1513ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=479ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=699ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (1073ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=669ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=546ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=146ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (801ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=191ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=165ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=260ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (678ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=134ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=132ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=205ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=197ms, relink=146ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (884ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=65ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=397ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=579ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=120ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (724ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=126ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=278ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=265ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=82ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (1354ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=157ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=437ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=422ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=904ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=445ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=244ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (245ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=42ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (327ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=78ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=98ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=202ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=79ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (598ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=133ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=84ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=106ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=496ms, relink=105ms, reconfigure=2060ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (1329ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=311ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=711ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=706ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=206ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (888ms), classes=17

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=293ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=259ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=235ms | 2 | PASS |  |
| 04-charposition-copy-ctor | ctor-add | code | compile | compile=343ms | 1 | PASS |  |
| 05-intpair-swap | new-api | code | compile | compile=427ms | 1 | PASS |  |
| 06-textbidi-explicit-direction | cross-file-api-add | code | compile | compile=282ms | 1 | PASS |  |
| 07-stringlatin1-appendto-perf | method-body | code | compile | compile=375ms | 1 | PASS |  |

Output equivalence: PASS -- 17 classes match (set + CRC)
