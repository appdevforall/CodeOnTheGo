# Quick Build corpus matrix results

Generated: 2026-07-16T08:48:18.602099+00:00

## Config

- **daemonJar**: quickbuild-daemon/build/libs/quickbuild-daemon.jar
- **daemonAvailable**: True
- **androidJar**: /Users/bryanchan/Android/Sdk/platforms/android-36/android.jar
- **kotlinStdlib**: quickbuild-daemon/build/libs/kotlin-stdlib-2.3.0.jar
- **aapt2**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/aapt2
- **d8Jar**: /Users/bryanchan/Android/Sdk/build-tools/36.0.0/lib/d8.jar
- **minApi**: 30

## Summary

- Apps: 6, Edits: 23
- Passed: 10, Failed: 0, Skipped: 13

## assets-app (kotlin) -- PASS

Baseline: PASS (21366ms), classes=5

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-asset-only | asset-only | assets |  |  | - | PASS |  |
| 02-method-body | method-body | code | compile | compile=972ms | 1 | PASS |  |
| 03-asset-plus-code | asset-plus-code | code | compile | compile=456ms | 1 | PASS |  |

Output equivalence: PASS -- 5 classes match (set + CRC)

## fanout-kotlin (kotlin) -- PASS

Baseline: PASS (5507ms), classes=36

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-const-change | const-change | code | compile | compile=1745ms | 21 | PASS |  |
| 02-inline-body | inline-body | code | compile | compile=1495ms | 11 | PASS |  |
| 03-leaf-method-body | leaf-method-body | code | compile | compile=550ms | 1 | PASS |  |

Output equivalence: PASS -- 36 classes match (set + CRC)

## hello-java (java) -- PASS

Baseline: PASS (847ms), classes=6

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | method-body | code | compile | compile=243ms | 1 | PASS |  |
| 02-new-method | new-method | code | compile | compile=263ms | 2 | PASS |  |
| 03-resource-value | resource-value | resources | relink | relink=198ms | - | PASS |  |
| 04-asset | asset | assets |  |  | - | PASS |  |

Output equivalence: PASS -- 6 classes match (set + CRC)

## hello-kotlin (kotlin) -- FAILED

Baseline: FAILED (583ms), classes=-
  - baseline compile failed: [{'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/hello-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/hellokotlin/MainActivity.kt', 'line': 24, 'column': 47}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/hello-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/hellokotlin/MainActivity.kt', 'line': 28, 'column': 33}]

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 02-resource-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 03-noop | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 04-mixed | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 05-manifest | None | None |  |  | - | SKIPPED | baseline compile did not succeed |

## medium-kotlin (kotlin) -- FAILED

Baseline: FAILED (1065ms), classes=-
  - baseline compile failed: [{'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/DetailsActivity.kt', 'line': 7, 'column': 44}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/DetailsActivity.kt', 'line': 30, 'column': 55}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/MainActivity.kt', 'line': 10, 'column': 44}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/MainActivity.kt', 'line': 44, 'column': 21}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/MainActivity.kt', 'line': 49, 'column': 21}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/MainActivity.kt', 'line': 55, 'column': 21}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/SettingsActivity.kt', 'line': 7, 'column': 44}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/SettingsActivity.kt', 'line': 24, 'column': 21}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/medium-kotlin/tree/src/main/java/org/appdevforall/cotg/corpus/medium/ui/SettingsActivity.kt', 'line': 29, 'column': 21}]

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-method-body | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 02-signature-change | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 03-new-class | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 04-resource-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |

## resources-heavy (kotlin) -- SKIPPED

Baseline: SKIPPED (491ms), classes=-
  - baseline compile failed: [{'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/FooterViewBuilder.kt', 'line': 10, 'column': 20}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/HeaderViewBuilder.kt', 'line': 10, 'column': 20}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/MainActivity.kt', 'line': 12, 'column': 18}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/MainActivity.kt', 'line': 14, 'column': 46}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/SecondActivity.kt', 'line': 12, 'column': 18}, {'severity': 'ERROR', 'message': "Unresolved reference 'R'.", 'file': '/var/folders/1x/qb7n_q_n0pqcmdtr1jn2s3_80000gn/T/quickbuild-matrix/20260716T084817Z/resources-heavy/tree/src/main/java/org/appdevforall/cotg/corpus/resheavy/SecondActivity.kt', 'line': 14, 'column': 46}] (R.java unavailable -- likely cause)

| Edit | Class | Expected route | Ops | ms | Recompiled | Status | Notes |
|---|---|---|---|---|---|---|---|
| 01-string-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 02-color-value | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 03-layout-edit | None | None |  |  | - | SKIPPED | baseline compile did not succeed |
| 04-string-ADD | None | None |  |  | - | SKIPPED | baseline compile did not succeed |

## Gaps

- hello-kotlin: baseline compile FAILED; all edits skipped for this app
- medium-kotlin: baseline compile FAILED; all edits skipped for this app
- resources-heavy: R.java generation failed even with aapt2 provided (see stderr log)
- resources-heavy: baseline compile could not be verified (no R.java); all edits skipped
