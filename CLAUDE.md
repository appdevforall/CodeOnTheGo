# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository.

## What this is

Code On The Go (CoGo) is an Android IDE that runs *on the device* — edit, build, and deploy real Android apps from a phone, offline. It's the maintained successor to AndroidIDE, so the Gradle/AGP namespace stays `com.itsaky.androidide` even though the product is "Code On The Go". A bundled Termux provides the shell/toolchain; a `tooling-api` runs a real Gradle build inside the app.

`AGENTS.md` holds the operational rules (branch naming, Jira CLI, SonarQube MCP, session/push protocol) — read it; this file doesn't duplicate it.

## Build & test

Wrap every Gradle invocation in `flox` for the right toolchain:

```bash
flox activate -d flox/local -- ./gradlew <task>
```

- **Debug APK (arm64, the usual target):** `flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6`
- **Single unit test:** `flox activate -d flox/local -- ./gradlew :module:test --tests "com.itsaky.androidide.SomeTest"`
- **Module unit tests:** `flox activate -d flox/local -- ./gradlew :testing:unit:test`

For a CI/CD job named by the user ("the sonar job", "the analyze workflow"), `.github/workflows/*.yml` is the authoritative command — don't reverse-engineer it from Gradle tasks. Official/public actions (Sonar upload, releases, deploys) run only in CI; local runs are verification only.

### ABI product flavors (v7 / v8)

Every module carries `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) flavors, so build tasks are `assembleV8Debug`, `assembleV7Release`, etc. — there is **no** flavorless `assembleDebug`. See [ARCHITECTURE.md](ARCHITECTURE.md) (Build & Module Configuration) for flavors, native asset bundling, and SDK levels.

## Architecture

See **[ARCHITECTURE.md](ARCHITECTURE.md)** — the single source of truth for the module map, layering/data flow, dependency rules, tech stack (DI, async, persistence, networking), state management, and testing strategy. Don't re-document those here; update ARCHITECTURE.md.

## Project-specific constraints

- **Avoid new dependencies** — the build almost certainly already has what's needed. Check `gradle/libs.versions.toml` and `build.gradle.kts` first.
- **Protect the two Android system bars** in any UI work: the top status bar (clock, notifications, status icons) and the bottom navigation bar (home, back, recents). Don't draw over or intercept them.
- **Plan and size before building.** Estimated >500 LOC or >10 files → split into 2+ PRs.
- `.androidide_root` is a sentinel file tests use to locate the project root — don't delete it.

## Code style

2-space indents everywhere. Java: Google style (`google-java-format`); Kotlin: `ktfmt` Google-internal style; XML: Android Studio formatter. Branch names must match `.../ADFA-#####` (3–5 digits) — see CONTRIBUTING.md; a pre-commit hook enforces it (`sh ./scripts/install-git-hooks.sh`).
