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
- **Fast iteration:** during multi-file/multi-module changes, verify with targeted `:module:compileV8DebugKotlin`/`compileV8DebugJavaWithJavac` invocations (batch several modules into one Gradle call) rather than a full assemble. Reserve `:app:assembleV8Debug` for final end-to-end verification — it's slow (multi-minute) in this multi-module project, and running it after every small change adds up.
- **Long-running commands: narrate them, and background only the read-only ones.** Anything that can exceed ~60s — `assembleV8Debug`, a full test sweep, a cold Gradle invocation of any kind (daemon start plus configuring this many modules is ~20s before any task runs, and far worse under memory pressure), and `git push`, which runs Spotless through the hook — gets a line before it starts saying what is running and roughly how long it takes, and a status line every couple of minutes while it runs: elapsed time, the last output line, whether it is still progressing. A silent terminal is indistinguishable from a hang, and saying which it is is your job, not the user's to ask.

	Background a command only if it does **not** write to the worktree. A build or `spotlessCheck` is safe. A *test* run is not automatically safe: `:gradle-plugin:test` writes under `tests/` (see the `git add -A` rule below), so check what a task writes before backgrounding it rather than assuming tests are read-only. `spotlessApply` and `git push` (which runs it through the hook) rewrite tracked files, so editing or staging while one is in flight races it — keep those in the foreground and narrate the wait instead.

When the user names a CI/CD job ("the sonar job", "the analyze workflow"), read `.github/workflows/*.yml` — the YAML is the authoritative gradle/shell invocation. Don't reverse-engineer it from gradle tasks or build files.

### ABI product flavors (v7 / v8)

Every module carries `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) flavors, so build tasks are `assembleV8Debug`, `assembleV7Release`, etc. — there is **no** flavorless `assembleDebug`. See [ARCHITECTURE.md](ARCHITECTURE.md) (Build & Module Configuration) for flavors, native asset bundling, and SDK levels.

### Emulator / device

At least one Android emulator or device is available. Find it with `adb devices -l | grep -v offline`, then target it with the `ANDROID_SERIAL` env var. Note the app is **arm-only** (`v7`/`v8` flavors, no x86) — an x86_64 emulator can't run it (not always even via a translation layer), so testing often needs a **physical arm device** or an arm-translation emulator.

**Font-scale check.** Read the current value first so you can put it back. Each change recreates the activity (only `EditorActivityKt` declares `fontScale` in `configChanges`), so this doubles as a state-restoration test:

```bash
orig=$(adb shell settings get system font_scale | tr -d '\r')   # "null" if never set
trap 'if [ "$orig" = null ]; then
        adb shell settings delete system font_scale
      else
        adb shell settings put system font_scale "$orig"
      fi' EXIT

adb shell settings put system font_scale 2.0
adb exec-out screencap -p > /tmp/scale-2.0.png
```

At 2.0, look for text cut off mid-word, labels overrunning their control, actions pushed off the bottom with no way to scroll to them, and overlapping rows.

## Architecture

See **[ARCHITECTURE.md](ARCHITECTURE.md)** — the single source of truth for the module map, layering/data flow, dependency rules, tech stack (DI, async, persistence, networking), state management, and testing strategy. Don't re-document those here; update ARCHITECTURE.md.

## Project-specific constraints

- **Avoid new dependencies** — the build almost certainly already has what's needed. Check `gradle/libs.versions.toml` and `build.gradle.kts` first.
- **Persistence:** prefer **Room** for relational data and the filesystem/preferences for settings; raw SQLite only for justified exceptions — see [ADR 0001](docs/adr/0001-prefer-room-for-persistence.md).
- **Don't treat a large binary asset's on-disk content as ground truth without checking its provenance first.** Run `git ls-files <path>` / `git check-ignore -v <path>`, and grep the build files for how it's provisioned, before relying on its current schema or row content. Several assets here (e.g. `assets/documentation.db`, and the SDK/bootstrap/Gradle zips alongside it) are `.gitignore`d and fetched by a Gradle task from an external URL (see the `Asset(...)` list in `app/build.gradle.kts`) — a locally-cached copy can be stale independent of git commit history and silently diverge from the maintained original.
- **Protect the two Android system bars** in any UI work: the top status bar (clock, notifications, status icons) and the bottom navigation bar (home, back, recents). Don't draw over or intercept them.
- **Every screen must survive 2x font scale.** Users with low vision run large system fonts, and a screen that clips or hides content at 2.0 is broken for them. Verify any new or changed screen at font scale **1.0 and 2.0** (see Build & test, Emulator / device) and say in the PR that you did. Text grows, so: use `sp` for text and `dp` for spacing — never an `sp` dimen as a margin or padding; don't box text in a fixed `dp` height or width; give content that can grow somewhere to scroll; and reserve `maxLines`/`singleLine`/`ellipsize` for text that is genuinely disposable.
- **Plan and size before building.** Prefer **one PR per ticket/use case** — don't force-split a coherent change (splitting has its own overhead when later edits span the pieces). When a change is large, break it into **reviewable commits** — mechanical/refactor commits separate from behavioral ones — and offer review-by-commit. Treat ~500 LOC / ~10 files as a signal to reach for that commit structure, not a hard cap; the ceiling rises as LLM-assisted review matures. For staged multi-commit refactors (e.g. removing a dependency across many files/modules), order stages easiest-to-hardest and independently compile/test each stage (see Build & test's fast-iteration guidance) before moving to the next, so a failure is isolated to the stage that caused it.
- **Before designing a way to make a costly operation cheaper, ask whether the operation is needed at all.** When a problem's severity scales with how often something happens — connections opened per second, requests issued per page, calls made per frame — eliminating the operations usually beats optimizing each one. Check whether the platform offers a way to do the work without them before writing the plan for the optimized version. ADFA-5172/5176 is the cautionary case: the server opened one TCP connection per documentation request to our own process, and `WebViewClient.shouldInterceptRequest` served the same content with no connection at all, which HTTP keep-alive would only have made cheaper per connection.
- **Keep docs in step with code.** When you change code, update the docs that describe it in the same change — a module's `README.md`, `ARCHITECTURE.md`, or an ADR — so a doc never outlives the API it documents (see REVIEW.md, Code quality). If the doc fix is out of scope, file a ticket rather than let it drift.
- `.androidide_root` is a sentinel file tests use to locate the project root — don't delete it.
- Avoid http or https links which go off-device. When such links are unavoidable, warn the user beforehand and offer to cancel the action.

## Verify before you claim

Four independent reviews of work already reported as verified each found a real defect in it. Before calling a change done, verified, or behaviour-neutral:

- **Sweep the siblings, not just the site in front of you.** After a fix, grep for the other places the same pattern lives — the `UPDATE` beside the `INSERT` you fixed, the third entry point beside the two you guarded, the `Content-Type` *parameters* beside the media type you sanitised. Say in the PR which sites you checked, and which you deliberately left alone.
- **Prove the regression test fails without the fix.** Revert the fix, run the new test, confirm it fails *for the reason it is named for*, then restore. A test that passes against the unfixed code pins nothing. Watch for expectations that all coincide with one boundary value: if every case equals the minimum, a `MIN()` stub passes the whole suite.
- **Match the handler to the failure the change exists to fix.** `catch (Exception)` does not catch an `Error`. A guard on two of three call sites is not a guard.
- **Every claim needs its check.** "No behaviour change", "this MIME type has rows in the shipped database", "that tool logs a warning" are all testable — run the query, read the sibling repo's source, diff the commits — or don't write them. Re-read the PR body before pushing: a description that was true at commit 1 is often false by commit 3.

## Code style

**Tabs** for indentation, **LF** line endings — enforced by **Spotless**. The `ratchetFrom = origin/stage` ratchet is **file-level, not line-level**: it checks every file that differs from `origin/stage` and reformats each such file *in full*, so editing even one line of a file whose existing indentation doesn't conform (e.g. a layout XML using 4 spaces) pulls the **whole file** under the ratchet and requires reindenting it to tabs — a one-line edit can become a whole-file reformat. That reformat is reviewer noise: commit it **standalone** (`style: spotless reformat, no functional change`), never `--amend`ed into a behavioral commit and never mixed with one. Better, do it *first* — append a blank line to each file you are about to touch (this enrolls it in the ratchet; `endWithNewline()` strips it back out), run `spotlessApply`, commit that, then make the functional change against an already-conforming file. Java uses the **Eclipse** formatter (`spotless.eclipse-java.xml`, with member sorting + import ordering); Kotlin and `*.gradle.kts` use **ktlint**; XML uses the **Eclipse WTP** formatter. Run `./gradlew spotlessApply` to fix formatting before pushing — the `.githooks` pre-push hook does this automatically once hooks are installed and enabled (`sh ./scripts/install-git-hooks.sh`, no conflicting `core.hooksPath`). Branch names must match `.../ADFA-#####` (3–5 digits) — see CONTRIBUTING.md; a pre-commit hook enforces it (`sh ./scripts/install-git-hooks.sh`).

Keep docs, tickets, commit messages, and PR descriptions crisp — say it once, lead with the point, cut hedging and restated context. Brevity is the soul of wit; a reader's attention is the scarce resource.

Brevity governs the artifact, not the reasoning. When you recommend something to the user — a design choice, a library, a way to split a PR — give the one-line *why* and the alternative you rejected, not the conclusion alone. A bare recommendation costs a round-trip of "tell me more", and the user should never have to ask twice to see the trade-off.

**Code comments** follow the same discipline:
- Short and to-the-point: comment the non-obvious *why* (a workaround, a constraint, a subtle invariant), not what the code already states. Cut restated context.
- **No separator or decorative comments.** No banner bars, `// ====` rules, or ASCII-art dividers; let structure, naming, and small functions carry organization.
- **Prefer ASCII.** Don't use a non-ASCII character when an ASCII equivalent reads the same: `->` not the arrow glyph, `-` for a dash, straight quotes not curly. Scope: code and code comments. Exempt: Markdown prose, and diagrams/ASCII-art where a non-ASCII glyph does real visual work (e.g. the box-drawing in ARCHITECTURE.md's data-flow diagram) — keep those.

## Branch model

- **`main`** — release branch. It only ever receives merges **from `stage`**; nothing else targets `main` directly.
- **`stage`** — the protected default/integration branch (`origin/HEAD`) and the base for the Spotless ratchet. All feature work lands here first.
- **Feature branches** — always branched **from `stage`** and opened as PRs back **into `stage`**. Name internal branches `.../ADFA-#####` (see CONTRIBUTING.md; external community contributors use the `community/` prefix instead).

Flow: branch off `stage` → PR into `stage` → `stage` merges to `main` for release. Never branch a feature off `main`, and never target `main` with a feature PR.

## Operational rules

### Official/public actions run in CI, not locally

Anything official or public-facing runs only through version-controlled GitHub Actions (`.github/workflows/*.yml`), never locally — SonarQube/SonarCloud uploads, releases, artifact publishing, deploys, pushes to external services. Tokens like `SONAR_TOKEN` are GitHub secrets scoped to those workflows; don't hunt for them locally. Asked to run e.g. the sonar task locally, treat it as verification only (build/test to confirm a fix) and let the official analysis happen in CI.

### Jira tickets — read, and keep updated

**Read the ticket before you start work.** Any authenticated route is fine — the local `jira` CLI (`jira issue view ADFA-1234`), the Atlassian MCP server, or the REST API. Pick whatever is already working; don't burn time switching tools.

**Post progress as you go.** The team wants visibility into in-progress work, not just a final drop. When you start a ticket, hit a notable blocker or decision, or finish a meaningful chunk, add a short comment (`jira issue comment add ADFA-#### "…"`). Keep it crisp — status, what changed, what's next.

**Status progression.** Every ADFA issue type moves through the same states:

`To Do` → `In Progress` → `Code review` → `QA` → `Ready to merge` → `Done`

The names are case-sensitive as written — note the lowercase `review` and `merge`. When a ticket looks ready to advance, **offer** to move it; don't transition it silently. Typical triggers: you begin work → `In Progress`; the PR is open → `Code review`; a review comes back with no outstanding critical, high, or medium findings → `QA`; QA passes → `Ready to merge`.

**Steps to QA.** The `Steps to QA` field is what QA works from, so it matters. When it's empty, offer to write it for the user as Gherkin — Given / When / Then. When you test a ticket yourself, read `Steps to QA` and cover it *in addition to* whatever the user asked you to check.

### SonarQube MCP server

The sonarqube MCP server runs in Docker, so Docker must be up before launching Claude Code. Its first launch pulls a ~225MB image (`mcp/sonarqube:latest`) that exceeds Claude Code's 30s MCP handshake timeout — so the first connect reports a timeout though nothing is broken. Pre-pull the image (or let one launch finish) so later `/mcp` reconnects succeed. `docker system prune` removes it and brings back the slow first launch.

### Staging commits — no `git add -A`

Stage by explicit path (`git add path/one path/two`). `git add -A` sweeps in whatever else the working tree happens to hold, untracked files included — regenerated test fixtures, multi-MB binaries, files carrying machine-local absolute paths — and buries them in an unrelated commit. `git add -u` is narrower (tracked paths only, and it stages deletions), but it still picks up any tracked file something rewrote behind your back, which is how a 12 MB regenerated fixture reached a commit here. The untracked half of that risk is live here: a `:gradle-plugin:test` run leaves files under `tests/`, and `tests/test-home` is not currently ignored. Run `git status --short` first and account for every line; if a regenerated file genuinely belongs in the change, say so in the commit message.

### Multi-line git/gh messages

Default to writing the body to a tempfile via the Write tool, then `git commit -F /tmp/msg.txt` or `gh pr create --body-file /tmp/body.md`. Use heredoc/`--body "$(cat <<EOF ...)"` only for short messages with no shell-special characters.

Many characters break the inline `"$(cat <<'EOF' ... EOF)"` pattern: apostrophes trigger `bash: eval: unexpected EOF` (the outer `"$( ... )"` parses the apostrophe even though `<<'EOF'` quotes the heredoc), and backticks or arrows like `→` trigger `bad substitution`. The tempfile approach sidesteps all of them.
