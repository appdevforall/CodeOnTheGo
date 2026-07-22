# Quick Build corpus matrix results

Generated: 2026-07-22T05:54:00.227647+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-a-corpus/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 3, Edits: 9
- Passed: 9, Failed: 0, Skipped: 0

## antennapod-model (java) -- PASS

Baseline: PASS (1853ms), classes=4

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-mediatype-remove-extension-guessing | code-removal | code | compile | compile=561ms | 1 | PASS |  |
| 02-feeditemfilter-serializable | interface-add | code | compile | compile=253ms | 1 | PASS |  |
| 03-feedfunding-serializable | interface-add | code | compile | compile=157ms | 1 | PASS |  |

Output equivalence: PASS -- 4 classes match (set + CRC)

## architecture-samples (kotlin) -- PASS

Baseline: PASS (10465ms), classes=16

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-task-id-required | required-param | code | compile | compile=1664ms | 1 | PASS |  |
| 02-create-task-returns-id | return-type-change | code | compile | compile=204ms | 1 | PASS |  |
| 03-refresh-rename | method-rename | code | compile | compile=140ms | 1 | PASS |  |

Output equivalence: PASS -- 16 classes match (set + CRC)

## readyou (kotlin) -- PASS

Baseline: PASS (1808ms), classes=8

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-date-isfuture | method-add | code | compile | compile=594ms | 1 | PASS |  |
| 02-number-infix | modifier-change | code | compile | compile=878ms | 1 | PASS |  |
| 03-string-rtl-mimetype | method-add | code | compile | compile=616ms | 2 | PASS |  |

Output equivalence: PASS -- 9 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
