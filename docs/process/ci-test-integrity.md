# CI test integrity: unit-test failures cannot fail any build

Status: verified in the build files and workflow YAML on `stage` at `75483b6eb`,
2026-07-28. No decision taken, nothing fixed. **Scope: CoGo-wide CI, not
ADFA-4128** — the Quick Build work is where this was noticed, not what it is
about. Written to be handed over as its own ticket (task #86).

Provenance: everything below is reading `build.gradle.kts`, module build scripts,
and `.github/workflows/*.yml`; the YAML is authoritative for what CI runs. One
item (the connected-test crash) is `[measured on a56]`.

## The finding, and the first thing to do

Two independent facts combine. The root build sets `ignoreFailures = true` on
every Gradle `Test` task in every module, so a module whose unit tests all fail
still reports success — and nothing downstream compensates: no workflow parses
test XML, no step thresholds coverage. Separately, **no unit test runs on a push
or a PR at all**; the only workflow that runs any test is a nightly scheduled
job. So a change that broke unit tests in any module except one could be pushed,
reviewed, and merged with every check green, and the nightly job would also stay
green.

**Do this first: run the suite once with failures honoured and publish the list.**
It is cheap and needs no code change, and it converts a theoretical exposure into
a concrete backlog. How much has actually slipped through is `[unmeasured]` —
nobody has run the suite with `ignoreFailures = false` to see what is currently
red. The size of that repair job is the input to every option below.

Worth saying plainly: the suppression is one line, well-intentioned (it exists so
JaCoCo still writes its `.exec` when a test fails), and has been quietly in force
across every module. A build that is green because failures are suppressed looks
exactly like a build that is green because everything passes.

## What is actually configured

| # | Finding | Where |
|---|---|---|
| 1 | `ignoreFailures = true` on every Test task, unconditional, no property or env gate | `build.gradle.kts:72-84` |
| 2 | The sole exception: `:quickbuild-daemon:test` re-sets it to `false`, but only when `REQUIRE_BUILD_TOOLCHAIN=1` | `quickbuild-daemon/build.gradle.kts:80-88` |
| 3 | Nothing downstream reads test results — no `TEST-*.xml` parsing, no reporter action, no XML gate. `analyze.yml` also passes `--continue` | `analyze.yml:113`, `:119-122` |
| 4 | The only push-triggered workflow runs zero tests | `debug.yml` |
| 5 | Tests run only in a nightly scheduled job | `analyze.yml:11-13` |
| 6 | `:gradle-plugin:test` runs in no workflow at all, and its documented 90% coverage gate has never executed | `gradle-plugin/build.gradle.kts:138-145` |
| 7 | `jacocoAggregateReport` is v8Debug-only in both directions, so 27 plain-JVM modules contribute neither coverage data nor class files | `build.gradle.kts:429-433`, `:458-468`, `:476-483` |
| 8 | The SDK is not a declared Test input, so its disappearance leaves the task UP-TO-DATE rather than failing | `quickbuild-daemon/build.gradle.kts:59-60` |

Detail on the ones where the detail matters:

**Push vs nightly.** `debug.yml` is what a developer sees on a PR branch; its
three checks are `:plugin-api:apiCheck`, `spotlessCheck`, and
`:app:assembleV8Debug`. A change that breaks every unit test in the repo passes
all three. `analyze.yml` is nightly (`schedule` + `workflow_dispatch` only) and
runs two sets of tests: `:quickbuild-daemon:test` named explicitly with
`REQUIRE_BUILD_TOOLCHAIN: '1'` (`analyze.yml:112`) — the one that can fail — and
every Android module's `testV8DebugUnitTest` transitively, because `sonarqube`
depends on `jacocoAggregateReport` which depends on each subproject's test task
(`build.gradle.kts:421-433`). All of the latter run under `ignoreFailures` and
cannot fail anything. No plain-JVM `test` task other than `:quickbuild-daemon:test`
runs anywhere in CI.

**`:gradle-plugin` is unprotected twice over.** It has 12 Kotlin test classes plus
a `sample-project` fixture, and `gradle-plugin/build.gradle.kts:138-145` wires
`jacocoTestReport` to `test` for a documented ">= 90% line + branch" DoD gate.
Nothing invokes any of it: it is not in `analyze.yml`'s task list, and being
plain-JVM it has no `testV8DebugUnitTest` for the aggregate to pull in. This is
the module that owns the Quick Build setup build.

**The aggregate's exclusion is total, not partial.** Execution data is collected
only from `outputs/unit_test_code_coverage/v8DebugUnitTest/`, and the class
directories are AGP variant paths, so plain-JVM modules (which write
`build/jacoco/test.exec`) contribute nothing — and via
`sonar.coverage.jacoco.xmlReportPaths` they are invisible to SonarCloud coverage
too. 27 of ~90 included projects are plain-JVM; seven of them actually have a
`src/test`: `gradle-plugin`, `quickbuild-daemon`, `lexers`, `logger`, `lookup`,
`shared`, `subprojects/tooling-api-impl`. A secondary fragility in the same
block: `build.gradle.kts:432` uses `findByName`, an eager lookup that silently
yields null, so a module whose test task is not yet realized drops out with no
error.

**Two corrections, so the fix is not mis-scoped.** `quick-build` is *not* a
plain-JVM module — it applies the Android library plugins, so it has
`testV8DebugUnitTest` and *is* in the aggregate; its tests run nightly and are
measured, they just cannot fail anything. And `build-logic` can never be in the
aggregate for a different reason: it is an `includeBuild`
(`settings.gradle.kts:23`), not a subproject.

**The SDK gap is narrower than it looks, but real.** Commit `51a9aafec` added a
fail-if-skipped switch (`TestSdk.kt:18-29`): with the flag on, a missing SDK turns
33 silently-skipped tests into a hard failure, and CI sets it. What it does not
cover is the task not running at all — `analyze.yml` runs on `self-hosted` and
passes `--no-build-cache` but not `--rerun-tasks`, so `build/` persists between
nightly runs. Remove the SDK, change nothing else, re-run: Gradle sees identical
declared inputs, prints `Task :quickbuild-daemon:test UP-TO-DATE`, no test JVM is
forked, and the check never executes. The switch protects against silent
*skipping*, not silent *not running*.

Related but distinct: Android lint runs with `abortOnError = false`
(`app/build.gradle.kts:175`, `plugin-manager/build.gradle.kts:12`), and
`REVIEW.md:103` states a ">= 50% line & branch" bar that is an instruction to a
human reviewer, not an automated gate. Two of the repo's own documented quality
gates are therefore documentation only.

## Adjacent, same ticket: instrumented tests cannot run locally

`:app:connectedV8DebugAndroidTest` cannot run at all on AGP 8.8.2. AGP's Unified
Test Platform builds a TLS cert before starting its result listener and dies in
BouncyCastle with `NoClassDefFoundError: ... OperatorHelper`
`[measured on a56, 2026-07-28]`. Evidence:
`quick-build/corpus/results/20260728T095928Z-task70-kaspresso-smoke/connected-run2-utp-bc-stacktrace.log:126843-126847`.

This is invisible to CI, because `connectedAndroidTest` appears in no workflow —
`instrumentation-test.yml` builds the two APKs and hands them to Firebase Test
Lab, never invoking AGP's connected-test task. It bites only local and on-device
work, and it is why the task-70 smoke walk fell back to `adb install -r` +
`adb shell am instrument -w`. Two consequences: adding a Kaspresso test to the
local loop is currently blocked, and CI's instrumented coverage is exactly two
classes, nightly. Tracked separately as task #94.

## Options, with their costs

1. **Run the suite once with failures honoured, and publish the list.** Cheap, no
   code change. Tells you whether item 2 is a five-minute change or a month of
   repair. Do this first.
2. **Replace `ignoreFailures = true` with a gate that still writes coverage.** The
   original intent is satisfiable without the blanket suppression: keep
   `ignoreFailures` while collecting, then add a verification task that reads the
   JUnit XML and fails the build; or set it from a property defaulting to `false`
   that only the coverage job sets `true`. Cost is whatever backlog item 1
   surfaces — this is the change that turns unknown red tests into a blocked
   pipeline, which is the point, but it should be a decision rather than a
   surprise.
3. **Run unit tests on push/PR.** `debug.yml` already builds the app, so adding a
   test task is small. Cost is PR wall-clock on the self-hosted runner, and it
   needs item 2 first — running tests that cannot fail buys nothing.
4. **Add `:gradle-plugin:test` (and the other six plain-JVM modules with tests) to
   whatever runs.** Independent of the above and worth doing regardless.
5. **Widen `jacocoAggregateReport` to plain-JVM modules.** Add the
   `build/jacoco/test.exec` path and the plain `classes/kotlin/main` /
   `classes/java/main` dirs, depend on `test` as well as `testV8DebugUnitTest`,
   and replace `findByName` with a lazy lookup so a missing task is an error
   rather than a silent drop. Mostly mechanical; the coverage number will move,
   probably down `[inferred]`, which needs saying before it surprises anyone.
6. **Make the SDK a declared input** on the toolchain-gated tests, so its
   disappearance invalidates the task instead of leaving it UP-TO-DATE. Small;
   closes the "green with zero tests executed" hole.
7. **The UTP/BouncyCastle crash** is a separate investigation — an AGP bump, a
   BouncyCastle alignment, or accepting the `adb am instrument` workaround as the
   local path. Not on the critical path for the items above.
