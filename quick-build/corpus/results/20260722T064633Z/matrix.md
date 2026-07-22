# Quick Build corpus matrix results

Generated: 2026-07-22T06:46:33.882231+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-d-mixedkj/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.3.0/f375396c5723812604a2a3bce1d27d3bd56a16c9/kotlin-compose-compiler-plugin-embeddable-2.3.0.jar
- **composeRuntimeJar**: quickbuild-daemon/build/compose-test-runtime/compose-runtime.jar
- **minApi**: 30

## Summary

- Apps: 15, Edits: 56
- Passed: 56, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (7470ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=702ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=242ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (2700ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=1349ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=1136ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (1209ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=978ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=853ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=209ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (1029ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=192ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=174ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=416ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (1166ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=229ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=280ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=292ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=328ms, relink=217ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (1957ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=260ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=283ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=418ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=422ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (1494ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=203ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=423ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=107ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=100ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## mixed-lang-cyclic (mixed) -- PASS

Baseline: PASS (1073ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-kotlin-method-body | method-body | code | compile | compile=436ms | 1 | PASS |  |
| 02-java-method-body | java-method-body | code | compile | compile=73ms | 1 | PASS |  |
| 03-java-signature-change | java-signature-change | code | compile | compile=406ms | 4 | PASS |  |
| 04-kotlin-signature-change | kotlin-signature-change | code | compile | compile=822ms | 4 | PASS |  |
| 05-resource-value | resource-value | resources | relink | relink=173ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (1478ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=265ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=422ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=369ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=1005ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=659ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=319ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (903ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=223ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (583ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=148ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=178ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=532ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=112ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (438ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=127ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=102ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=90ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=404ms, relink=97ms, reconfigure=2039ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (1171ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=249ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=670ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=589ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=206ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## sora-editor-full (mixed) -- PASS

Baseline: PASS (18529ms), classes=457

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-java-body-edit | java-method-body | code | compile | compile=1880ms | 1 | PASS |  |
| 02-kotlin-body-edit | method-body | code | compile | compile=2913ms | 1 | PASS |  |
| 03-java-abi-change | java-abi-change | code | compile | compile=9182ms | 1 | PASS |  |

Output equivalence: PASS -- 457 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (694ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=566ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=405ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=403ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)
