# CI test integrity: unit-test failures cannot fail any build

Status: verified in the build files and workflow YAML on `stage` at `75483b6eb`,
2026-07-28. No decision taken, nothing fixed. **Scope: CoGo-wide CI, not
ADFA-4128** — the Quick Build work is where this was noticed, not what it is
about. Written to be handed over as its own ticket.

Provenance: everything below is reading `build.gradle.kts`, module build
scripts, and `.github/workflows/*.yml`. The YAML is authoritative for what CI
actually runs. One item (the connected-test crash) is
`[measured on a56]`; inferences are tagged `[inferred]`.

## The finding in one paragraph

The root build sets `ignoreFailures = true` on every Gradle `Test` task in every
module, so a module whose unit tests all fail still reports success. Nothing
downstream compensates: no workflow parses test XML, no step thresholds
coverage. Separately, **no unit test runs on a push or a PR at all** — the only
workflow that runs any test is a nightly scheduled job. The two combine into a
simple statement: for the last however-long, a change that broke unit tests in
any module except one could be pushed, reviewed, and merged with every check
green, and the nightly job would also stay green.

## What is actually configured

### 1. `ignoreFailures = true` on every Test task

`build.gradle.kts:72-84`:

```kotlin
subprojects {
	plugins.apply("jacoco")
	...
	tasks.withType<Test> {
		// Continue even if tests fail, so coverage data is written
		ignoreFailures = true
```

Unconditional — no property or env gate. `subprojects {}` + `tasks.withType<Test>`
reaches every Test task in every included module, Android (`testV8DebugUnitTest`)
and plain-JVM (`test`) alike. Because it is applied in a non-lazy `withType`
block at root configuration time, a module can only escape by re-setting the
flag *after* root evaluation.

The stated intent — keep the run going so JaCoCo writes its `.exec` — is
reasonable. The problem is that nothing then checks the results.

**The one mitigation**, and it is narrow. `quickbuild-daemon/build.gradle.kts:82-88`:

```kotlin
val requireToolchain =
	providers.environmentVariable("REQUIRE_BUILD_TOOLCHAIN").orNull == "1" ||
		providers.gradleProperty("requireBuildToolchain").isPresent
systemProperty("quickbuild.test.requireToolchain", requireToolchain.toString())
if (requireToolchain) {
	ignoreFailures = false
}
```

So `:quickbuild-daemon:test` is the sole Test task in the repo whose failures can
fail a build, and only when the flag is set. Its comment at lines 80-81 names the
problem outright: *"Also undo the root build's ignoreFailures=true (set for
coverage collection) so the failure actually fails the build - without that, CI
would stay green."* `gradle-plugin/build.gradle.kts:137` documents its own
exposure rather than fixing it: *"do not block it: the root build sets
ignoreFailures on every Test task."*

There is no other `ignoreFailures` anywhere in the repo. Related but distinct:
Android lint runs with `abortOnError = false` in `app/build.gradle.kts:175` and
`plugin-manager/build.gradle.kts:12`.

### 2. Nothing downstream reads test results

No workflow references `TEST-*.xml`, `build/test-results/`, or
`build/reports/tests/`. There is no test-reporter action, no `--fail-on-*`, no
XML gate. `analyze.yml:119-122` uploads the JaCoCo report as an artifact
(`if: always()`) and no step parses or thresholds it. `analyze.yml:113` also
passes `--continue`, which further decouples task failures from the run's exit
status.

`REVIEW.md:103` states a ">= 50% line & branch" coverage bar, but it is an
instruction to a human reviewer, not an automated gate.

### 3. What each workflow actually runs

Thirteen workflows. Every `./gradlew` invocation across all of them:

| Workflow | Trigger | Gradle tasks | Runs tests? |
|---|---|---|---|
| `debug.yml` | **push** (all branches but `main`) | `:plugin-api:apiCheck` (198), `spotlessCheck` (202), `:app:assembleV8Debug` (214) | no |
| `analyze.yml` | schedule `19 7 * * *` + dispatch | `:testing:tooling:assemble :testing:common:assemble :quickbuild-daemon:test sonarqube --info --no-build-cache -x lint --continue` (113) | **yes** |
| `instrumentation-test.yml` | schedule `0 12 * * *` + dispatch | `:app:assembleV8DebugAndroidTest`, `:app:assembleV8Debug` (79-80) | assembles only; tests run on Firebase Test Lab |
| `release.yml`, `generate_assets.yml`, `release-plugin-api.yml` | release/dispatch | assemble / asset tasks | no |
| `weekly-release.yml`, `compress_docdb.yml`, `crowdin_contributors.yml`, `delete_workflows.yml`, `gh2jira.yml`, `lint-branch-name.yml` | various | none | no |

`debug.yml` is what a developer sees on a PR branch, and it runs **zero** tests.

`analyze.yml` is the only place tests run, it is **nightly and not triggered on
push or PR** (`analyze.yml:11-13`), and it runs on `self-hosted` with
`timeout-minutes: 180`. Two sets of tests run there:

1. `:quickbuild-daemon:test`, named explicitly, with `REQUIRE_BUILD_TOOLCHAIN: '1'`
   (`analyze.yml:112`) — the one that can fail.
2. Every Android module's `testV8DebugUnitTest`, **transitively**: the workflow
   runs `sonarqube`; `build.gradle.kts:421-422` makes `sonarqube` depend on
   `jacocoAggregateReport`; `build.gradle.kts:429-433` makes that depend on every
   subproject's `testV8DebugUnitTest`. All of these run under
   `ignoreFailures = true` and cannot fail anything.

No plain-JVM `test` task other than `:quickbuild-daemon:test` runs anywhere in CI.

### 4. `:gradle-plugin:test` runs in no workflow

`:gradle-plugin` has 12 Kotlin test classes under
`gradle-plugin/src/test/java/com/itsaky/androidide/gradle/` — including
`QuickBuildSetupBuildTest.kt`, `AndroidIDEPluginTest.kt`,
`InitScriptClasspathTest.kt`, and seven `quickbuild/*Test.kt` — plus a
`src/test/resources/sample-project/` fixture. `gradle-plugin/build.gradle.kts:138-145`
wires `jacocoTestReport` to `test` for a documented **">= 90% line + branch"
DoD coverage gate**.

Nothing invokes any of it. It is not in `analyze.yml`'s task list, and it is a
plain-JVM module so it has no `testV8DebugUnitTest` for the aggregate to pull in.
The module that owns the Quick Build setup build — the piece that produces the
installable test app — has a documented 90% gate that has never run in CI.

### 5. `jacocoAggregateReport` is v8Debug-only in *both* directions

The claim as usually stated is that it only depends on `testV8DebugUnitTest`.
That is true (`build.gradle.kts:429-433`), and the execution data is Android-only
too, which makes the exclusion total rather than partial. `build.gradle.kts:476-483`:

```kotlin
// Collect execution data (.exec files)
val execFiles =
	subprojects
		...
		.map { subproj ->
			subproj.layout.buildDirectory.file(
				"outputs/unit_test_code_coverage/v8DebugUnitTest/testV8DebugUnitTest.exec",
			)
```

Plain-JVM modules write `build/jacoco/test.exec`, which is never in that list.
The class directories (`build.gradle.kts:458-468`) are AGP variant paths too
(`tmp/kotlin-classes/v8Debug`, `classes/java/v8Debug`,
`intermediates/javac/v8DebugUnitTest/classes`). So plain-JVM modules contribute
neither coverage data nor class files, and via
`sonar.coverage.jacoco.xmlReportPaths` (`build.gradle.kts:376-382`) they are
invisible to SonarCloud coverage as well.

**27 of ~90 included projects are plain-JVM**, and are therefore outside the
aggregate entirely: `annotation-processors`, `annotation-processors-ksp`,
`annotations`, `build-info`, `eventbus`, `gradle-plugin`, `gradle-plugin-config`,
`lexers`, `logger`, `lookup`, `lsp/jvm-symbol-models`, `quickbuild-daemon`,
`quickbuild-protocol`, `shared`, `subprojects/aapt2-proto`,
`subprojects/builder-model-impl`, `subprojects/framework-stubs`,
`subprojects/project-models`, `subprojects/project-serial`,
`subprojects/project-serialization`, `subprojects/tooling-api`,
`subprojects/tooling-api-events`, `subprojects/tooling-api-impl`,
`subprojects/tooling-api-model`, `subprojects/xml-dom`, `testing/common`,
`testing/tooling`. Seven of them actually have a `src/test`: `gradle-plugin`,
`quickbuild-daemon`, `lexers`, `logger`, `lookup`, `shared`,
`subprojects/tooling-api-impl`.

Net: `:quickbuild-daemon:test` runs in CI but its coverage never reaches the
aggregate report; `:gradle-plugin:test` neither runs nor is measured.

**Two corrections to how this has been described**, worth carrying into the
ticket so the fix isn't mis-scoped:

- **`quick-build` is *not* a plain-JVM module.** `quick-build/build.gradle.kts:3-5`
  applies the Android library + Kotlin Android plugins, so it has
  `testV8DebugUnitTest` and *is* in the aggregate. Its tests run nightly and are
  measured; they just cannot fail anything.
- **`build-logic` can never be in the aggregate** for a different reason: it
  lives at `composite-builds/build-logic` and is an `includeBuild`
  (`settings.gradle.kts:23`), not a subproject.

A secondary fragility in the same block: line 432 uses `findByName`, an eager
lookup that silently yields null. A module whose `testV8DebugUnitTest` is not
realized at that point drops out of the aggregate with no error.

### 6. SDK presence is not a tracked Test input

The only `inputs.*` declarations on any Test task in the repo are
`quickbuild-daemon/build.gradle.kts:59-60` (`stageComposeTestRuntime`,
`composeCompilerPlugin`). A repo-wide grep for `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in build scripts returns **zero** hits. The SDK
is located at test runtime, inside the forked test JVM, by
`quickbuild-daemon/src/test/kotlin/.../TestSdk.kt:32-40` via `System.getenv` —
which Gradle does not track.

There *is* a real mitigation here, landed as commit `51a9aafec` ("fail-if-skipped
switch for toolchain-gated daemon tests", task #39). `TestSdk.kt:18-29`:

```kotlin
private fun toolchainRequired(): Boolean = System.getProperty("quickbuild.test.requireToolchain").toBoolean()

private fun requireOrSkip(available: Boolean, what: String): Boolean {
	check(available || !toolchainRequired()) {
		"REQUIRE_BUILD_TOOLCHAIN is set but the $what is unavailable on this host - ..."
	}
	return available
}
```

With the flag on, a missing SDK turns 33 silently-skipped tests into a hard
failure instead. CI sets it (`analyze.yml:112`).

**What it does not cover: the task not running at all.** `analyze.yml` runs on
`self-hosted` and passes `--no-build-cache` but **not** `--rerun-tasks`, so
`build/` persists between nightly runs on the same box. Remove the SDK, change
nothing else, re-run: Gradle sees identical declared inputs, prints
`Task :quickbuild-daemon:test UP-TO-DATE`, no test JVM is forked, and the
fail-if-skipped check never executes. The switch protects against silent
*skipping*, not against silent *not running*.

Partial saving grace: `systemProperty("quickbuild.test.requireToolchain", ...)`
(line 85) *is* a tracked input, so toggling `REQUIRE_BUILD_TOOLCHAIN` between
runs does invalidate the task. Holding it constant at `'1'`, as `analyze.yml`
does, does not.

### 7. The instrumented-test blocker (adjacent, same ticket)

`:app:connectedV8DebugAndroidTest` cannot run at all on AGP 8.8.2
(`gradle/libs.versions.toml:3`). AGP's Unified Test Platform builds a TLS cert
before starting its result listener and dies in BouncyCastle
`[measured on a56, 2026-07-28]`:

```
Execution failed for task ':app:connectedV8DebugAndroidTest'.
> Could not initialize class org.bouncycastle.operator.jcajce.OperatorHelper
...
Caused by: java.lang.NoClassDefFoundError: Could not initialize class org.bouncycastle.operator.jcajce.OperatorHelper
	at org.bouncycastle.operator.jcajce.JcaContentSignerBuilder.<init>(Unknown Source)
	at com.android.build.gradle.internal.testing.utp.TLSUtilsKt.createCert(TLSUtils.kt:58)
	at com.android.build.gradle.internal.testing.utp.UtpTestResultListenerServerRunner.<init>(UtpTestResultListenerServerRunner.kt:67)
...
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.NoClassDefFoundError: org/bouncycastle/asn1/edec/EdECObjectIdentifiers
	at org.bouncycastle.operator.DefaultSignatureNameFinder.<clinit>(Unknown Source)
```

Evidence:
`quick-build/corpus/results/20260728T095928Z-task70-kaspresso-smoke/connected-run2-utp-bc-stacktrace.log:126843-126847, 126888-126896, 127030-127032`.
(That directory's `DEVICE-FINDINGS.md:48` cites the stacktrace as
`connected-run2.log`; the filename on disk is the one above. Stale reference,
content intact.)

This is invisible to CI, because `connectedAndroidTest` appears in **no**
workflow — `instrumentation-test.yml` builds the two APKs and hands them to
Firebase Test Lab (`instrumentation-test.yml:88-129`), never invoking AGP's
connected-test task. It bites only local and on-device work, and it is why the
task-70 smoke walk fell back to `adb install -r` + `adb shell am instrument -w`.
Two consequences worth stating: adding a Kaspresso test to the local loop is
currently blocked, and CI's instrumented coverage is exactly two classes
(`ProjectBuildTestWithKtsGradle`, `ProjectBuildTestWithGroovyGradle`), nightly.

## Blast radius: what could have merged green

Stated plainly, for a change pushed to a feature branch and merged to `stage`:

- **No unit test runs on the PR.** The only push-triggered workflow is
  `debug.yml`, whose checks are `spotlessCheck`, `:plugin-api:apiCheck`, and
  `:app:assembleV8Debug`. A change that breaks every unit test in the repo
  passes all three.
- **The nightly job cannot fail on them either.** `analyze.yml` runs every
  Android module's unit tests, and every one of them is `ignoreFailures = true`.
  The single exception is `:quickbuild-daemon:test`.
- **`:gradle-plugin` is unprotected twice over** — its tests run nowhere, and its
  documented 90% coverage gate has never executed. This is the module that owns
  the Quick Build setup build.
- **Coverage numbers understate reality in a specific direction:** 27 plain-JVM
  modules contribute nothing to `jacocoAggregateReport` or SonarCloud, so their
  coverage — including `:gradle-plugin`'s and `:quickbuild-daemon`'s — is not
  reflected in any dashboard the team looks at.
- **A green nightly does not prove tests ran.** If the SDK vanishes from the
  self-hosted runner and `build/` is warm, `:quickbuild-daemon:test` reports
  UP-TO-DATE and the run is green with zero tests executed.

How much has actually slipped through is `[unmeasured]` — nobody has run the
suite with `ignoreFailures = false` to see what is currently red. **That is the
first thing to do**, before deciding anything below: it converts this from a
theoretical exposure into a list.

## Options, with their costs

1. **Run the suite once with failures honoured, and publish the list.** Cheap,
   no code change (`-PignoreTestFailures=false` equivalent, or a one-off local
   run). Tells you whether item 2 is a five-minute change or a month of repair.
   Do this first.

2. **Replace `ignoreFailures = true` with a gate that still writes coverage.**
   The original intent is satisfiable without the blanket suppression: keep
   `ignoreFailures` while collecting, then add a verification task that reads the
   JUnit XML and fails the build. Or set `ignoreFailures` from a property that
   defaults to `false` and is set `true` only by the coverage job. Cost is the
   backlog item 1 surfaces — this is the change that turns unknown red tests
   into a blocked pipeline, which is the point, but it should be a decision, not
   a surprise.

3. **Run unit tests on push/PR.** `debug.yml` already builds the app; adding a
   test task is small. Cost is PR wall-clock on the self-hosted runner and the
   need for item 2 to be real first — running tests that cannot fail buys
   nothing.

4. **Add `:gradle-plugin:test` (and the other six plain-JVM modules with tests)
   to whatever runs.** Independent of the above and worth doing regardless.

5. **Widen `jacocoAggregateReport` to plain-JVM modules.** Add the
   `build/jacoco/test.exec` path and the plain `classes/kotlin/main` /
   `classes/java/main` dirs, and depend on `test` as well as
   `testV8DebugUnitTest`. Also replace `findByName` with a lazy lookup so a
   missing task is an error rather than a silent drop. Mostly mechanical; the
   coverage number will move, probably down `[inferred]`, which needs saying
   before it surprises anyone.

6. **Make the SDK a declared input** on the toolchain-gated tests
   (`inputs.property` on the resolved SDK path, or `inputs.dir` on
   `build-tools/`), so its disappearance invalidates the task instead of leaving
   it UP-TO-DATE. Small; closes the "green with zero tests executed" hole that
   the fail-if-skipped switch deliberately does not cover.

7. **The UTP/BouncyCastle crash** is a separate investigation — an AGP version
   bump, a BouncyCastle dependency alignment, or accepting the
   `adb am instrument` workaround as the local path. Not on the critical path
   for the items above, but it blocks anyone trying to run instrumented tests
   locally.

## What to take away

- A build that is green because failures are suppressed looks exactly like a
  build that is green because everything passes. The suppression here is one
  line, well-intentioned, and has been quietly in force across every module.
- Two of the repo's own documented quality gates (`:gradle-plugin`'s 90%,
  `REVIEW.md`'s 50%) are documentation only — neither is wired to anything that
  can fail.
- Before changing anything, measure: run the suite honestly once. The size of
  the repair job is the input to every option above and it is currently unknown.
