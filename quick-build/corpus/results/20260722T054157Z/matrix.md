# Quick Build corpus matrix results

Generated: 2026-07-22T05:42:01.794287+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/daemon/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/daemon/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **javac**: /Volumes/Data/Users/bryanchan/dev/agent-wrapper-project/worktrees/codeonthego/4128-ws-a2-secuso/flox/local/.flox/run/aarch64-darwin.local.dev/bin/javac
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **composePluginJar**: None
- **composeRuntimeJar**: None
- **minApi**: 30

## Summary

- Apps: 1, Edits: 3
- Passed: 3, Failed: 0, Skipped: 0

## ruler (java) -- PASS

Baseline: PASS (3813ms), classes=19

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | string-value | resources | relink | relink=2190ms | - | PASS |  |
| 02-string-value-locale | string-value-locale | resources | relink | relink=332ms | - | PASS |  |
| 03-resource-add | locale-file-new | resources | relink | relink=394ms | - | PASS |  |

Output equivalence: PASS -- 19 classes match (set + CRC)

## Gaps

- no --compose-plugin-jar/--compose-runtime-jar: apps with "compose": true SKIPPED in this run
