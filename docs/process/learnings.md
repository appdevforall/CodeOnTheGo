# Learnings

## Spotless / formatting
- The Spotless ratchet (`ratchetFrom = origin/stage`) is **file-level, not line-level**: a one-line edit to a file whose indentation doesn't already conform reformats the *whole* file (e.g. a 4-space layout XML gets fully reindented to tabs). Run `spotlessApply` after editing so the whole-file reformat lands in your commit, not in CI.
- The `.githooks/pre-push/0001-run-spotless` hook runs `spotlessCheck`/`spotlessApply` locally and blocks a push on violations — but only if git hooks are actually enabled: no conflicting global `core.hooksPath` (it silently overrides `.git/hooks`), and a portable dispatcher (`find -executable` is GNU-only and no-ops on macOS BSD find — see ADFA-4833).

## Git / GitHub
- Before pushing a follow-up commit to a community PR, check `gh pr view <n> --json headRepositoryOwner` — the PR head is usually on the contributor's **fork**, so a same-named push to `origin` doesn't touch the PR and just creates a confusing dead branch that has to be deleted.

## Kotlin LSP test harness
- Disposing the `KtLspTestEnvironment` in a unit test (`env.close()`, or `Disposer.dispose(env.project)`) throws `AssertionError: Write access is allowed inside write-action only`. IntelliJ requires model teardown to run inside a write action. This is why `KtLspTestRule`'s teardown has `env.close()` commented out as "fails in test cases". To dispose deterministically in a test, wrap it: `ApplicationManager.getApplication().runWriteAction { env.close() }`.
- The index/compilation environment lifecycle is racy: background `IndexWorker` coroutines call `PsiManager.findFile(project)` and will crash with `Project is already disposed` if the project is disposed before the workers are stopped. Always stop & join `KtSymbolIndex.close()` (and cancel related scopes) before `Disposer.dispose(...)`.
