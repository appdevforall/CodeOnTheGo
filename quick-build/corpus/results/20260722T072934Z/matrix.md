# Quick Build corpus matrix results

Generated: 2026-07-22T07:29:34.680271+00:00

## Config

- **daemonJar**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Library/Android/sdk/platforms/android-36.1/android.jar
- **kotlinStdlib**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Library/Android/sdk/build-tools/37.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Library/Android/sdk/build-tools/37.0.0/lib/d8.jar
- **composePluginJar**: /Users/bryanchan/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.3.0/f375396c5723812604a2a3bce1d27d3bd56a16c9/kotlin-compose-compiler-plugin-embeddable-2.3.0.jar
- **composeRuntimeJar**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/quickbuild-daemon/build/compose-test-runtime/compose-runtime.jar
- **minApi**: 30

## Summary

- Apps: 13, Edits: 48
- Passed: 48, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (2224ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=191ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=130ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (627ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=214ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=203ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (422ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=173ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=132ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=51ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (228ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=57ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=68ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=76ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (283ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=46ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=76ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=43ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=47ms, relink=79ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (586ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=55ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=111ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=154ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=78ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (451ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=98ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=224ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=244ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=85ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (325ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=46ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=143ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=138ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=294ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=128ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=72ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (167ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=52ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (238ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=63ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=82ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=154ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=79ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (181ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=80ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=85ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=79ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=165ms, relink=76ms, reconfigure=733ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (310ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=94ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=135ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=164ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=86ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (209ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=107ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=106ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=101ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)
