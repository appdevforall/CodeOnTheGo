package com.itsaky.androidide.lsp.kotlin.navigation

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isAnalysisCancellation
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.retryingOnPreemption
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.utils.rangeOf
import com.itsaky.androidide.lsp.models.ReferenceParams
import com.itsaky.androidide.lsp.models.ReferenceResult
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.future.await
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsiSafe
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("FindUsages")

/** How many times [planWithRetry] runs [planAt], each of which retries a preemption once itself. */
private const val PLAN_ATTEMPTS = 2

/**
 * Where a usage could possibly be written, derived from the target's visibility (R4).
 *
 * Kotlin's visibility rules are an exact bound, not a heuristic: a `private` declaration cannot be
 * referenced from another file, and a `public` one cannot be referenced from a module that does not
 * depend on its own. Narrowing here is what keeps the common cases cheap - a search on a local
 * variable never leaves the open file - and it is also what makes the ticket's three resolution
 * scopes fall out of one code path.
 */
internal sealed interface UsageSearchScope {
	data class SingleFile(
		val path: Path,
	) : UsageSearchScope

	data class Modules(
		val modules: List<KtModule>,
	) : UsageSearchScope
}

/**
 * Everything the per-file search loop needs, computed once in the caret's analysis session.
 *
 * [matchSet] holds pointers rather than symbols because a [KaSymbol] cannot cross a session boundary,
 * and each candidate file may be analyzed in a different one (R6).
 */
internal class SearchPlan(
	val simpleName: String,
	val matchSet: List<KaSymbolPointer<KaSymbol>>,
	val scope: UsageSearchScope,
)

/**
 * Computes the usage result for [params].
 *
 * Structured so that no lock spans the whole search (R9): the target is resolved under one short
 * `project.read`, candidate files are selected with no lock at all, and each candidate then takes its
 * own read lock and analysis session. A whole-workspace search holding either for its full duration
 * would block index refresh (which needs `project.write`) and would lose all its work to a single
 * keystroke.
 */
context(env: AbstractCompilationEnvironment)
internal suspend fun findUsagesAt(params: ReferenceParams): ReferenceResult {
	logger.debug("findUsagesAt requested for file={} position={}", params.file, params.position)

	if (params.cancelChecker.isCancelled()) {
		logger.debug("References request for {} was cancelled before processing", params.file)
		return ReferenceResult.empty()
	}

	return try {
		val plan = planWithRetry(params) ?: return ReferenceResult.empty()
		val candidates = candidateFiles(plan, params.cancelChecker)
		logger.debug("Usage search for '{}': {} candidate file(s)", plan.simpleName, candidates.size)

		val locations =
			candidates
				.flatMap { candidate ->
					params.cancelChecker.abortIfCancelled()
					usagesIn(candidate, plan, params.cancelChecker)
				}.distinctBy { it.file to it.range }
				.sortedWith(compareBy({ it.file.toString() }, { it.range.start.index }))

		logger.debug("Usage result for {}: {} location(s)", params.file, locations.size)
		ReferenceResult(locations)
	} catch (e: Throwable) {
		if (e.isAnalysisCancellation()) {
			logger.debug("Usage search for {} cancelled", params.file)
			return ReferenceResult.empty()
		}
		logger.warn("Usage search failed for {}", params.file, e)
		ReferenceResult.empty()
	}
}

/**
 * [planAt], retried on a preemption that outlived its own single retry.
 *
 * Without this a *second* preemption escapes as an [AnalysisPreemptedException], which is a
 * [java.util.concurrent.CancellationException], so [findUsagesAt]'s cancellation branch turns it into
 * an empty result and the editor flashes "No references found" for a symbol with plenty - the wrong
 * answer ADR 0011 exists to prevent. [usagesIn] draws the same distinction per candidate file.
 *
 * Retrying is cheap here: the plan phase is one file and one short session. Genuine cancellation is
 * not caught - the delegate throws a plain [java.util.concurrent.CancellationException], not this
 * subtype.
 */
context(env: AbstractCompilationEnvironment)
private suspend fun planWithRetry(params: ReferenceParams): SearchPlan? {
	repeat(PLAN_ATTEMPTS) {
		try {
			return planAt(params)
		} catch (e: AnalysisPreemptedException) {
			logger.debug("Usage search plan for {} was preempted twice; retrying the plan", params.file)
		}
	}

	logger.warn("Usage search for {} abandoned: target resolution kept being preempted", params.file)
	return null
}

/**
 * The search plan for [params]' caret, or null when it names nothing searchable.
 *
 * Its own short-lived read lock and analysis session, released before any candidate file is touched.
 */
context(env: AbstractCompilationEnvironment)
internal suspend fun planAt(params: ReferenceParams): SearchPlan? {
	val offset = params.position.requireIndex()

	return retryingOnPreemption(params.cancelChecker, "Usage search target for ${params.file}") { cancelChecker ->
		// Awaited per attempt and outside project.read, exactly as in findDefinitionAt: the refresh this
		// waits on needs project.write, and a preemption invalidates the KtFile it returned.
		val ktFile = env.ktSymbolIndex.getCurrentKtFile(params.file).await()
		if (ktFile == null) {
			logger.warn("File {} cannot be loaded for usage search", params.file)
			null
		} else {
			cancelChecker.abortIfCancelled()
			env.project.read {
				val target = targetAtCaret(ktFile, offset) ?: return@read null
				analyzeMaybeDangling(ktFile, AnalysisPriority.COMMAND, cancelChecker) {
					planFor(target)
				}
			}
		}
	}
}

/** The search plan for [target], or null when it names nothing searchable. */
context(env: AbstractCompilationEnvironment)
private fun KaSession.planFor(target: CaretTarget): SearchPlan? {
	val symbol = targetSymbol(target) ?: return null
	val declaration = symbol.sourcePsiSafe<PsiElement>()
	if (declaration == null) {
		// Not a workspace source: the stdlib, the framework, a library jar. Its usages are unreachable
		// for the same reason go-to-definition cannot navigate to it.
		logger.debug("Usage search target is not a workspace source; nothing to search")
		return null
	}

	val simpleName = prefilterName(symbol) ?: return null

	val matchSet = matchSet(symbol)

	return SearchPlan(
		simpleName = simpleName,
		matchSet = matchSet.map { it.createPointer() },
		scope = scopeOf(symbol, declaration, pathOf(declaration), matchSet),
	)
}

/**
 * The on-disk path of [declaration]'s file, or null when it has none.
 *
 * [backingFilePath] is tried before the VFS, exactly as in go-to-definition: the file the user is
 * editing is a live [KtFile] built from the editor buffer, whose `virtualFile` is a non-physical
 * `LightVirtualFile`. Reading the VFS alone would leave the common case pathless, and a pathless local
 * or `private` target loses its single-file scope (R4) and widens to the whole module graph.
 */
private fun pathOf(declaration: PsiElement): Path? {
	val psiFile = declaration.containingFile ?: return null
	val ktFile = psiFile as? KtFile

	return (ktFile?.backingFilePath ?: ktFile?.originalKtFile?.backingFilePath)
		?: psiFile.virtualFile
			?.takeIf { it.fileSystem.protocol == "file" }
			?.let { runCatching { it.toNioPath() }.getOrNull() }
}

/**
 * The declaration [target] names.
 *
 * A [CaretTarget.Declaration] already *is* the declaration, so it answers through its own symbol; a
 * [CaretTarget.Reference] answers through the same two resolution paths go-to-definition uses.
 */
private fun KaSession.targetSymbol(target: CaretTarget): KaDeclarationSymbol? =
	when (target) {
		is CaretTarget.Declaration -> {
			runCatching { target.declaration.symbol }.getOrNull()
		}

		is CaretTarget.Reference -> {
			symbolsAt(target.element)
				.also {
					if (it.size > 1) {
						// An ambiguous reference (overloads, broken code). Searching for the first candidate
						// beats refusing to search; the alternative is a chooser UI the panel cannot host.
						logger.debug("Reference at caret resolved to {} symbols; searching the first", it.size)
					}
				}.firstOrNull() as? KaDeclarationSymbol
		}
	}?.let { symbol ->
		// A call through a subtype that does not redeclare the member resolves to a substituted fake
		// override rather than to the declaration the user wrote. Normalise both sides of every
		// comparison, starting here.
		(symbol as? KaCallableSymbol)?.fakeOverrideOriginal ?: symbol
	}

/**
 * The declarations a reference may resolve to and still count as a usage of [symbol] (R3).
 *
 * Two edges are added to the target itself:
 * - **Workspace-source supers.** A call dispatched through `Base.foo` may reach `Derived.foo`, so it
 *   counts as a usage of it. The walk stops at the workspace boundary: `Any.toString` in the match set
 *   would make a usage search on an overridden `toString` report every `.toString()` call in the
 *   workspace, and a library super can never contribute a reportable result anyway.
 * - **A classifier's constructors.** `Foo()` resolves to a constructor, not to the class, so without
 *   this a search on `class Foo` would miss every instantiation. Not applied in reverse: a target that
 *   *is* one constructor stays that constructor, because asking for usages of one overload is a
 *   deliberate act.
 */
private fun KaSession.matchSet(symbol: KaDeclarationSymbol): List<KaSymbol> =
	buildList {
		add(symbol)

		if (symbol is KaCallableSymbol) {
			addAll(
				symbol.allOverriddenSymbols
					.map { it.fakeOverrideOriginal }
					.filter { it.sourcePsiSafe<PsiElement>() != null },
			)
		}

		if (symbol is KaClassSymbol) {
			addAll(symbol.declaredMemberScope.constructors)
		}
	}

/**
 * The simple name to prefilter candidate files on, or null when there is none to search by.
 *
 * A constructor is written as its class's name, never as its own, so prefiltering on the symbol's own
 * name would match nothing.
 */
private fun KaSession.prefilterName(symbol: KaDeclarationSymbol): String? {
	val named =
		if (symbol is KaConstructorSymbol) {
			symbol.containingDeclaration as? KaNamedSymbol
		} else {
			symbol as? KaNamedSymbol
		}

	return named?.name?.asString()?.takeUnless { it.isEmpty() }
}

/**
 * [symbol]'s search scope, per R4's visibility ladder.
 *
 * [matchSet] widens the module case: see the dependents comment below.
 */
context(env: AbstractCompilationEnvironment)
private fun KaSession.scopeOf(
	symbol: KaDeclarationSymbol,
	declaration: PsiElement,
	declarationPath: Path?,
	matchSet: List<KaSymbol>,
): UsageSearchScope {
	val fileOnly = declarationPath?.let(UsageSearchScope::SingleFile)

	// A local is confined to its declaring block, and a private declaration to its file: Kotlin's
	// private top-level is file-private, and a private member cannot escape the class body it is
	// written in. Both are the cheap, exact cases.
	val fileConfined =
		symbol.location == KaSymbolLocation.LOCAL || symbol.visibility == KaSymbolVisibility.PRIVATE
	if (fileOnly != null && fileConfined) {
		return fileOnly
	}

	val module = moduleOf(declaration) ?: return fileOnly ?: UsageSearchScope.Modules(sourceModules())

	// internal is module-wide, and there is no associated test module to widen to: this project model
	// builds one module per Gradle module from the main source set only. A file-confined target with no
	// derivable path lands here too - it cannot be narrowed to one file, but it is still unreferenceable
	// outside its own module, so it must not fall through to the dependents below.
	if (fileConfined || symbol.visibility == KaSymbolVisibility.INTERNAL) {
		return UsageSearchScope.Modules(listOf(module))
	}

	// Anything more visible can be referenced from any module that depends on this one. Dependents,
	// not all modules: a module that cannot see the declaration cannot reference it.
	//
	// Every match-set member contributes its own module and dependents, not just the target's. A call
	// written against a workspace `Base.foo` declared in a *dependency* module is a usage of the
	// override (R3), and that module is not a dependent of the override's own - so scoping to the
	// target's dependents alone would never look at it.
	val provider = KotlinModuleDependentsProvider.getInstance(env.project)
	val roots = LinkedHashSet<KtModule>()
	roots.add(module)
	for (member in matchSet) {
		val memberDeclaration = member.sourcePsiSafe<PsiElement>() ?: continue
		moduleOf(memberDeclaration)?.let(roots::add)
	}

	val searched = LinkedHashSet<KtModule>()
	for (root in roots) {
		searched.add(root)
		provider.getTransitiveDependents(root).filterIsInstanceTo(searched)
	}

	return UsageSearchScope.Modules(searched.toList())
}

context(env: AbstractCompilationEnvironment)
private fun moduleOf(declaration: PsiElement): KtModule? =
	runCatching {
		ProjectStructureProvider.getInstance(env.project).getModule(declaration, useSiteModule = null) as? KtModule
	}.getOrNull()

context(env: AbstractCompilationEnvironment)
private fun sourceModules(): List<KtModule> =
	env.modules
		.asFlatSequence()
		.filter { it.isSourceModule }
		.toList()

/**
 * The files worth parsing and resolving for [plan].
 *
 * The prefilter is a one-directional over-approximation: a file that mentions the name but contains no
 * usage is parsed and discarded, while a file that does not mention it cannot contain a named usage.
 * [mentionsName] reads an open file's live editor buffer rather than its saved bytes, so a usage typed
 * but not yet saved is still found - which matters here, because find usages is run *while* editing.
 */
context(env: AbstractCompilationEnvironment)
internal fun candidateFiles(
	plan: SearchPlan,
	cancelChecker: ICancelChecker,
): List<Path> =
	when (val scope = plan.scope) {
		// The declaration's own file always contains its name, so there is nothing to filter.
		is UsageSearchScope.SingleFile -> {
			listOf(scope.path)
		}

		is UsageSearchScope.Modules -> {
			scope.modules
				.asSequence()
				.filter { it.isSourceModule }
				.flatMap { it.computeFiles(extended = true) }
				// A source module's files are .kt *and* .java, and `ktFileFor` rejects a non-Kotlin path
				// anyway (searching .java is a non-goal). Dropping them here, on the extension alone,
				// stops a Java-heavy workspace spending most of the prefilter's I/O - the part the user
				// waits on - reading files whose result is already known to be nothing. The extensions
				// mirror `DocumentUtils.isKotlinFile`, which is what decides it downstream.
				.filter { it.extension == "kt" || it.extension == "kts" }
				.mapNotNull { runCatching { it.toNioPath() }.getOrNull() }
				.distinct()
				.filter {
					// Checked per file: a whole-workspace scan is seconds of I/O, and cancelling must stop it
					// rather than let it run to completion and then discard the result.
					cancelChecker.abortIfCancelled()
					mentionsName(it, plan.simpleName)
				}.toList()
		}
	}

/**
 * Whether the file at [path] writes [name] as a whole word.
 *
 * Read line by line through [FileManager] rather than through `StringSearch.containsWord`: that helper
 * scans only a file's first megabyte, so a usage below the mark is silently dropped, it does so through
 * one process-global `ByteBuffer` the Java LSP mutates concurrently from its own threads, and it rethrows
 * an unreadable file as a `RuntimeException` - which here would abort the whole search rather than skip
 * one file. [FileManager] keeps the property that matters: an open file is matched against its live
 * editor buffer. A name cannot span a line break, so matching per line is exact.
 */
private fun mentionsName(
	path: Path,
	name: String,
): Boolean =
	try {
		FileManager.getReader(path).use { reader ->
			reader.lineSequence().any { it.containsWord(name) }
		}
	} catch (e: IOException) {
		// One unreadable file must not lose the whole result.
		logger.debug("Usage search could not prefilter candidate {}", path, e)
		false
	}

/** Whether this line contains [name] bounded by non-identifier characters on both sides. */
private fun String.containsWord(name: String): Boolean {
	var at = indexOf(name)
	while (at >= 0) {
		val before = at - 1
		val after = at + name.length
		if ((before < 0 || !this[before].isIdentifierChar()) &&
			(after >= length || !this[after].isIdentifierChar())
		) {
			return true
		}
		at = indexOf(name, at + 1)
	}
	return false
}

private fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

/**
 * Every usage of [plan]'s target in the file at [path].
 *
 * One analysis session per file, so a preemption costs this file rather than the whole search, and the
 * live-PSI await stays outside `project.read` (R9).
 */
context(env: AbstractCompilationEnvironment)
private suspend fun usagesIn(
	path: Path,
	plan: SearchPlan,
	delegate: ICancelChecker,
): List<Location> =
	try {
		retryingOnPreemption(delegate, "Usage search in $path") { cancelChecker ->
			val ktFile = ktFileFor(path)
			if (ktFile == null) {
				logger.debug("Skipping candidate {}: no PSI", path)
				emptyList()
			} else {
				env.project.read {
					// The name filter is pure PSI, so it runs before the analysis session opens. A text
					// prefilter hit whose only mention is a comment or a string literal must not cost an
					// analysis-lock acquisition, a FIR session and a match-set restore to rule out - and on a
					// short, common name most candidates are exactly that.
					val named = namedReferences(ktFile, plan.simpleName, cancelChecker)
					if (named.isEmpty()) {
						emptyList()
					} else {
						analyzeMaybeDangling(ktFile, AnalysisPriority.COMMAND, cancelChecker) {
							matchingReferences(named, plan, ktFile, path, cancelChecker)
						}
					}
				}
			}
		}
	} catch (e: AnalysisPreemptedException) {
		// A preemption that outlived retryingOnPreemption's single retry is keystroke-driven work winning
		// the lock, not the user cancelling. Rethrowing it would discard every location collected so far
		// and report "no references" for a symbol with plenty, so it costs this file like any other
		// failure. Genuine cancellation still propagates below (R12).
		logger.debug("Usage search gave up on candidate {}: preempted twice", path)
		emptyList()
	} catch (e: Throwable) {
		if (e.isAnalysisCancellation()) throw e
		// One unresolvable file must not lose the whole result.
		logger.debug("Usage search skipped candidate {}", path, e)
		emptyList()
	}

/**
 * PSI for a candidate file: refreshed to the live editor buffer when the file is open, the indexed
 * on-disk instance otherwise.
 *
 * The open case must be awaited here, outside `project.read`, because the refresh it waits on needs
 * `project.write`. `getKtFile` cannot do it - it runs under `project.read` inside Analysis API
 * services, so it only ever peeks the live cache.
 */
context(env: AbstractCompilationEnvironment)
private suspend fun ktFileFor(path: Path): KtFile? =
	if (FileManager.isActive(path)) {
		env.ktSymbolIndex.getCurrentKtFile(path).await()
	} else {
		env.ktSymbolIndex.getKtFile(path)
	}

/**
 * The simple-name references in [ktFile] written as [simpleName].
 *
 * PSI alone, so it can rule a candidate file out before any analysis session is opened. It is also what
 * implements "convention references are not discovered": `a + b` contains no `plus` token, so it is never
 * a candidate.
 *
 * Filters during the walk rather than collecting every [KtSimpleNameExpression] and filtering after: on
 * the case the text prefilter is worst at - a short, common name in a large file - the intermediate list
 * is the bulk of the allocation, and the walk is long enough to need a cancellation checkpoint of its own.
 */
private fun namedReferences(
	ktFile: KtFile,
	simpleName: String,
	cancelChecker: ICancelChecker,
): List<KtSimpleNameExpression> {
	val found = mutableListOf<KtSimpleNameExpression>()

	ktFile.accept(
		object : PsiRecursiveElementWalkingVisitor() {
			override fun visitElement(element: PsiElement) {
				cancelChecker.abortIfCancelled()
				if (element is KtSimpleNameExpression && element.getReferencedName() == simpleName) {
					found.add(element)
				}
				super.visitElement(element)
			}
		},
	)

	return found
}

/**
 * The [references] that resolve into [plan]'s match set.
 *
 * Match-set pointers are restored **once** for this session; [KaSymbol] equality within a single
 * session compares the underlying FIR symbol, so it is the right comparison once both sides come from
 * the same session (R6).
 */
private fun KaSession.matchingReferences(
	references: List<KtSimpleNameExpression>,
	plan: SearchPlan,
	ktFile: KtFile,
	path: Path,
	cancelChecker: ICancelChecker,
): List<Location> {
	val targets = plan.matchSet.mapNotNull { it.restoreSymbol() }
	if (targets.isEmpty()) {
		// Under-reporting beats reporting something false, so a pointer that will not restore drops this
		// file rather than falling back to a looser comparison.
		logger.debug("No match-set symbol restored in {}; skipping", path)
		return emptyList()
	}

	return references.mapNotNull { reference ->
		cancelChecker.abortIfCancelled()
		if (resolvesInto(reference, targets)) locationOf(reference, ktFile, path) else null
	}
}

/** Whether [reference] resolves to one of [targets]. */
private fun KaSession.resolvesInto(
	reference: KtSimpleNameExpression,
	targets: List<KaSymbol>,
): Boolean =
	runCatching {
		reference.mainReference
			.resolveToSymbols()
			.asSequence()
			.map { (it as? KaCallableSymbol)?.fakeOverrideOriginal ?: it }
			.any { resolved -> targets.any { it == resolved } }
	}.getOrElse {
		if (it.isAnalysisCancellation()) throw it
		logger.debug("Could not resolve '{}'", reference.text, it)
		false
	}

/** [reference]'s name range as an editor [Location], or null when the file has no document. */
private fun locationOf(
	reference: KtSimpleNameExpression,
	ktFile: KtFile,
	path: Path,
): Location? {
	val range = rangeOf(reference.getReferencedNameElement(), ktFile)
	if (range == Range.NONE) {
		logger.debug("No document for {}; dropping usage", path)
		return null
	}
	return Location(path, range)
}
