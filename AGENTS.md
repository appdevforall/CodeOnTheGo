Code On The Go is an Android app which allows the user to edit, build and deploy their own new Android apps. It is an Integrated Development Environment similar to Eclipse or VSCode.

There is at least one Android emulator available. Use `adb devices -l | grep -v offline` to find it. Then use the ANDROID_SERIAL environment variable.

For new persistence, use SQLite or the filesystem — never add Room. (See ARCHITECTURE.md for the full persistence model and the one legacy Room exception.)

Avoid adding dependencies - we probably already have everything loaded that you need. Look in build.gradle.kts for details.

Always plan before building. As part of the plan, estimate the projected size of the change, both number of files and lines of code. When that estimate is greater than 500 lines of code or more than 10 files, you must organize the work into 2 or more smaller change sets, so that the user can help you make 2 or more PRs (Pull Requests).

Always protect the two critical Android interaction locations: (1) the top bar containing the time, notification icons, and status icons, and (2) the bottom bar containing the home button, back button, and app selector. Never put UI elements of our app on top of those Android reserved areas.

## Official/Public Actions Run in CI, Not Locally

Anything official or public-facing must happen through version-controlled GitHub Actions (`.github/workflows/*.yml`) — never from the user's laptop or a local machine. This includes uploading SonarQube/SonarCloud analyses, publishing releases or artifacts, deploying, and pushing to external services. The repo holds tokens like `SONAR_TOKEN` as GitHub secrets scoped to those workflows by design; do not go hunting for them locally. When asked to run something like the sonar task on the local machine, treat it as **local verification only** (run the tests/build to confirm a fix works) and let the official analysis happen in CI.

## Reading Jira Tickets

Prefer the local authenticated `jira` CLI for reading tickets — it is configured via the `JIRA_API_TOKEN`, `JIRA_HOST`, and `JIRA_USER` environment variables. Example: `jira issue view ADFA-1234`. Do NOT start the Atlassian MCP OAuth flow for ticket reads; that heavyweight authentication is unnecessary when the CLI is available.

## SonarQube MCP Server

The sonarqube MCP server runs in a Docker container, so Docker must be running before launching Claude Code. Its first launch pulls a ~225MB image (`mcp/sonarqube:latest`), which exceeds Claude Code's 30s MCP handshake timeout — so the initial connect reports a timeout even though nothing is broken. Pre-pull the image (or let one launch finish) so subsequent `/mcp` reconnects succeed. A `docker system prune` removes the image and reintroduces the slow first launch.

## Resolving CI Job Names

When the user references a CI/CD job by name (e.g., "the sonar job", "the analyze workflow"), read `.github/workflows/*.yml` first. The workflow YAML is the authoritative source for the exact gradle/shell invocation — do not introspect gradle tasks or grep build files to reverse-engineer the command.

## Multi-line git/gh Messages

**Default to writing the body to a tempfile** via the Write tool, then `git commit -F /tmp/msg.txt` or `gh pr create --body-file /tmp/body.md`. Treat heredoc/`--body "$(cat <<EOF ...)"` as the exception, only for short messages with no shell-special characters.

Many characters break the inline `"$(cat <<'EOF' ... EOF)"` pattern: apostrophes trigger `bash: eval: unexpected EOF` (the outer `"$( ... )"` parses the apostrophe even though `<<'EOF'` quotes the heredoc), and backticks or arrows like `→` trigger `bad substitution`. The tempfile approach sidesteps all of them.
