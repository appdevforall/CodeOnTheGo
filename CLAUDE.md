# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Code On The Go (CoGo) is an Android IDE that runs *on an Android device* — it lets users edit, build, and deploy real Android apps from a phone, offline. It is the actively-maintained successor to AndroidIDE, so the Gradle/AGP namespace is still `com.itsaky.androidide` throughout the codebase even though the product is "Code On The Go". A bundled Termux provides the shell/toolchain, and a `tooling-api` runs a real Gradle build inside the app.

`AGENTS.md` holds operational rules (branch naming, Jira CLI, SonarQube MCP, session/push protocol). Read it — this file does not duplicate it.

## Build & test

All Gradle invocations must be wrapped in `flox` for the correct toolchain:

```bash
flox activate -d flox/local -- ./gradlew <task>
```

Common tasks:
- **Debug APK (arm64, the usual target):** `flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6`
- **Single unit test:** `flox activate -d flox/local -- ./gradlew :module:test --tests "com.itsaky.androidide.SomeTest"`
- **Module unit tests:** `flox activate -d flox/local -- ./gradlew :testing:unit:test`

When a CI/CD job is referenced by name ("the sonar job", "the analyze workflow"), `.github/workflows/*.yml` is the authoritative source for the exact command — don't reverse-engineer it from Gradle tasks. Official/public actions (Sonar upload, releases, deploys) run only in CI, never locally; local runs are verification only.

### ABI product flavors (v7 / v8)

Every Android module carries `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) flavors, so build tasks are `assembleV8Debug`, `assembleV7Release`, etc. — there is **no** flavorless `assembleDebug`. See [ARCHITECTURE.md](ARCHITECTURE.md) (Build & Module Configuration) for how flavors, native asset bundling, and SDK levels are configured.

## Architecture

See **[ARCHITECTURE.md](ARCHITECTURE.md)** — the single source of truth for the module map, layering and data flow, dependency rules, the technology stack (DI, async, persistence, networking), state management, and the testing strategy. Don't re-document those here; update ARCHITECTURE.md instead.

## Project-specific constraints

- **Avoid new dependencies** — the build almost certainly already has what's needed. Check `gradle/libs.versions.toml` and `build.gradle.kts` first.
- **Protect the two Android system bars** in any UI work: the top status bar (clock, notifications, status icons) and the bottom navigation bar (home, back, recents). Don't draw over or intercept them.
- **Plan and size before building.** If a change is estimated at >500 LOC or >10 files, split it into 2+ smaller PRs.
- `.androidide_root` is a sentinel file used by tests to locate the project root — do not delete it.

## Code style

2-space indents everywhere. Java uses Google style (`google-java-format`); Kotlin uses `ktfmt` Google-internal style; XML uses the Android Studio formatter. Branch names must match `.../ADFA-#####` (3–5 digits) — see CONTRIBUTING.md; a pre-commit hook enforces it (`sh ./scripts/install-git-hooks.sh`).
