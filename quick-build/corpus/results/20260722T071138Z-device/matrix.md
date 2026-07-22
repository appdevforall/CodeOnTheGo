# Quick Build corpus matrix results

Generated: 2026-07-22T07:11:46.007074+00:00

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
- Passed: 5, Failed: 0, Skipped: 8

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (12893ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=1620ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=368ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=1828ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=1719ms, relink=285ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- FAILED

Baseline: FAILED (30444ms), classes=-
  - baseline compile failed: [{'severity': 'ERROR', 'message': 'java.lang.OutOfMemoryError: Java heap space'}]

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 02-signature-change | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 03-new-class | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 04-resource-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |

## resources-heavy (kotlin) -- FAILED

Baseline: FAILED (15022ms), classes=-
  - baseline compile failed: [{'severity': 'ERROR', 'message': 'java.lang.OutOfMemoryError: Java heap space'}]

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 02-color-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 03-layout-edit | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 04-string-ADD | None | None |  |  | - | SKIPPED | baseline compile did not succeed |

## Gaps

- medium-kotlin: baseline compile did not succeed on-device; all edits skipped for this app
- resources-heavy: baseline compile did not succeed on-device; all edits skipped for this app

## Device vs Host (median compile ms)

Host source: `quick-build/corpus/results/20260716T153413Z/matrix.md`

| App | Host median ms | Device median ms | Ratio (device/host) |
|---|---|---|---|
| hello-kotlin | 58ms | 1719ms | 29.6x |
| medium-kotlin | 197ms | - | - |
| resources-heavy | 231ms | - | - |
