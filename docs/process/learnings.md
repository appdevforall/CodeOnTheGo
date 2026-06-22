# Learnings

## Kotlin LSP test harness
- Disposing the `KtLspTestEnvironment` in a unit test (`env.close()`, or `Disposer.dispose(env.project)`) throws `AssertionError: Write access is allowed inside write-action only`. IntelliJ requires model teardown to run inside a write action. This is why `KtLspTestRule`'s teardown has `env.close()` commented out as "fails in test cases". To dispose deterministically in a test, wrap it: `ApplicationManager.getApplication().runWriteAction { env.close() }`.
- The index/compilation environment lifecycle is racy: background `IndexWorker` coroutines call `PsiManager.findFile(project)` and will crash with `Project is already disposed` if the project is disposed before the workers are stopped. Always stop & join `KtSymbolIndex.close()` (and cancel related scopes) before `Disposer.dispose(...)`.
