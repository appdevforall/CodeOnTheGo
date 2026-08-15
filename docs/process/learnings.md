# Learnings

## Spotless / formatting
- The Spotless ratchet (`ratchetFrom = origin/stage`) is **file-level, not line-level**: a one-line edit to a file whose indentation doesn't already conform reformats the *whole* file (e.g. a 4-space layout XML gets fully reindented to tabs). Run `spotlessApply` after editing so the whole-file reformat lands in your commit, not in CI.
- The `.githooks/pre-push/0001-run-spotless` hook runs `spotlessCheck`/`spotlessApply` locally and blocks a push on violations — but only if git hooks are actually enabled: no conflicting global `core.hooksPath` (it silently overrides `.git/hooks`), and a portable dispatcher (`find -executable` is GNU-only and no-ops on macOS BSD find — see ADFA-4833).

## Git / GitHub
- Before pushing a follow-up commit to a community PR, check `gh pr view <n> --json headRepositoryOwner` — the PR head is usually on the contributor's **fork**, so a same-named push to `origin` doesn't touch the PR and just creates a confusing dead branch that has to be deleted.

## Jira CLI
- On a snap install, the Jira CLI binary is named `jira-cli`, not `jira` — running `jira issue view ...` fails with `Permission denied (os error 13)` (a misleading error for "command not found"). If `jira` isn't on PATH, try `jira-cli` before assuming the CLI itself is broken.

## Android / Kotlin
- `Handler.removeCallbacks(Runnable)` only removes callbacks posted by that *exact* `Handler` instance, not just the same `Looper` — `Handler(Looper.getMainLooper()).removeCallbacks(x)` won't cancel something posted via a *different* `Handler` bound to the same looper. Any post/cancel pair needs to share one `Handler` instance (see `TaskExecutor.mainThreadHandler`, added when replacing blankj's `ThreadUtils.getMainHandler()`).

## Reverse-engineering a library before porting it
- When writing a same-name drop-in for a third-party utility (to remove the dependency without changing call-site behavior), don't guess its semantics from memory/docs — extract the AAR's `classes.jar` and run `javap -c` against the actual bytecode to confirm exact chaining/wrapping behavior, especially for fluent/reflection-style APIs where a subtle mismatch (e.g., wrapping a field's *declared* type vs. its *runtime* class) changes behavior at existing call sites.

## MockK
- Migrating a mocked call from a Java static method (`mockkStatic(SomeClass::class)`) to a Kotlin top-level extension function requires `mockkStatic("com.package.FileNameKt")` (the compiled JVM facade class name) instead — `mockkStatic(ExtensionReceiver::class)` doesn't work for extension functions.

## Measuring a real before/after delta
- To measure an actual size/perf delta for a change (not just estimate it), use `git worktree add <path> <base-commit>`, build there, and diff the artifacts — avoids disturbing the current working tree or stashing.

## SQLite CLI scripting
- The sqlite3 CLI's `.system` dot-command can hit a content-dependent shell-parsing failure when a line chains multiple operators (`;`, `&&`, `||`, parentheses) — reproduces for some strings and not others, so it won't show up in a quick smoke test. Keep each `.system` line to one plain `command | pipe > file`.
- `.bail on` is required for a `BEGIN;...COMMIT;`-wrapped script to actually be atomic: without it, a mid-script SQL error prints to stderr but the script keeps going, including reaching the final `COMMIT`, which persists whatever succeeded before the error. `.bail` also can't see `.system` shell failures directly — if a step's success depends on a shell command's exit status, assert it in SQL (e.g. a temp table with a `CHECK` constraint) rather than relying on `.bail` to catch it.
- Don't write a `.system` command's output to a fixed, guessable filename directly under `/tmp` (CWE-377) — another local user could pre-plant a symlink there or race the write against your later read. Create an owner-only working directory instead (`rm -rf` it, then `mkdir -m 700` it — the mode is set atomically at creation, so there's no window where it's briefly wider), write everything under that, and remove it when done. `mkdir` itself can fail (e.g. another user recreates the path between the `rm -rf` and the `mkdir`) — that's a `.system` failure `.bail` won't catch either, so assert the directory's mode in SQL before trusting it, the same way you'd guard the Brotli step above. A fresh `mktemp -d` per run would be even better, but it doesn't fit this script shape: each `.system` line is its own subshell, so a path it generates can't be carried into later `.system`/`READFILE()` calls without writing it to another fixed, guessable file first.

## Kotlin LSP test harness
- Disposing the `KtLspTestEnvironment` in a unit test (`env.close()`, or `Disposer.dispose(env.project)`) throws `AssertionError: Write access is allowed inside write-action only`. IntelliJ requires model teardown to run inside a write action. This is why `KtLspTestRule`'s teardown has `env.close()` commented out as "fails in test cases". To dispose deterministically in a test, wrap it: `ApplicationManager.getApplication().runWriteAction { env.close() }`.
- The index/compilation environment lifecycle is racy: background `IndexWorker` coroutines call `PsiManager.findFile(project)` and will crash with `Project is already disposed` if the project is disposed before the workers are stopped. Always stop & join `KtSymbolIndex.close()` (and cancel related scopes) before `Disposer.dispose(...)`.
