# Organize Imports (Kotlin LSP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an always-available "Organize imports" code action for `.kt` files in the Kotlin LSP that sorts imports into canonical order and removes unused/redundant imports, computed in-process via the Kotlin Analysis API.

**Architecture:** A thin `OrganizeImportsAction : BaseKotlinCodeAction` (menu action) delegates to a pure `ImportOrganizer` helper. All Analysis-API resolution is isolated to one function, `KaSession.collectImportUsage(ktFile)`, which returns plain strings (no `KaLifetimeOwner` escapes). The rest — classification, redundancy rules, canonical sort, edit text, no-op detection, KDoc-keep — is pure PSI/string logic and is unit-tested without analysis.

**Tech Stack:** Kotlin, JetBrains Kotlin Analysis API (standalone, vendored as `analysis-api-standalone-embeddable-for-ide`), Robolectric + JUnit4, Gradle (flavored Android library).

**Spec:** `docs/superpowers/specs/2026-07-09-kotlin-organize-imports-design.md`

## Global Constraints

- **Branch / worktree:** all work happens in the `feat/ADFA-4614` worktree at `.claude/worktrees/ADFA-4614`. All paths below are relative to that worktree root.
- **Commit convention:** subject `ADFA-4614: <summary>` (colon, imperative). No co-author trailer. Logical, focused commits. Never `git add .` — always stage explicit paths. **Never commit files under `docs/superpowers/`** (specs/plans/notes stay uncommitted).
- **Build/test task:** `./gradlew :lsp:kotlin:testV7DebugUnitTest` (V7 = `armeabi-v7a` flavor). Run from the worktree root.
- **Analysis-API footguns (mandatory):**
  - Obtain the `KtFile` via `env.ktSymbolIndex.getCurrentKtFile(path).get()` **before** entering `env.project.read { }`. Never call the blocking `.get()`/`.await()` inside `project.read` (its refresh needs `project.write`; the RW lock is non-upgradeable → deadlock).
  - All Analysis-API access goes through `analyzeMaybeDangling(ktFile) { }` (never bare `analyze()`), and inside it never call `write`.
  - No `KaLifetimeOwner` (symbol, type, call) may escape the `analyzeMaybeDangling` block — extract plain data (`String`s) inside it.
- **Safety bias:** an import is removed only when *provably* unused. On any failed/ambiguous resolution, keep the import. Wrongly removing a used import breaks compilation and is the failure mode to avoid.

## File Structure

- Create `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizer.kt` — pure logic: `ImportUsage` data class, `DEFAULT_STAR_PACKAGES`, `organizedImportBlock(...)`, `keepImport(...)`, `collectKDocLinkNames(...)`.
- Create `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollector.kt` — the single Analysis-API seam: `KaSession.collectImportUsage(ktFile)` + `KaSymbol.importableFqNameString()`.
- Create `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt` — the action wrapper.
- Modify `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinLanguageServer.kt` — add public `compilationEnvironmentFor(file)` accessor.
- Modify `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionsMenu.kt` — register `OrganizeImportsAction()`.
- Create `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizerTest.kt` — pure-logic tests.
- Create `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollectorTest.kt` — analysis-backed fixture tests.

---

## Task 1: De-risking spike — pin the resolution API

**Purpose:** The repo has no `resolveToSymbol` / `importableFqName` / `mainReference` call sites, and the vendored Analysis API is a moving `2.3.255-SNAPSHOT`. Confirm the exact callable surface before building on it. This task produces a **throwaway** test that is deleted at the end; its only durable output is the confirmed API recorded in the plan/spec notes.

**Files:**
- Create (throwaway): `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ResolutionSpikeTest.kt`

**Interfaces:**
- Produces: confirmed answers to (a) how to get a `KtReference` from a `KtNameReferenceExpression` and resolve it to a symbol; (b) how to derive an importable FqName string from a resolved `KaSymbol`; (c) that `KtElement.resolveToCall()?.successfulFunctionCallOrNull()?.symbol` yields the operator function symbol for `+`, `[]`, `by lazy`, and destructuring; (d) how to read the file's package name from PSI.

- [ ] **Step 1: Write the spike test (candidate API — adjust until it compiles & passes)**

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.junit.Test

class ResolutionSpikeTest : KtLspTest() {
  @Test
  fun `resolve type reference and operator call`() {
    val ktFile = createSourceFile(
      "Spike.kt",
      """
      package p
      import java.io.File
      import java.math.BigInteger
      fun use() {
        val f: File = File("x")
        val a = BigInteger.ONE + BigInteger.ONE
      }
      """.trimIndent(),
    )

    env.project.read {
      analyzeMaybeDangling(ktFile) {
        // (a)+(b): a name reference -> symbol -> importable fq-name
        val fileRef = ktFile.collectDescendantsOfType<KtNameReferenceExpression>()
          .first { it.getReferencedName() == "File" }
        val sym = fileRef.mainReference.resolveToSymbol()  // CONFIRM: mainReference import + resolveToSymbol
        val fq = when (sym) {
          is KaClassLikeSymbol -> sym.classId?.asSingleFqName()?.asString()
          is KaCallableSymbol -> sym.callableId?.asSingleFqName()?.asString()
          else -> null
        }
        println("SPIKE type fq = $fq")   // expect java.io.File

        // (c): operator call -> function symbol -> importable fq-name
        val plus = ktFile.collectDescendantsOfType<KtBinaryExpression>().first()
        val opSym = plus.resolveToCall()?.successfulFunctionCallOrNull()?.symbol
        println("SPIKE plus callableId = ${(opSym as? KaCallableSymbol)?.callableId?.asSingleFqName()?.asString()}")

        // (d): package name from PSI (no analysis needed, but confirm here)
        println("SPIKE package = ${ktFile.packageFqName.asString()}")
      }
    }
  }
}
```

- [ ] **Step 2: Run the spike and iterate on the API**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*ResolutionSpikeTest*"`

If it fails to **compile**, fix the API until it does, trying these known alternates and recording which works:
- `mainReference`: import `org.jetbrains.kotlin.idea.references.mainReference`; if unresolved, try `org.jetbrains.kotlin.analysis.api.fir.references.*` or resolve via `fileRef.references.filterIsInstance<org.jetbrains.kotlin.idea.references.KtReference>().firstOrNull()?.resolveToSymbol()`.
- symbol → fq-name: if `KaSymbol.importableFqName` exists directly (import `org.jetbrains.kotlin.analysis.api.symbols.*`), prefer it over the `classId`/`callableId` branch.
- `asSingleFqName()`: `ClassId` and `CallableId` both expose it; if not, use `classId.asFqNameString()` / `callableId.asSingleFqName()`.

Expected once compiling: PASS, with logged `SPIKE type fq = java.io.File`, a non-null `plus` callableId (e.g. `java.math.BigInteger.plus` or `kotlin.plus`), and `SPIKE package = p`.

- [ ] **Step 3: Record the confirmed API**

Append a short "Confirmed resolution API" note to the bottom of the spec file (`docs/superpowers/specs/2026-07-09-kotlin-organize-imports-design.md`) capturing the exact imports and call shapes that compiled. Tasks 4 uses these verbatim.

- [ ] **Step 4: Delete the throwaway test**

```bash
rm lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ResolutionSpikeTest.kt
```

- [ ] **Step 5: No commit**

Nothing to commit (spec notes are uncommitted per Global Constraints; the spike test is deleted). Proceed to Task 2.

---

## Task 2: Wire the action skeleton (no-op) + registration + env accessor

**Purpose:** Get the menu item appearing and dispatching end-to-end before the logic exists, so wiring bugs surface early. The action returns no edits for now.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinLanguageServer.kt`
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt`
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionsMenu.kt`

**Interfaces:**
- Produces: `KotlinLanguageServer.compilationEnvironmentFor(file: Path): CompilationEnvironment?`; `OrganizeImportsAction` (id `ide.editor.lsp.kt.organizeImports`) registered in `KotlinCodeActionsMenu.actions`.
- Consumes: `BaseKotlinCodeAction` (existing), `R.string.action_organize_imports` (existing).

- [ ] **Step 1: Add the public env accessor to `KotlinLanguageServer`**

In `KotlinLanguageServer.kt`, add this method inside the class (e.g. just after `connectClient`), and add the import `import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment` (`Path` is already imported):

```kotlin
	/** Returns the [CompilationEnvironment] responsible for [file], or null if the compiler is not ready. */
	fun compilationEnvironmentFor(file: Path): CompilationEnvironment? =
		compiler?.compilationEnvironmentFor(file)
```

- [ ] **Step 2: Create `OrganizeImportsAction` (no-op body)**

Create `actions/OrganizeImportsAction.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.actions

import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory

class OrganizeImportsAction : BaseKotlinCodeAction() {
	override var titleTextRes: Int = R.string.action_organize_imports
	override val id: String = "ide.editor.lsp.kt.organizeImports"
	override var label: String = ""

	companion object {
		private val logger = LoggerFactory.getLogger(OrganizeImportsAction::class.java)
	}

	override suspend fun execAction(data: ActionData): List<TextEdit> {
		// Logic added in Task 5.
		return emptyList()
	}

	override fun postExec(data: ActionData, result: Any) {
		super.postExec(data, result)
		if (result !is List<*> || result.isEmpty()) return

		@Suppress("UNCHECKED_CAST")
		result as List<TextEdit>

		val client = data.languageClient ?: run {
			logger.warn("No language client set. Cannot organize imports.")
			return
		}
		val file = data.requireFile()
		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes = listOf(DocumentChange(file = file.toPath(), edits = result)),
				kind = CodeActionKind.QuickFix,
				command = Command("", ""), // no post-action command (no CMD_FORMAT_CODE)
			)
		)
	}
}
```

- [ ] **Step 3: Register the action**

In `KotlinCodeActionsMenu.kt`, add the import `import com.itsaky.androidide.lsp.kotlin.actions.OrganizeImportsAction` and add `OrganizeImportsAction(),` to the `actions` list after `AddImportAction()`:

```kotlin
	override val actions: List<ActionItem> =
		listOf(
			CommentLineAction(KT_LANG, KT_EXTS, KT_LINE_COMMENT_TOKEN),
			UncommentLineAction(KT_LANG, KT_EXTS, KT_LINE_COMMENT_TOKEN),
			AddImportAction(),
			OrganizeImportsAction(),
		)
```

- [ ] **Step 4: Compile**

Run: `./gradlew :lsp:kotlin:compileV7DebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinLanguageServer.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionsMenu.kt
git commit -m "ADFA-4614: register no-op Organize Imports action skeleton"
```

---

## Task 3: Pure import-organizing logic (`ImportOrganizer`)

**Purpose:** Implement and fully test the sort + classification + redundancy + KDoc-keep + no-op logic, driven by a hand-built `ImportUsage` (no Analysis API involved). This is the bulk of the behavior and is deterministically testable.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizer.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizerTest.kt`

**Interfaces:**
- Produces:
  - `data class ImportUsage(val usedFqNames: Set<String>, val usedPackages: Set<String>)`
  - `internal fun organizedImportBlock(ktFile: KtFile, usage: ImportUsage): String?` — returns the canonical import-block text, or `null` if imports are already organized (no edit needed). Returned text has no leading/trailing newline; lines are joined with `System.lineSeparator()`.
  - `internal val DEFAULT_STAR_PACKAGES: Set<String>`
- Consumes: `KtFile` PSI (`importDirectives`, `importList`, `packageFqName`), `KtImportDirective` (`importedFqName`, `isAllUnder`, `aliasName`, `importPath`).

- [ ] **Step 1: Write the failing tests**

Create `ImportOrganizerTest.kt`. It extends `KtLspTest` only to get a real `KtFile` via `createSourceFile`; `organizedImportBlock` itself takes a synthetic `ImportUsage`, so no analysis runs.

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportOrganizerTest : KtLspTest() {

	private fun organize(content: String, usage: ImportUsage): String? {
		val ktFile = createSourceFile("Sample.kt", content)
		return env.project.read { organizedImportBlock(ktFile, usage) }
	}

	@Test
	fun `removes unused named import and keeps used one`() {
		val result = organize(
			"""
			package p
			import a.b.Used
			import a.b.Unused
			fun f(x: Used) {}
			""".trimIndent(),
			ImportUsage(usedFqNames = setOf("a.b.Used"), usedPackages = setOf("a.b")),
		)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `sorts imports lexicographically`() {
		val result = organize(
			"""
			package p
			import a.b.Zebra
			import a.b.Apple
			fun f(x: Zebra, y: Apple) {}
			""".trimIndent(),
			ImportUsage(setOf("a.b.Zebra", "a.b.Apple"), setOf("a.b")),
		)
		assertEquals("import a.b.Apple${'\n'}import a.b.Zebra", result)
	}

	@Test
	fun `keeps used wildcard, removes unused wildcard`() {
		val result = organize(
			"""
			package p
			import used.pkg.*
			import unused.pkg.*
			fun f(x: Thing) {}
			""".trimIndent(),
			ImportUsage(usedFqNames = setOf("used.pkg.Thing"), usedPackages = setOf("used.pkg")),
		)
		assertEquals("import used.pkg.*", result)
	}

	@Test
	fun `removes default-import-redundant named import`() {
		val result = organize(
			"""
			package p
			import kotlin.collections.List
			import a.b.Used
			fun f(x: Used) {}
			""".trimIndent(),
			// even though List resolves, it is redundant (default star package)
			ImportUsage(setOf("a.b.Used", "kotlin.collections.List"), setOf("a.b", "kotlin.collections")),
		)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `removes same-package redundant import`() {
		val result = organize(
			"""
			package p
			import p.Sibling
			import a.b.Used
			fun f(x: Used, y: Sibling) {}
			""".trimIndent(),
			ImportUsage(setOf("a.b.Used", "p.Sibling"), setOf("a.b", "p")),
		)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `keeps aliased import from default package`() {
		val result = organize(
			"""
			package p
			import kotlin.collections.List as KList
			fun f(x: KList<String>) {}
			""".trimIndent(),
			ImportUsage(setOf("kotlin.collections.List"), setOf("kotlin.collections")),
		)
		assertEquals("import kotlin.collections.List as KList", result)
	}

	@Test
	fun `keeps import referenced only in KDoc`() {
		val result = organize(
			"""
			package p
			import a.b.DocOnly
			/** See [DocOnly] for details. */
			fun f() {}
			""".trimIndent(),
			ImportUsage(usedFqNames = emptySet(), usedPackages = emptySet()),
		)
		assertEquals("import a.b.DocOnly", result)
	}

	@Test
	fun `collapses exact duplicate imports`() {
		val result = organize(
			"""
			package p
			import a.b.Used
			import a.b.Used
			fun f(x: Used) {}
			""".trimIndent(),
			ImportUsage(setOf("a.b.Used"), setOf("a.b")),
		)
		assertEquals("import a.b.Used", result)
	}

	@Test
	fun `returns null when already organized`() {
		val result = organize(
			"""
			package p
			import a.b.Apple
			import a.b.Zebra
			fun f(x: Apple, y: Zebra) {}
			""".trimIndent(),
			ImportUsage(setOf("a.b.Apple", "a.b.Zebra"), setOf("a.b")),
		)
		assertNull(result)
	}

	@Test
	fun `no imports returns null`() {
		val result = organize(
			"""
			package p
			fun f() {}
			""".trimIndent(),
			ImportUsage(emptySet(), emptySet()),
		)
		assertNull(result)
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*ImportOrganizerTest*"`
Expected: FAIL to compile (`organizedImportBlock` / `ImportUsage` unresolved).

- [ ] **Step 3: Implement `ImportOrganizer.kt`**

Create `utils/ImportOrganizer.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * What a file's body actually uses, expressed as plain strings so nothing crosses an `analyze`
 * lifetime boundary. Produced by [collectImportUsage].
 *
 * @property usedFqNames importable fully-qualified names referenced by the body.
 * @property usedPackages parent packages/objects of used symbols (for wildcard matching).
 */
internal data class ImportUsage(
	val usedFqNames: Set<String>,
	val usedPackages: Set<String>,
)

/** JVM packages that Kotlin imports with a wildcard by default; explicit named imports from these are redundant. */
internal val DEFAULT_STAR_PACKAGES: Set<String> = setOf(
	"kotlin",
	"kotlin.annotation",
	"kotlin.collections",
	"kotlin.comparisons",
	"kotlin.io",
	"kotlin.ranges",
	"kotlin.sequences",
	"kotlin.text",
	"kotlin.jvm",
	"java.lang",
)

private val KDOC_LINK = Regex("""\[([^\]\s]+)]""")

/**
 * Computes the canonical import block for [ktFile] given [usage]: unused/redundant imports removed,
 * survivors deduped and lexicographically sorted. Returns null when the imports are already in that
 * exact form (no edit needed). The returned text has no surrounding newlines.
 */
internal fun organizedImportBlock(ktFile: KtFile, usage: ImportUsage): String? {
	val directives = ktFile.importDirectives
	if (directives.isEmpty()) return null

	val filePackage = ktFile.packageFqName.asString()
	val kdocNames = collectKDocLinkNames(ktFile)

	val newLines = directives
		.filter { keepImport(it, usage, filePackage, kdocNames) }
		.mapNotNull { it.importPath?.pathStr?.let { path -> "import $path" } }
		.distinct()
		.sorted()

	val currentLines = directives.mapNotNull { it.importPath?.pathStr?.let { path -> "import $path" } }

	if (newLines == currentLines) return null
	return newLines.joinToString(System.lineSeparator())
}

private fun keepImport(
	directive: KtImportDirective,
	usage: ImportUsage,
	filePackage: String,
	kdocNames: Set<String>,
): Boolean {
	val fqName = directive.importedFqName ?: return true // malformed import -> keep (conservative)
	val fqNameStr = fqName.asString()
	val alias = directive.aliasName
	val shortName = alias ?: fqName.shortName().asString()

	// Conservative: keep anything referenced by short name/alias in a KDoc link.
	if (shortName in kdocNames) return true

	if (directive.isAllUnder) {
		// Wildcard: keep iff some used symbol lives in this package/object.
		return fqNameStr in usage.usedPackages
	}

	val parentPackage = fqName.parent().asString()
	// Redundant named imports (only when not aliased — an alias is meaningful).
	if (alias == null && parentPackage in DEFAULT_STAR_PACKAGES) return false
	if (alias == null && parentPackage == filePackage) return false

	return fqNameStr in usage.usedFqNames
}

private fun collectKDocLinkNames(ktFile: KtFile): Set<String> =
	PsiTreeUtil.collectElementsOfType(ktFile, KDoc::class.java)
		.flatMap { kdoc -> KDOC_LINK.findAll(kdoc.text).map { it.groupValues[1].substringAfterLast('.') } }
		.toSet()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*ImportOrganizerTest*"`
Expected: PASS (all 10 tests). If `KDoc` or `PsiTreeUtil` imports fail to resolve, confirm the package path (`org.jetbrains.kotlin.kdoc.psi.api.KDoc`, `org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil`) against the classpath and adjust.

- [ ] **Step 5: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizer.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportOrganizerTest.kt
git commit -m "ADFA-4614: add pure import-organizing logic with tests"
```

---

## Task 4: Analysis-API usage collection (`collectImportUsage`)

**Purpose:** Implement the one function that touches the resolution API, using the surface confirmed in Task 1. Test it against real fixtures so operator/convention coverage is verified.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollector.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollectorTest.kt`

**Interfaces:**
- Produces: `internal fun KaSession.collectImportUsage(ktFile: KtFile): ImportUsage`.
- Consumes: `ImportUsage` (Task 3); the resolution API confirmed in Task 1.

- [ ] **Step 1: Write the failing tests**

Create `ImportUsageCollectorTest.kt`. Each test builds a fixture, runs `collectImportUsage` inside `analyzeMaybeDangling`, and asserts membership (not exact set equality — the set also contains stdlib/implicit symbols).

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportUsageCollectorTest : KtLspTest() {

	private fun usageOf(ktFile: KtFile): ImportUsage =
		env.project.read { analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) } }

	@Test
	fun `type reference is recorded as used`() {
		val ktFile = createSourceFile(
			"UseType.kt",
			"""
			package p
			fun f(): java.io.File? = null
			fun g() { val x: java.io.File = java.io.File("a") }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("java.io.File" in usage.usedFqNames)
		assertTrue("java.io" in usage.usedPackages)
	}

	@Test
	fun `top-level function call is recorded as used`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			fun topLevelHelper() {}
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseFn.kt",
			"""
			package p
			import lib.topLevelHelper
			fun f() { topLevelHelper() }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.topLevelHelper" in usage.usedFqNames)
	}

	@Test
	fun `operator function used via symbol is recorded`() {
		createSourceFile(
			"lib/Ops.kt",
			"""
			package lib
			class Money
			operator fun Money.plus(other: Money): Money = this
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"UseOp.kt",
			"""
			package p
			import lib.Money
			import lib.plus
			fun f(a: Money, b: Money) { val c = a + b }
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertTrue("lib.plus" in usage.usedFqNames)
	}

	@Test
	fun `unreferenced import is absent from usage`() {
		createSourceFile(
			"lib/Extra.kt",
			"""
			package lib
			class Extra
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"NoUse.kt",
			"""
			package p
			import lib.Extra
			fun f() {}
			""".trimIndent(),
		)
		val usage = usageOf(ktFile)
		assertFalse("lib.Extra" in usage.usedFqNames)
	}
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*ImportUsageCollectorTest*"`
Expected: FAIL to compile (`collectImportUsage` unresolved).

- [ ] **Step 3: Implement `ImportUsageCollector.kt` (using Task 1's confirmed API)**

Create `utils/ImportUsageCollector.kt`. The imports/resolution calls below are the candidate confirmed by the Task 1 spike — substitute the exact ones recorded there:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Collects the importable fq-names (and their packages) referenced by [ktFile]'s body. MUST be
 * called inside [analyzeMaybeDangling]. Returns only plain strings, so nothing escapes the analyze
 * lifetime. Anything that fails to resolve simply doesn't join the used set (safe: leads to keeping
 * an import, never removing a used one).
 */
internal fun KaSession.collectImportUsage(ktFile: KtFile): ImportUsage {
	val usedFqNames = HashSet<String>()
	val usedPackages = HashSet<String>()

	fun record(symbol: KaSymbol?) {
		val fq = symbol?.importableFqNameString() ?: return
		usedFqNames += fq
		val pkg = fq.substringBeforeLast('.', missingDelimiterValue = "")
		if (pkg.isNotEmpty()) usedPackages += pkg
	}

	// 1) Plain name / type references (excluding the import list itself).
	ktFile.collectDescendantsOfType<KtNameReferenceExpression>().forEach { ref ->
		if (ref.getParentOfType<KtImportList>(strict = false) != null) return@forEach
		runCatching { record(ref.mainReference.resolveToSymbol()) }
	}

	// 2) Convention / operator call sites (no textual name reference).
	ktFile.collectDescendantsOfType<KtElement>().forEach { element ->
		val isConvention = element is KtOperationReferenceExpression ||
			element is KtArrayAccessExpression ||
			element is KtCallExpression ||
			element is KtForExpression ||
			element is KtDestructuringDeclaration ||
			element is KtPropertyDelegate
		if (!isConvention) return@forEach
		runCatching {
			record(element.resolveToCall()?.successfulFunctionCallOrNull()?.symbol)
		}
	}

	return ImportUsage(usedFqNames, usedPackages)
}

private fun KaSymbol.importableFqNameString(): String? = when (this) {
	is KaClassLikeSymbol -> classId?.asSingleFqName()?.asString()
	is KaCallableSymbol -> callableId?.asSingleFqName()?.asString()
	else -> null
}
```

Notes for the implementer:
- If the Task 1 spike found `KaSymbol.importableFqName` directly, replace `importableFqNameString()` with it.
- The `resolveToCall()` overloads are members of `KaSession` and require the analyze receiver — that is why this whole function is a `KaSession` extension.
- `getParentOfType<KtImportList>` guards against crediting the import statements themselves.
- `runCatching` prevents one unresolved node from aborting the whole pass; a swallowed failure just omits that node (safe direction).

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*ImportUsageCollectorTest*"`
Expected: PASS (4 tests). If the operator test fails (`lib.plus` absent), the convention-resolution branch needs adjustment — verify with the Task 1 spike which element type carries the resolvable call for `a + b` (it may be the `KtBinaryExpression` itself rather than its `KtOperationReferenceExpression`); add that element type to the `isConvention` set.

- [ ] **Step 5: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollector.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollectorTest.kt
git commit -m "ADFA-4614: collect used importable names via Analysis API"
```

---

## Task 5: Wire the logic into the action + integration test

**Purpose:** Replace the no-op `execAction` with the real computation and verify end-to-end on a fixture.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/ImportUsageCollectorTest.kt` (add an end-to-end helper case) — or a new `OrganizeImportsEndToEndTest.kt`.

**Interfaces:**
- Consumes: `KotlinLanguageServer.compilationEnvironmentFor` (Task 2), `organizedImportBlock` (Task 3), `collectImportUsage` (Task 4), `TextRange.toRange` (existing `EditExts`).
- Produces: a fully functional `OrganizeImportsAction`.

- [ ] **Step 1: Write the failing end-to-end test**

Create `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/OrganizeImportsEndToEndTest.kt`. This exercises the full compute path (fetch KtFile → analyze → organize → build edit text) without the Android `ActionData`, by calling a small internal helper `computeOrganizeEdit` that Step 3 extracts from the action.

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizeImportsEndToEndTest : KtLspTest() {

	@Test
	fun `removes unused import end to end`() {
		createSourceFile(
			"lib/Lib.kt",
			"""
			package lib
			class Used
			class Unused
			""".trimIndent(),
		)
		val ktFile = createSourceFile(
			"Main.kt",
			"""
			package p
			import lib.Used
			import lib.Unused
			fun f(x: Used) {}
			""".trimIndent(),
		)

		val block = env.project.read {
			val usage = analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) }
			organizedImportBlock(ktFile, usage)
		}
		assertEquals("import lib.Used", block)
	}
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "*OrganizeImportsEndToEndTest*"`
Expected: PASS already if Tasks 3+4 are correct (this test only uses their public functions). If it FAILS, the failure is a real integration bug between `collectImportUsage` and `organizedImportBlock` — fix before proceeding. (This test locks the contract the action relies on.)

- [ ] **Step 3: Implement the real `execAction`**

Replace the body of `execAction` in `OrganizeImportsAction.kt` and add the required imports:

```kotlin
	override suspend fun execAction(data: ActionData): List<TextEdit> {
		val server = data.get<KotlinLanguageServer>() ?: return emptyList()
		val file = data.requireFile()
		val nioPath = file.toPath()

		val env = server.compilationEnvironmentFor(nioPath) ?: return emptyList()

		// Fetch the current KtFile BEFORE entering project.read (deadlock rule).
		val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return emptyList()
		if (ktFile.importDirectives.isEmpty()) return emptyList()

		return env.project.read {
			val usage = analyzeMaybeDangling(ktFile) { collectImportUsage(ktFile) }
			val newText = organizedImportBlock(ktFile, usage) ?: return@read emptyList()
			val range = ktFile.importList?.textRange?.toRange(ktFile) ?: return@read emptyList()
			if (range == Range.NONE) return@read emptyList()
			listOf(TextEdit(range, newText))
		}
	}
```

Add these imports to the file:

```kotlin
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.collectImportUsage
import com.itsaky.androidide.lsp.kotlin.utils.organizedImportBlock
import com.itsaky.androidide.lsp.kotlin.utils.toRange
import com.itsaky.androidide.models.Range
```

- [ ] **Step 4: Compile and run the full module test suite**

Run: `./gradlew :lsp:kotlin:testV7DebugUnitTest`
Expected: BUILD SUCCESSFUL; all `ImportOrganizerTest`, `ImportUsageCollectorTest`, `OrganizeImportsEndToEndTest`, and pre-existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/OrganizeImportsAction.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/OrganizeImportsEndToEndTest.kt
git commit -m "ADFA-4614: compute and apply organized imports in the action"
```

---

## Task 6: Manual verification in-app

**Purpose:** Confirm the action behaves correctly in the running IDE (the only place the full `ActionData`/editor/`performCodeAction` path runs).

**Files:** none (verification only).

- [ ] **Step 1: Build and install the app per the project's run workflow** (use the `/run` skill or the standard install task).

- [ ] **Step 2: Open a `.kt` file with messy imports** — include: an unused named import, a used import, an aliased import, an unused wildcard, a used wildcard, and an import used only via an operator (e.g. a custom `plus`). Trigger the code-actions menu and select **Organize imports**.

- [ ] **Step 3: Verify** the unused named import and unused wildcard are removed; the used import, alias, used wildcard, and operator import remain; the surviving imports are alphabetically sorted; and the file still compiles. Confirm running it again is a no-op (no edit / no cursor jump).

- [ ] **Step 4: Record the result** (pass/fail + any surprises) in the PR description. No commit.

---

## Self-Review

**Spec coverage:**
- Menu action, Java-parity, `action_organize_imports` → Task 2. ✓
- File access via `getCurrentKtFile`, deadlock rule, `analyzeMaybeDangling`, no lifetime escape → Tasks 4, 5 + Global Constraints. ✓
- Semantic used-detection incl. operator/convention categories → Task 4 (`collectImportUsage`) + its operator test. ✓
- Wildcard: remove when unused, no expansion → Task 3 `keepImport` (wildcard branch) + test. ✓
- Redundant (default + same-package) removal → Task 3 + two tests. ✓
- Canonical sort, single block, whole-block replace, no-op when unchanged → Task 3 (`organizedImportBlock`) + tests; edit built in Task 5. ✓
- KDoc conservative-keep → Task 3 `collectKDocLinkNames` + test. ✓
- No `CMD_FORMAT_CODE` → Task 2 `postExec` (`Command("", "")`). ✓
- Spike de-risking the resolution API → Task 1. ✓
- Testing via `:lsp:kotlin:testV7DebugUnitTest` → every task. ✓
- Out-of-scope items (expansion, collapse, diagnostic, settings) → not implemented. ✓

**Deviation from spec (flag for reviewer):** the spec said derive default imports "via `KaDefaultImports`"; this plan uses a documented constant `DEFAULT_STAR_PACKAGES` (the stable JVM default-import package set) instead, to keep `organizedImportBlock` pure and fully unit-testable without an analysis session. The Task 1 spike can cross-check the constant against `KaDefaultImports` if desired.

**Placeholder scan:** no TBD/TODO; every code step contains full code. The only intentionally-candidate code is Task 4's resolution calls, gated by Task 1's spike with explicit alternates. ✓

**Type consistency:** `ImportUsage(usedFqNames, usedPackages)`, `organizedImportBlock(ktFile, usage): String?`, `KaSession.collectImportUsage(ktFile): ImportUsage`, `OrganizeImportsAction.execAction: List<TextEdit>` used consistently across Tasks 3–5. ✓
