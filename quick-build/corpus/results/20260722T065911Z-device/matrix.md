# Quick Build corpus matrix results

Generated: 2026-07-22T06:59:39.148154+00:00

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

Baseline: PASS (9663ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1383ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=947ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=1095ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=1423ms, relink=300ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (4108ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=906ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=1494ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=1929ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=291ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (1665ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=345ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=377ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=325ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=1314ms, relink=322ms, reconfigure=2588ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## Device vs Host (median compile ms)

Host source: `quick-build/corpus/results/20260716T153413Z/matrix.md`

| App | Host median ms | Device median ms | Ratio (device/host) |
|---|---|---|---|
| hello-kotlin | 58ms | 1383ms | 23.8x |
| medium-kotlin | 197ms | 1494ms | 7.6x |
| resources-heavy | 231ms | 1314ms | 5.7x |
