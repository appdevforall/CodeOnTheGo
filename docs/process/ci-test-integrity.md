# Backlog Item - General Bug: A failing unit test cannot fail any CoGo build, and the PR path never compiles test code

**Ready to file.** Project ADFA · type Bug · component CI / build. CoGo-wide, not ADFA-4128 —
that work is where this was noticed. Verified against `origin/stage` at `4ab4e5634`, 2026-07-28.

## Summary

- **`ignoreFailures = true` is set on every Gradle `Test` task in every module, unconditionally**
  (`build.gradle.kts:80-83`, inside `subprojects {}`, no property or env gate). It has been there
  since `e799f575c` "integrate jacoco (#679)", 2025-11-29. There is **no opt-out anywhere on stage**.
- **This is not theoretical.** Nightly run **28852596391** ran three modules with a failing test —
  `:termux:termux-app`, `:lsp:java`, `:lsp:xml` — and finished `BUILD SUCCESSFUL in 13m 37s` with
  the workflow concluding **success**.
- **No push- or PR-triggered workflow compiles or runs unit tests**, and `stage` has **no required
  status checks** — its only ruleset carries `deletion`, `non_fast_forward`, `required_signatures`.
- **The consequence is already live: test sources have not compiled on stage for 21 consecutive
  days.** Every nightly since 2026-07-08 fails on `:plugin-manager:compileV8DebugUnitTestKotlin`
  and `:lsp:kotlin:compileV8DebugUnitTestKotlin`. `ignoreFailures` covers test *execution*, not
  test *compilation* — so this is the one class of test breakage that can still fail a build, and
  it only fails the job nobody watches.
- **Decision: do we make unit-test failures able to fail a build, and add a PR check that at least
  compiles test sources?**

## What is true, and what is not

Two things that sound like this bug but are false, listed so the ticket is not dismissed on them:

- **"Tests never run."** False. The nightly runs **56 modules'** unit tests, pulled in transitively:
  `sonarqube dependsOn jacocoAggregateReport` (`build.gradle.kts:416-418`), which `dependsOn` each
  module's `testV8DebugUnitTest` (`:420-428`). The wiring has been intact since `857f006be`
  (2026-01-06). The accurate statement is **tests run nightly but cannot fail the build, and never
  run on the PR path.**
- **"The 90% coverage gate never executes."** False on two counts. CoGo's own bar is **≥50% line
  and branch** (`REVIEW.md:103`); 90% is a wrapper-repo convention, not this team's.

## Findings

| # | Finding | Where |
|---|---|---|
| 1 | `ignoreFailures = true` on every Test task, unconditional, since 2025-11-29 | `build.gradle.kts:80-83`, `e799f575c` |
| 2 | Failing tests + green build + green workflow, in production | run `28852596391` (log lines 295056, 296370, 296383, 422211) |
| 3 | Only push-triggered workflow runs `apiCheck`, `spotlessCheck`, `assembleV8Debug` — no tests | `debug.yml:9-14`, `:198`, `:202`, `:214` |
| 4 | No required status checks on `stage`; ruleset "CoGo" (4152190) has none | `repos/.../rules/branches/stage` returns `[]` |
| 5 | Test **compilation** has failed every nightly since 2026-07-08 — 21 consecutive runs | `plugin-manager/src/test/kotlin/.../PluginManagerIntegrationTest.kt`, `lsp/kotlin/src/test/java/.../AddImportActionTest.kt` |
| 6 | Coverage aggregation is AGP-`v8Debug`-only, so ~29 subprojects contribute **zero coverage** | `build.gradle.kts:427`, `:450-461`, `:476-478` |
| 7 | `:plugin-api` contributes **no test task at all** to the graph, unlike the other 56 Android modules | run `30341039093` task graph |
| 8 | Nothing parses test XML — no reporter action, no gate | all 12 workflows |
| 9 | `instrumentation-test.yml` is also red 10/10 (2026-07-19..07-28) | scheduled runs |

Finding 6 in detail: the aggregate report's `dependsOn`, `executionData` and `classDirectories` all
name only `v8Debug` AGP paths. Plain-JVM modules emit `classes/{java,kotlin}/main`, which never
matches — so six JVM modules with real test suites (`:gradle-plugin`, `:lexers`, `:logger`,
`:lookup`, `:shared`, `:subprojects:tooling-api-impl`) plus `:plugin-api` report nothing. They are
still *statically analyzed* by Sonar (`build.gradle.kts:344-345` puts their `main` classes in
`sonar.java.binaries`) — they just have no coverage. `REVIEW.md`'s "prove it with
`jacocoAggregateReport`" is unmeasurable for them.

## Why it went unnoticed

- A build green because failures are suppressed looks exactly like a build green because everything
  passes, and the suppression is unconditional, so no local mode shows a developer the difference.
- The checks a developer watches on a PR run no tests, and nothing is *required* anyway.
- The one job that would notice is a nightly that has been red for three weeks.

## Suggested first steps

1. **Fix the two uncompilable test sources.** Nothing else can be measured until the nightly is
   green again.
2. **Run the suite once with failures honoured and publish the list.** How much has slipped through
   is `[unmeasured]`; that number is the input to every option (gate on PR, gate nightly,
   fix-then-gate, or accept and document).
3. **Add a PR check that at least compiles test sources.** Cheap, and it would have caught finding 5
   on the day it landed.

The suppression itself is one line and well-intentioned — it exists so JaCoCo still writes its
`.exec` when a test fails. Any fix needs to keep that property.

## Open questions — not verified

- **Why `:plugin-api` and `:logsender` contribute no test task.** Symptom confirmed, mechanism not.
  The plausible cause is a `tasks.findByName(...)` lazy-realization race at `build.gradle.kts:427`
  (a task not yet registered resolves to null and is silently dropped), which would make the
  dependsOn set nondeterministic — a sharper bug than "v8Debug-only". **Do not assert this without
  testing it.**
- **The commit that broke test compilation.** It landed between 2026-07-07 08:30 and 2026-07-08
  08:03. `cc2f5925a` (ADFA-4582) is the best fit for the `plugin-manager` half; the `lsp:kotlin`
  half does not trace cleanly to the same window.
- **The cause of 19 of the 21 failed runs.** GitHub returns HTTP 410 for logs older than ~07-25.
  All 21 failed at the same step; only 2 had readable errors. "All 21 have the same cause" is
  `[inferred]`, not measured.
- **Whether tests run anywhere outside `.github/workflows/`** (a pre-push hook, a self-hosted job).
  Only the GitHub workflows were checked.
