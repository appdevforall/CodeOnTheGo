# Quick Build corpus matrix results

Generated: 2026-07-22T07:04:26.571497+00:00

## Config

- **mode**: device
- **offline**: False
- **airplaneModePriorState**: None
- **serial**: RZGYC24640P
- **pkg**: com.itsaky.androidide
- **daemonAvailable**: True
- **deviceAndroidJar**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/platforms/android-36/android.jar
- **deviceKotlinStdlib**: /data/user/0/com.itsaky.androidide/files/home/.cg/quickbuild/daemon/kotlin-stdlib-2.3.0.jar
- **deviceAapt2**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/aapt2
- **deviceD8Jar**: /data/user/0/com.itsaky.androidide/files/home/android-sdk/build-tools/35.0.0/lib/d8.jar
- **hostAndroidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **hostAapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **hostJavac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/CodeOnTheGo/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **minApi**: 30

## Summary

- Apps: 3, Edits: 13
- Passed: 13, Failed: 0, Skipped: 0

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (9080ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1372ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=361ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=1301ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=1321ms, relink=269ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (4677ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1190ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=1616ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=2020ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=303ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (2413ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=328ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=418ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=316ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=1640ms, relink=348ms, reconfigure=4753ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## Device vs Host (median compile ms)

Host source: `quick-build/corpus/results/20260716T153413Z/matrix.md`

| App | Host median ms | Device median ms | Ratio (device/host) |
|---|---|---|---|
| hello-kotlin | 58ms | 1321ms | 22.8x |
| medium-kotlin | 197ms | 1616ms | 8.2x |
| resources-heavy | 231ms | 1640ms | 7.1x |
