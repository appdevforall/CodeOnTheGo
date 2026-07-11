# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository. This file is self-contained — Claude Code reads it on startup; the operational rules below live here, not in a separate file.

## What this is

Code On The Go (CoGo) is an Android IDE that runs *on the device* — edit, build, and deploy real Android apps from a phone, offline. It's the maintained successor to AndroidIDE, so the Gradle/AGP namespace stays `com.itsaky.androidide` even though the product is "Code On The Go". A bundled Termux provides the shell/toolchain; a `tooling-api` runs a real Gradle build inside the app.

## Build & test

Wrap every Gradle invocation in `flox` for the right toolchain:

```bash
flox activate -d flox/local -- ./gradlew <task>
```

- **Debug APK (arm64, the usual target):** `flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6`
- **Single unit test:** `flox activate -d flox/local -- ./gradlew :module:test --tests "com.itsaky.androidide.SomeTest"`
- **Module unit tests:** `flox activate -d flox/local -- ./gradlew :testing:unit:test`

When the user names a CI/CD job ("the sonar job", "the analyze workflow"), read `.github/workflows/*.yml` — the YAML is the authoritative gradle/shell invocation. Don't reverse-engineer it from gradle tasks or build files.

### ABI product flavors (v7 / v8)

Every module carries `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) flavors, so build tasks are `assembleV8Debug`, `assembleV7Release`, etc. — there is **no** flavorless `assembleDebug`. See [ARCHITECTURE.md](ARCHITECTURE.md) (Build & Module Configuration) for flavors, native asset bundling, and SDK levels.

### Emulator / device

At least one Android emulator or device is available. Find it with `adb devices -l | grep -v offline`, then target it with the `ANDROID_SERIAL` env var.

## Architecture

See **[ARCHITECTURE.md](ARCHITECTURE.md)** — the single source of truth for the module map, layering/data flow, dependency rules, tech stack (DI, async, persistence, networking), state management, and testing strategy. Don't re-document those here; update ARCHITECTURE.md.

## Project-specific constraints

- **Avoid new dependencies** — the build almost certainly already has what's needed. Check `gradle/libs.versions.toml` and `build.gradle.kts` first.
- **Persistence:** prefer **Room** for relational data and the filesystem/preferences for settings; raw SQLite only for justified exceptions — see [ADR 0001](docs/adr/0001-prefer-room-for-persistence.md).
- **Protect the two Android system bars** in any UI work: the top status bar (clock, notifications, status icons) and the bottom navigation bar (home, back, recents). Don't draw over or intercept them.
- **Plan and size before building.** Estimated >500 LOC or >10 files → split into 2+ PRs.
- **Keep docs in step with code.** When you change code, update the docs that describe it in the same change — a module's `README.md`, `ARCHITECTURE.md`, or an ADR — so a doc never outlives the API it documents (see REVIEW.md, Code quality). If the doc fix is out of scope, file a ticket rather than let it drift.
- `.androidide_root` is a sentinel file tests use to locate the project root — don't delete it.
- Avoid http or https links which go off-device. When such links are unavoidable, warn the user beforehand and offer to cancel the action.

## Code style

2-space indents everywhere. Java: Google style (`google-java-format`); Kotlin: `ktfmt` Google-internal style; XML: Android Studio formatter. Branch names must match `.../ADFA-#####` (3–5 digits) — see CONTRIBUTING.md; a pre-commit hook enforces it (`sh ./scripts/install-git-hooks.sh`).

Keep docs, tickets, commit messages, and PR descriptions crisp — say it once, lead with the point, cut hedging and restated context. Brevity is the soul of wit; a reader's attention is the scarce resource.

## Operational rules

### Official/public actions run in CI, not locally

Anything official or public-facing runs only through version-controlled GitHub Actions (`.github/workflows/*.yml`), never locally — SonarQube/SonarCloud uploads, releases, artifact publishing, deploys, pushes to external services. Tokens like `SONAR_TOKEN` are GitHub secrets scoped to those workflows; don't hunt for them locally. Asked to run e.g. the sonar task locally, treat it as verification only (build/test to confirm a fix) and let the official analysis happen in CI.

### Reading Jira tickets

Read tickets with the local authenticated `jira` CLI (e.g. `jira issue view ADFA-1234`), configured via `JIRA_API_TOKEN`, `JIRA_HOST`, and `JIRA_USER`. Don't start the Atlassian MCP OAuth flow for reads — it's unnecessary when the CLI works.

### SonarQube MCP server

The sonarqube MCP server runs in Docker, so Docker must be up before launching Claude Code. Its first launch pulls a ~225MB image (`mcp/sonarqube:latest`) that exceeds Claude Code's 30s MCP handshake timeout — so the first connect reports a timeout though nothing is broken. Pre-pull the image (or let one launch finish) so later `/mcp` reconnects succeed. `docker system prune` removes it and brings back the slow first launch.

### Multi-line git/gh messages

Default to writing the body to a tempfile via the Write tool, then `git commit -F /tmp/msg.txt` or `gh pr create --body-file /tmp/body.md`. Use heredoc/`--body "$(cat <<EOF ...)"` only for short messages with no shell-special characters.

Many characters break the inline `"$(cat <<'EOF' ... EOF)"` pattern: apostrophes trigger `bash: eval: unexpected EOF` (the outer `"$( ... )"` parses the apostrophe even though `<<'EOF'` quotes the heredoc), and backticks or arrows like `→` trigger `bad substitution`. The tempfile approach sidesteps all of them.
