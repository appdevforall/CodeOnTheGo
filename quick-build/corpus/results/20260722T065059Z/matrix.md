# Quick Build corpus matrix results

Generated: 2026-07-22T06:50:59.781367+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-a-corpus/quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-a-corpus/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 4, Edits: 14
- Passed: 14, Failed: 0, Skipped: 0

## findroid (kotlin) -- PASS

Baseline: PASS (2795ms), classes=11

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-addserveraction-format | noop-format | code | compile | compile=196ms | 1 | PASS |  |
| 02-serveraddressesstate-format | noop-format | code | compile | compile=155ms | 1 | PASS |  |
| 03-server-format | noop-format | code | compile | compile=131ms | 1 | PASS |  |

Output equivalence: PASS -- 11 classes match (set + CRC)

## jcomposecogo (kotlin) -- PASS

Baseline: PASS (533ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-uistate-field-add | field-add | code | compile | compile=385ms | 3 | PASS |  |
| 02-reset-method | method-add | code | compile | compile=139ms | 2 | PASS |  |
| 03-derived-property | property-add | code | compile | compile=186ms | 2 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## kiss (java) -- PASS

Baseline: PASS (267ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-ascii-z-fix | boundary-fix | code | compile | compile=93ms | 1 | PASS |  |
| 02-empty-result | method-add | code | compile | compile=57ms | 2 | PASS |  |
| 03-nonnull-tostring | annotation-add | code | compile | compile=96ms | 1 | PASS |  |
| 04-clipboard-label | overload-add | code | compile | compile=92ms | 1 | PASS |  |
| 05-clipboard-contextcompat | api-migration | code | compile | compile=51ms | 1 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## seal (kotlin) -- PASS

Baseline: PASS (661ms), classes=18

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-sponsor-tier-nullable | nullable-change | code | compile | compile=103ms | 1 | PASS |  |
| 02-sponsor-social-links | restructure | code | compile | compile=127ms | 10 | PASS |  |
| 03-videoinfo-fields | field-add | code | compile | compile=1169ms | 10 | PASS |  |

Output equivalence: PASS -- 21 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
