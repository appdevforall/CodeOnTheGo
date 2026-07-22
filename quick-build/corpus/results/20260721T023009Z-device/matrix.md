# Quick Build corpus matrix results

> **OFFLINE RUN** -- airplane mode ON; connectivity verified down (ping 8.8.8.8 failed) before the matrix; prior airplane_mode_on restored afterward.

Generated: 2026-07-21T02:30:23.449243+00:00

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

- Apps: 6, Edits: 23
- Passed: 23, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (9450ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=1426ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=1540ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (3064ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=2331ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=1927ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=1062ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (1217ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=349ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=220ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=405ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (1940ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1266ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=290ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=942ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=882ms, relink=270ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (3566ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1075ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=1346ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=1318ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=295ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (1781ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=381ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=317ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=436ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=1407ms, relink=315ms, reconfigure=2638ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## Device vs Host (median compile ms)

Host source: `quick-build/corpus/results/20260716T153413Z/matrix.md`

| App | Host median ms | Device median ms | Ratio (device/host) |
|---|---|---|---|
| assets-app | 256ms | 1483ms | 5.8x |
| fanout-kotlin | 768ms | 1927ms | 2.5x |
| hello-java | 59ms | 284ms | 4.8x |
| hello-kotlin | 58ms | 942ms | 16.2x |
| medium-kotlin | 197ms | 1318ms | 6.7x |
| resources-heavy | 231ms | 1407ms | 6.1x |
