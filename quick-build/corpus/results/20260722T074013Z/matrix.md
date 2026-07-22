# Quick Build corpus matrix results

Generated: 2026-07-22T07:40:13.203170+00:00

## Config

- **daemonJar**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/quickbuild-daemon/build/daemon-shrunk/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Library/Android/sdk/platforms/android-36.1/android.jar
- **kotlinStdlib**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-i-size/quickbuild-daemon/build/daemon-shrunk/kotlin-stdlib-2.3.0.jar
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

Baseline: PASS (2557ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=539ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=393ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## compose-kotlin (kotlin) -- PASS

Baseline: PASS (577ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-composable-body | composable-body | code | compile | compile=613ms | 2 | PASS |  |
| 02-add-composable | add-composable | code | compile | compile=562ms | 2 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (403ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=382ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=455ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=475ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (289ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=60ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=58ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=78ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (281ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=242ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=85ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=278ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=316ms, relink=78ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (636ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=536ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=555ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=532ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=74ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (512ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=327ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=437ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=415ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=74ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (344ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=353ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=487ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=523ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=527ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=423ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=77ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (365ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=262ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (362ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=303ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=359ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=674ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=77ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (224ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=86ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=79ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=78ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=231ms, relink=85ms, reconfigure=874ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (329ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=313ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=434ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=364ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=77ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## sora-editor-lib (mixed) -- PASS

Baseline: PASS (244ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-editor-core-value | method-body | code | compile | compile=197ms | 1 | PASS |  |
| 02-sample-app-ui | const-change | code | compile | compile=238ms | 2 | PASS |  |
| 03-cross-module | cross-file-api-add | code | compile | compile=221ms | 2 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)
