# Design: Organize Imports code action for the Kotlin LSP (ADFA-4614)

- **Ticket:** [ADFA-4614](https://appdevforall.atlassian.net/browse/ADFA-4614) — *K2-LSP: Code action: Organize imports*
- **Parent:** ADFA-3317 (Integrate K2 compiler with LSP) · **Related:** ADFA-3323 (code-action framework, Done)
- **Branch / worktree:** `feat/ADFA-4614` at `.claude/worktrees/ADFA-4614` (currently identical to its base `feat/ADFA-3322`, which introduced the file-management refactor this design depends on).
- **Date:** 2026-07-09

## Goal

Add a self-contained code action for `.kt` files that rewrites the file's import list into canonical order and removes imports that are not used, computed entirely in-process via the Kotlin Analysis API. It has **no** dependency on unused-import diagnostics (none exist in the LSP, and the FIR compiler defines no `UNUSED_IMPORT` factory) and cannot use IntelliJ's import optimizer (`KotlinOptimizeImportsFacility` / `KotlinImportOptimizer` are IDEA-plugin classes, not on our classpath — the vendored JAR is only the compiler + Analysis API standalone stack).

## Non-goals (explicitly out of scope)

- Wildcard **expansion** (`import foo.*` → explicit named imports).
- Wildcard **collapse** / count-threshold behavior (many named imports → `foo.*`).
- A standalone unused-import diagnostic or editor squiggle.
- Any user-facing configuration/settings surface.

## User-facing behavior

- Always present in the code-actions menu for Kotlin files (parity with Java's `OrganizeImportsAction`), titled from the existing string `R.string.action_organize_imports` ("Organize imports"), reachable from `lsp/kotlin` via `com.itsaky.androidide.resources.R`.
- On invoke: sorts the surviving imports into canonical order and drops unused/redundant imports, applied as a **single whole-block-replace edit**.
- **No-op when already organized:** if the regenerated import block is byte-identical to the current one, emit no edit (do not dirty the buffer).
- Runs off the UI thread; on any analysis failure or cancellation it aborts with **zero** edits — never a partial rewrite.
- **No `CMD_FORMAT_CODE`** is triggered after applying edits (output is already canonical; reformatting the whole file could reflow unrelated code). This intentionally differs from `AddImportAction`, which does format.

## Architecture & placement

All paths are within the `feat/ADFA-4614` worktree.

- **New action:** `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt`, extending `BaseKotlinCodeAction` (a plain `EditorActionItem`, `location = EDITOR_CODE_ACTIONS`, `requiresUIThread = false`, already gated on `DocumentUtils.isKotlinFile`). `titleTextRes = R.string.action_organize_imports`.
- **Registration:** add `OrganizeImportsAction()` to `KotlinCodeActionsMenu.actions` (`lsp/kotlin/.../KotlinCodeActionsMenu.kt`, at the package root). It is a plain menu action — **not** diagnostic-driven, so `prepare()` needs no `DiagnosticItem` gate.
- **Core algorithm lives in a pure, testable helper** (see "Design for testability"), e.g. `utils/ImportOrganizer.kt`, invoked by the action. The action wrapper stays thin.

### File access (post-ADFA-3322 refactor — the key change from `stage`)

The old `getOpenedKtFile` / `openedFiles` map and manual `parser.createFile` reparsing are **gone**. The action obtains its `KtFile` through the new single-flight, version-stamped cache:

```kotlin
// off the UI thread (execAction is suspend), NOT inside project.read:
val ktFile = env.ktSymbolIndex.getCurrentKtFile(path).get() ?: return /* no-op */
```

- `getCurrentKtFile(path: Path): CompletableFuture<KtFile?>` (in `compiler/index/KtSymbolIndex.kt`) reparses live document contents (`FileManager.getDocumentContents`) internally, so **callers do not reparse**.
- **Deadlock rule (mandatory):** fetch the `KtFile` via `.get()`/`.await()` *first*, then do all analysis inside `env.project.read { … }`. The blocking fetch must never run inside `project.read` — its refresh needs `project.write`, and the RW lock is non-upgradeable.

### Analysis entry point

Do **not** call `analyze()` directly. Use the repo wrapper:

```kotlin
env.project.read {
  analyzeMaybeDangling(ktFile) { /* KaSession receiver */ ... }
}
```

- `analyzeMaybeDangling` (in `compiler/modules/KtFileExts.kt`) serializes all Analysis-API access under `withAnalysisLock` and handles dangling/copy files.
- Analysis runs under the shared `read` lock — it must never call `write`.
- **No `KaLifetimeOwner` may escape the analyze block.** Extract plain data (FqName strings, package strings, booleans) *inside* the block; return only that.

### Edit generation & dispatch

- Build **one** `TextEdit` whose range covers the existing import list (compute via the `TextRange.toRange(containingFile)` helper in `utils/EditExts.kt`) and whose `newText` is the regenerated block. If there is no import list, no-op.
- `execAction` computes the `TextEdit`; `postExec` wraps it in a single `CodeActionItem(title, changes = listOf(DocumentChange(file = nioPath, edits = edits)), kind = CodeActionKind.QuickFix)` and calls `data.languageClient?.performCodeAction(item)` — the `AddImportAction` dispatch pattern, but with **no chooser dialog** (single action) and **no `Command.CMD_FORMAT_CODE`**.
  - *Note:* use `CodeActionKind.SourceOrganizeImports` if that constant exists in `lsp/models` `CodeActions.kt`; otherwise `QuickFix`. Confirm during implementation.

## Core algorithm — semantic used-import detection

Inside a single `analyzeMaybeDangling(ktFile) { … }` pass (under `project.read`):

### 1. Collect the "used" sets

Traverse the file body (**excluding** the import list and package directive) and accumulate, as plain data:

- `usedFqNames: Set<String>` — importable FqName of each used top-level/extension callable, class, object, typealias, enum entry.
- `usedPackages: Set<String>` — the parent package (or containing object FqName) of each used importable symbol (for wildcard matching).

Sources that must all be covered:

- **Name references** (`KtNameReferenceExpression` / type references): resolve to symbol → derive importable FqName. (Exact API confirmed by the Step 0 spike — see Risks.)
- **Convention / operator call sites** (no textual name; resolved via `resolveToCall()?.successfulFunctionCallOrNull()?.symbol`). The implementation MUST enumerate every category:
  - binary & unary operators (`a + b`, `-a`, `a in b`, comparisons via `compareTo`, `==` via `equals`)
  - array access get/set (`a[i]`, `a[i] = v`)
  - `invoke` conventions (`a()` where `a` is not a function name)
  - `for` loops (`iterator`, `hasNext`, `next`)
  - destructuring (`val (x, y) = p` → `component1`, `component2`, …)
  - delegated properties (`by lazy` → `getValue` / `setValue` / `provideDelegate`)

Only **top-level / extension callables, classifiers, and enum entries** credit an import; member callables (imported via their class) do not. Fully-qualified references must not credit the corresponding import.

### 2. Classify each import directive

For each `KtImportDirective` in `ktFile.importDirectives`:

- **Named / aliased** (`import a.b.C`, `import a.b.C as D`): keep iff `importedFqName ∈ usedFqNames`. (An aliased reference resolves to the real FqName, so matching on `importedFqName` handles aliases.)
- **Wildcard** (`import foo.*`): keep iff `usedPackages` contains its parent FqName (package or object). **Unused wildcards are removed**; none are expanded.
- **Exact duplicates:** collapse to the first occurrence.
- **Redundant even if resolvable — removed** (confirmed in scope):
  - covered by default imports (`kotlin.*`, `kotlin.collections.*`, etc. — via `KaDefaultImports` data available on the classpath), and
  - same-package imports (imported symbol lives in the file's own package).

### 3. KDoc conservative-keep (accepted approximation)

KDoc link references (`[Foo]`) are not part of normal resolution. To avoid removing a doc-only import, **keep** any import whose short name/alias appears as a KDoc link identifier. This may under-remove in rare cases; it never wrong-removes.

### 4. Canonical order

The surviving imports are emitted as a **single block**, **pure lexicographic sort of the full import path string** (alias suffix included), with **no grouping and no blank lines** — the Kotlin / ktlint / IntelliJ default, consistent with the existing alphabetical insertion in `EditExts.insertImport`. (`foo.*` sorts before `foo.Bar` under string comparison, which is correct.)

## Edge cases & failure handling

- No import list, or empty body after the package directive → no-op.
- File with syntax errors: still attempt; unresolved references simply don't join the used set, so we lean toward **keeping** imports (never remove on failed/ambiguous resolution).
- Cancellation (cancel checker) → abort, no edit.
- Regenerated block identical to current → no edit (no-op).
- Whole-block replacement discards comments/blank lines interleaved *within* the import section (rare; IntelliJ likewise reorders freely). Accepted.

## Design for testability

- The algorithm is a pure helper — e.g. `ImportOrganizer` — taking `(KaSession, KtFile)` (plus any needed env data) and returning the removable-import decision and the regenerated block text / `TextEdit`. No Android `ActionData`/`Context` dependency.
- The `OrganizeImportsAction` is a thin wrapper: `execAction` fetches the `KtFile`, enters `project.read` + `analyzeMaybeDangling`, calls the helper, returns the `TextEdit`; `postExec` dispatches.

## Testing

- **Framework:** Robolectric + JUnit4, mirroring `KtLspTest` (base class with `createSourceFile(...)`, `env.analyze { … }`) and `CurrentKtFileCacheTest` (document open/edit + `getCurrentKtFile` plumbing). There is no existing code-action test to copy, so tests target the `ImportOrganizer` helper directly.
- **Gradle task:** `./gradlew :lsp:kotlin:testV7DebugUnitTest` (flavored Android library; V7 = `armeabi-v7a`).
- **Cases:**
  - unused named import removed; used named import kept
  - alias used / alias unused
  - operator import kept (`a + b`)
  - delegate import kept (`by lazy`)
  - destructuring component import kept
  - `for`/`iterator`, array-access, `invoke` convention imports kept
  - wildcard removed when unused; wildcard kept when used
  - exact duplicate collapsed
  - default-import-redundant removed; same-package-redundant removed
  - KDoc-only import kept (conservative)
  - correct canonical sort order (including `foo.*` vs `foo.Bar`)
  - no-op when already canonical
  - syntax-error file → conservative (nothing wrongly removed)

## Implementation plan (high level)

- **Step 0 — De-risking spike (throwaway).** In the `lsp/kotlin` test harness, inside `analyzeMaybeDangling`, confirm the exact Analysis-API surface on our vendored JAR:
  1. a plain type reference and a top-level call each yield the expected importable FqName (via `resolveToSymbol`/`classId`/`callableId`/any `importableFqName` — whichever actually exists);
  2. `resolveToCall()?.successfulFunctionCallOrNull()?.symbol` returns the operator function for `+`, `[]`, `by lazy`, and destructuring;
  3. a used symbol maps to its package (wildcard case).
  Outcome gates the approach: green → proceed; surprising → adjust to the real API; worst case → narrow the rule for any category the API can't cleanly express (keep rather than remove, and log).
- **Step 1** — `ImportOrganizer` helper + unit tests (TDD against the fixture cases above).
- **Step 2** — `OrganizeImportsAction` wrapper + registration in `KotlinCodeActionsMenu`.
- **Step 3** — manual verification in-app; full test run on `:lsp:kotlin:testV7DebugUnitTest`.

## Primary risk

Deriving an importable FqName from a resolved symbol is **unexercised in this repo** (no `resolveToSymbol` / `importableFqName` call sites; resolution elsewhere goes through scopes and the custom symbol index), and the vendored Analysis API is a moving `2.3.255-SNAPSHOT`. Compile-level risk (wrong method/property names) is cheap to discover; the correctness risk is missing a convention-call category, which would wrongly remove a used operator/delegate import and break the build. Mitigations: the Step 0 spike pins the API; the explicit convention checklist prevents missed categories; the "keep unless provably used" rule biases all residual error toward harmless under-removal. `resolveToCall()` + `successfulFunctionCallOrNull()` is already proven in the repo (signature help), which covers the operator path.

## Confirmed resolution API (Step 0 spike, 2026-07-09)

The Step 0 spike (`ResolutionSpikeTest`, throwaway, deleted after this run) confirmed the brief's candidate API compiled and passed **verbatim, with zero alternates needed**, against the vendored `2.3.255-SNAPSHOT` Analysis API. Task 4 should use these shapes as-is.

**(a) Name reference → `KtReference` → resolved symbol:**

```kotlin
import org.jetbrains.kotlin.idea.references.mainReference
// inside a KaSession (e.g. analyzeMaybeDangling { ... })
val sym = someNameReferenceExpression.mainReference.resolveToSymbol()
```

`KtNameReferenceExpression.mainReference` (the `org.jetbrains.kotlin.idea.references.mainReference` extension) resolved directly; no fallback to `analysis.api.fir.references.*` or manual `.references.filterIsInstance<KtReference>()` was required.

**(b) Resolved `KaSymbol` → importable FqName string:**

```kotlin
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol

val fq = when (sym) {
  is KaClassLikeSymbol -> sym.classId?.asSingleFqName()?.asString()
  is KaCallableSymbol -> sym.callableId?.asSingleFqName()?.asString()
  else -> null
}
```

No direct `KaSymbol.importableFqName` property was tried/needed — the `classId`/`callableId` branch from the brief compiled and produced the correct value on the first try (`java.io.File` for the `File` type reference). Both `ClassId` and `CallableId` expose `asSingleFqName()`.

**(c) Operator/convention call → function symbol:**

The resolvable call sits on the **`KtBinaryExpression`** itself (not `KtOperationReferenceExpression`) — `KtElement.resolveToCall()` is available directly on the binary expression:

```kotlin
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol

val opSym = someBinaryExpression.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
val callableFq = (opSym as? KaCallableSymbol)?.callableId?.asSingleFqName()?.asString()
```

For `BigInteger.ONE + BigInteger.ONE`, this resolved to `kotlin.plus` (the built-in numeric `plus` operator). The important confirmation is that the chain resolves non-null on a `KtBinaryExpression` and yields a `KaCallableSymbol` with a `callableId`. Same chain shape (`resolveToCall()?.successfulFunctionCallOrNull()?.symbol`) is expected to work for `[]`, `by lazy`, and destructuring `KtElement`s (per-repo precedent already used in signature help).

**(d) File package name from PSI:**

```kotlin
val pkg = ktFile.packageFqName.asString()
```

No analysis session needed — pure PSI. Confirmed value: `p` for `package p`.

**Logged SPIKE output (from the passing test run):**

```
SPIKE type fq = java.io.File
SPIKE plus callableId = kotlin.plus
SPIKE package = p
```

**Test harness note:** run inside `env.project.read { analyzeMaybeDangling(ktFile) { ... } }` (imports: `com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling`, `com.itsaky.androidide.lsp.kotlin.compiler.read`), using a `KtFile` from `KtLspTest.createSourceFile(name, content)`.
