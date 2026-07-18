# Quick Build corpus matrix results

Generated: 2026-07-16T15:34:14.004309+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/libs/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/libs/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/adfa-4128-quick-build/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **minApi**: 30

## Summary

- Apps: 6, Edits: 23
- Passed: 23, Failed: 0, Skipped: 0

## assets-app (kotlin) -- PASS

Baseline: PASS (2599ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=361ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=152ms | 1 | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (1495ms), classes=34

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=2043ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=768ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=108ms | 1 | PASS |  |

Output equivalence: PASS -- 34 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (354ms), classes=3

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=64ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=54ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=246ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 3 classes match (set + CRC)

## hello-kotlin (kotlin) -- PASS

Baseline: PASS (393ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=58ms | 1 | PASS |  |
| 02-resource-value | resource-value | resources | relink | relink=97ms | - | PASS |  |
| 03-noop | noop | code | compile | compile=93ms | 1 | PASS |  |
| 04-mixed | mixed | mixed | compile, relink | compile=58ms, relink=78ms | 1 | PASS |  |
| 05-manifest | manifest | fallback |  |  | - | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## medium-kotlin (kotlin) -- PASS

Baseline: PASS (944ms), classes=28

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=76ms | 1 | PASS |  |
| 02-signature-change | signature-change | code | compile | compile=197ms | 6 | PASS |  |
| 03-new-class | new-class | code | compile | compile=224ms | 2 | PASS |  |
| 04-resource-value | resource-value | resources | relink | relink=84ms | - | PASS |  |

Output equivalence: PASS -- 29 classes match (set + CRC)

## resources-heavy (kotlin) -- PASS

Baseline: PASS (372ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=109ms | - | PASS |  |
| 02-color-value | color-value | resources | relink | relink=179ms | - | PASS |  |
| 03-layout-edit | layout-edit | resources | relink | relink=75ms | - | PASS |  |
| 04-string-ADD | string-add | mixed | reconfigure, compile, relink | compile=231ms, relink=92ms, reconfigure=913ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)
