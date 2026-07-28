# Backlog Item - General Bug: Almost no unit tests run in CI, and the ones that do cannot fail a build

**Ready to file.** Project ADFA · type Bug · component CI/build. Verified against `stage` at
`75483b6eb` and against 40 real workflow runs, 2026-07-28.

## Summary

- **The root build suppresses every unit-test failure.** `build.gradle.kts:72-84` sets
  `ignoreFailures = true` on every Gradle `Test` task in every module, unconditionally —
  no property or env gate. A module whose tests all fail still reports success.
- **Almost nothing runs the tests anyway.** The only workflow that runs any test is the
  nightly `analyze.yml`, and it runs exactly one module: `:quickbuild-daemon:test`, plus
  `sonarqube`. **No push and no PR runs any unit test at all.**
- **That same module is the only one that opts back out of the suppression** — and only
  when `REQUIRE_BUILD_TOOLCHAIN=1`. Every other module's tests are both un-run and
  un-failable.
- **The nightly is not a usable signal either.** 21 of the last 40 scheduled runs failed
  (2026-06-19 → 2026-07-28) — a 52% failure rate, from causes unrelated to tests. A job
  that is red half the time is a job nobody reads.
- **Net effect:** a change breaking unit tests in any module except `:quickbuild-daemon`
  can be pushed, reviewed and merged with every check green — and no scheduled job would
  catch it either, because nothing runs those tests.

## What is actually configured

| # | Finding | Where |
|---|---|---|
| 1 | `ignoreFailures = true` on every Test task, unconditional | `build.gradle.kts:72-84` |
| 2 | Sole exception: `:quickbuild-daemon:test`, only under `REQUIRE_BUILD_TOOLCHAIN=1` | `quickbuild-daemon/build.gradle.kts:80-88` |
| 3 | Nightly runs only `:quickbuild-daemon:test` + `sonarqube`, with `--continue` | `analyze.yml:113` |
| 4 | Nightly is the only workflow running any test; `schedule`-triggered | `analyze.yml:11-13` |
| 5 | The only push-triggered workflow runs zero tests | `debug.yml` |
| 6 | `:gradle-plugin:test` runs in no workflow; its documented 90% coverage gate has never executed | `gradle-plugin/build.gradle.kts:138-145` |
| 7 | Nothing parses test XML — no reporter action, no gate | `analyze.yml` |
| 8 | `jacocoAggregateReport` is v8Debug-only in both directions, so 27 plain-JVM modules contribute neither coverage nor class files | `build.gradle.kts:429-433`, `:458-468`, `:476-483` |

## Evidence from real runs

`gh run list --workflow=analyze.yml --limit 40`, all `schedule`-triggered:

| Window | Runs | Failed | Succeeded |
|---|---|---|---|
| 2026-06-19 → 2026-07-28 | 40 | **21** | 19 |

Sampled failures all die in the same step (`Build and analyze`, exit code 1) from causes
unrelated to unit tests. The run logs also show the JaCoCo aggregate path missing —
*"No files were found with the provided path: build/reports/jacoco/jacocoAggregateReport/"* —
which is finding 8 showing up in production.

**So "the nightly would catch it" does not hold** — not because the nightly passes when
tests fail, but because it never runs those tests, and its own signal is too noisy to act on.

## Why it went unnoticed

- A build green because failures are suppressed looks exactly like a build green because
  everything passes.
- The suppression is unconditional, so no local mode shows a developer the difference.
- The workflow a developer watches on a PR branch runs no tests at all.
- The one job that does run a test is red half the time for other reasons.

## Suggested first step — no code change

Run the suite once with failures honoured and publish the list.

How much has slipped through is `[unmeasured]` — nobody has run with `ignoreFailures = false`
to see what is currently red. That one run turns a theoretical exposure into a concrete
backlog, and its size is the input to every option (gate on PR, gate nightly, fix-then-gate,
or accept and document).

## Scope note

CoGo-wide CI, **not** ADFA-4128 — that work is where this was noticed, not what it is about.
Ironically, `:quickbuild-daemon:test` opting out of the suppression, and the fail-if-skipped
guard around it, were added by that ticket and are the only reason any unit test runs in CI
today.

The suppression itself is one line and well-intentioned: it exists so JaCoCo still writes its
`.exec` when a test fails. Any fix needs to keep that property.
