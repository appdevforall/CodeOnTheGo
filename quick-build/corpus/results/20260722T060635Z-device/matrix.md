# Quick Build corpus matrix results

> **OFFLINE RUN** -- airplane mode ON; connectivity verified down (ping 8.8.8.8 failed) before the matrix; prior airplane_mode_on restored afterward.

Generated: 2026-07-22T06:06:48.162928+00:00

## Config

- **mode**: device
- **offline**: True
- **airplaneModePriorState**: 0
- **serial**: RZGYC24640P
- **pkg**: com.itsaky.androidide
- **daemonAvailable**: True
- **deviceAndroidJar**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/platforms/android-36/android.jar
- **deviceKotlinStdlib**: /data/user/0/com.itsaky.androidide/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar
- **deviceAapt2**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/aapt2
- **deviceD8Jar**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar
- **hostAndroidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **hostAapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **hostJavac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/adfa-4128-quick-build/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **minApi**: 30

## Summary

- Apps: 11, Edits: 43
- Passed: 43, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (11462ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=1469ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=1372ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (3369ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=1875ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=1559ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=1154ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (987ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=401ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=415ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=438ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (2144ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=858ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=290ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=1027ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=859ms, relink=311ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (3765ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1160ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=1229ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=1592ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=297ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## mixed-lang (mixed) -- PASS

Baseline: PASS (2628ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1121ms | 1 | PASS |  |
| 02-java-signature-change | java-signature-change | code | compile | compile=1623ms | 3 | PASS |  |
| 03-kotlin-signature-change | kotlin-signature-change | code | compile | compile=1535ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=300ms | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## multi-activity-kotlin (kotlin) -- PASS

Baseline: PASS (2442ms), classes=9

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=965ms | 1 | PASS |  |
| 02-main-activity-body | method-body | code | compile | compile=1340ms | 2 | PASS |  |
| 03-list-activity-body | method-body | code | compile | compile=1506ms | 1 | PASS |  |
| 04-signature-change | signature-change | code | compile | compile=2244ms | 6 | PASS |  |
| 05-new-class | new-class | code | compile | compile=1577ms | 3 | PASS |  |
| 06-resource-value | resource-value | resources | relink | relink=293ms | - | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## native-app (kotlin) -- PASS

Baseline: PASS (1677ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1043ms | 1 | PASS |  |
| 02-native-lib-change | native-lib-change | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## receiver-provider-app (kotlin) -- PASS

Baseline: PASS (2273ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-receiver-body | receiver-body | code | compile | compile=955ms | 1 | PASS |  |
| 02-provider-body | provider-body | code | compile | compile=1159ms | 1 | PASS |  |
| 03-new-class | new-class | code | compile | compile=1671ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=321ms | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (1448ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=350ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=420ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=387ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=1100ms, relink=412ms, reconfigure=4642ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## service-app (kotlin) -- PASS

Baseline: PASS (2144ms), classes=7

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-service-method-body | method-body | code | compile | compile=1294ms | 1 | PASS |  |
| 02-binder-signature-change | signature-change | code | compile | compile=1910ms | 3 | PASS |  |
| 03-activity-body | method-body | code | compile | compile=1830ms | 1 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=273ms | - | PASS |  |

Output equivalence: PASS -- 7 classes match (set + CRC)

## Device vs Host (median compile ms)

Host source: `quick-build/corpus/results/20260716T153413Z/matrix.md`

| App | Host median ms | Device median ms | Ratio (device/host) |
|---|---|---|---|
| assets-app | 256ms | 1421ms | 5.5x |
| fanout-kotlin | 768ms | 1559ms | 2.0x |
| hello-java | 59ms | 408ms | 6.9x |
| hello-kotlin | 58ms | 859ms | 14.8x |
| medium-kotlin | 197ms | 1229ms | 6.2x |
| mixed-lang | - | 1535ms | - |
| multi-activity-kotlin | - | 1506ms | - |
| native-app | - | 1043ms | - |
| receiver-provider-app | - | 1159ms | - |
| resources-heavy | 231ms | 1100ms | 4.8x |
| service-app | - | 1830ms | - |
