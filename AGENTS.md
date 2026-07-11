Code On The Go is an Android IDE — it lets users edit, build, and deploy their own Android apps on-device, like Eclipse or VSCode.

There is at least one Android emulator available. Find it with `adb devices -l | grep -v offline`, then use the `ANDROID_SERIAL` env var.

For new persistence, prefer Room for relational data and the filesystem/preferences for settings; use raw SQLite only for the justified exceptions (prebuilt read-only DBs, performance-critical indexing, cross-boundary schemas). See ARCHITECTURE.md and [ADR 0001](docs/adr/0001-prefer-room-for-persistence.md).

Avoid adding dependencies — we almost certainly already have what you need. Check `build.gradle.kts`.

Plan before building, and size the change (files + LOC). If it will exceed 500 LOC or 10 files, split it into 2+ change sets so the user can land them as separate PRs.

When you change code, update the docs that describe it in the same change — a module's `README.md`, `ARCHITECTURE.md`, or an ADR — so a doc never outlives the API it documents. See REVIEW.md (Code quality). If the doc fix is out of scope, file a ticket rather than let it drift.

Keep docs, tickets, commit messages, and PR descriptions crisp — say it once, lead with the point, cut hedging and restated context. Brevity is the soul of wit; a reader's attention is the scarce resource.

Never draw our UI over the two Android system bars: the top status bar (clock, notifications, status icons) and the bottom navigation bar (home, back, recents).

## Official/Public Actions Run in CI, Not Locally

Anything official or public-facing runs only through version-controlled GitHub Actions (`.github/workflows/*.yml`), never locally — SonarQube/SonarCloud uploads, releases, artifact publishing, deploys, pushes to external services. Tokens like `SONAR_TOKEN` are GitHub secrets scoped to those workflows; don't hunt for them locally. Asked to run e.g. the sonar task locally, treat it as verification only (build/test to confirm a fix) and let the official analysis happen in CI.

## Reading Jira Tickets

Read tickets with the local authenticated `jira` CLI (e.g. `jira issue view ADFA-1234`), configured via `JIRA_API_TOKEN`, `JIRA_HOST`, and `JIRA_USER`. Don't start the Atlassian MCP OAuth flow for reads — it's unnecessary when the CLI works.

## SonarQube MCP Server

The sonarqube MCP server runs in Docker, so Docker must be up before launching Claude Code. Its first launch pulls a ~225MB image (`mcp/sonarqube:latest`) that exceeds Claude Code's 30s MCP handshake timeout — so the first connect reports a timeout though nothing is broken. Pre-pull the image (or let one launch finish) so later `/mcp` reconnects succeed. `docker system prune` removes it and brings back the slow first launch.

## Resolving CI Job Names

When the user names a CI/CD job ("the sonar job", "the analyze workflow"), read `.github/workflows/*.yml` — the YAML is the authoritative gradle/shell invocation. Don't reverse-engineer it from gradle tasks or build files.

## Multi-line git/gh Messages

Default to writing the body to a tempfile via the Write tool, then `git commit -F /tmp/msg.txt` or `gh pr create --body-file /tmp/body.md`. Use heredoc/`--body "$(cat <<EOF ...)"` only for short messages with no shell-special characters.

Many characters break the inline `"$(cat <<'EOF' ... EOF)"` pattern: apostrophes trigger `bash: eval: unexpected EOF` (the outer `"$( ... )"` parses the apostrophe even though `<<'EOF'` quotes the heredoc), and backticks or arrows like `→` trigger `bad substitution`. The tempfile approach sidesteps all of them.
