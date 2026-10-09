package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.projects.classpath.JarFsClasspathReader
import com.itsaky.androidide.utils.ClassTrie
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Paths

/**
 * A module's [ModuleClasspathLookup] answers every query its consumers asked of the module's
 * classpath trie the way the trie did.
 *
 * The oracle is built the way `ModuleProject.indexClasspaths` fills the trie: `JarFsClasspathReader`
 * filtered to top-level classes, appended to a [ClassTrie]. The lookup reads real SQLite-backed
 * indexes filled by [CombinedJarScanner], so the scanner, the package table and the query layer are
 * all under test. kotlin-stdlib sits in the library index and, like `R.jar`, in the generated index
 * too, so every answer must be deduplicated; JUnit sits in the module-output index, standing in for
 * the output JAR of a project module the module compiles against.
 *
 * It runs against real JARs because the shapes that matter do not occur in a hand-built fixture:
 * Kotlin file facades, multi-file class parts and deep package trees in kotlin-stdlib, and
 * package-private and nested Java classes in JUnit.
 *
 * Two differences are intended, and each has a test proving it is non-empty and of its kind:
 * multi-file class parts are withheld, and a prefix query no longer adds the fuzzy matches
 * (`FuzzySearch.ratio > 59`) the trie's consumers added on top of prefix matches. Any other
 * difference fails.
 */
@RunWith(RobolectricTestRunner::class)
class ModuleClasspathLookupDifferentialTest {
	private class Corpus(
		anchor: Class<*>,
	) {
		val jar: File by lazy { testClasspathJar(anchor) }
		val sourceId: String get() = jar.absolutePath
		val symbols: List<JvmSymbol> by lazy { CombinedJarScanner.scan(Paths.get(sourceId), sourceId).toList() }
	}

	private companion object {
		val stdlib = Corpus(Unit::class.java)
		val junit = Corpus(Test::class.java)

		/** Typos the fuzzy tier matched to a class in the corpora, and no class name starts with. */
		val TYPOS = listOf("Asert", "Colections")
	}

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val indexes = mutableListOf<JvmSymbolIndex>()

	private val libraryIndex by lazy { sqliteIndex(stdlib) }
	private val generatedIndex by lazy { sqliteIndex(stdlib) }
	private val moduleOutputIndex by lazy { sqliteIndex(junit) }

	@After
	fun tearDown() {
		indexes.forEach(JvmSymbolIndex::close)
	}

	private fun sqliteIndex(corpus: Corpus): JvmSymbolIndex {
		val backing =
			SQLiteIndex(
				descriptor = JvmSymbolDescriptor,
				context = context,
				dbName = null,
				formatVersion = JvmSymbolIndex.FORMAT_VERSION,
			)
		val index = JvmSymbolIndex(backing, BackgroundIndexer(backing)).also(indexes::add)
		index.setActiveSources(setOf(corpus.sourceId))
		index.indexSource(corpus.sourceId) { corpus.symbols.asSequence() }
		runBlocking { index.awaitIndexing() }
		return index
	}

	/** The lookup of a module whose compile classpath is [classpath]. */
	private fun lookup(vararg classpath: Corpus): ModuleClasspathLookup =
		ModuleClasspathLookup(libraryIndex, generatedIndex, moduleOutputIndex) {
			ClasspathScope(
				classpath = classpath.map { it.sourceId },
				moduleOutputs = classpath.filter { it === junit }.map { it.sourceId },
			)
		}

	/** What the module's classpath trie held for [classpath]. */
	private class Oracle(
		vararg classpath: Corpus,
	) {
		val trie = ClassTrie()

		init {
			JarFsClasspathReader()
				.listClasses(classpath.map { it.jar })
				.filter { it.isTopLevel }
				.forEach { trie.append(it.name) }
		}

		val classes: Set<String> = trie.allClassNames()

		/** The trie's classes the index offers too: all but the multi-file parts. */
		val offered: Set<String> = classes.filterNot(::isMultiFilePart).toSet()

		val packages: List<ClassTrie.Node> = packageNodes(trie.root)

		private fun packageNodes(node: ClassTrie.Node): List<ClassTrie.Node> =
			listOf(node) +
				node.children.values
					.filter { it.children.isNotEmpty() }
					.flatMap(::packageNodes)

		fun subpackagesOf(node: ClassTrie.Node): Set<String> =
			node.children.values
				.filter { it.children.isNotEmpty() }
				.map { it.qualifiedName }
				.toSet()

		fun offeredClassesIn(node: ClassTrie.Node): Set<String> =
			node.children.values
				.filter { it.isClass && it.qualifiedName in offered }
				.map { it.qualifiedName }
				.toSet()

		/** Whether the trie matches [prefix] against [qualifiedName]'s simple name, ignoring case. */
		fun matchesPrefix(
			qualifiedName: String,
			prefix: String,
		) = simpleName(qualifiedName).lowercase().startsWith(prefix.lowercase())
	}

	/** Where the import completion's walk over the segments of a name ends. */
	private sealed interface Walk {
		/** A segment before the last is a class: its members must be completed instead. */
		data object ClassMember : Walk

		data class Found(
			val isClass: Boolean,
			val isPackage: Boolean,
		) : Walk

		data object Absent : Walk
	}

	/** The trie walk of the import completion: it stops at the first segment that is a class. */
	private fun Oracle.walk(name: String): Walk {
		var node: ClassTrie.Node? = trie.root
		for (segment in trie.segments(name)) {
			if (node == null) break
			if (node.isClass) return Walk.ClassMember
			node = node.children[segment]
		}
		return node?.let { Walk.Found(it.isClass, it.children.isNotEmpty()) } ?: Walk.Absent
	}

	/** The same walk as point lookups: a class check on every proper prefix, then the name itself. */
	private fun ModuleClasspathLookup.walk(name: String): Walk {
		val segments = name.split('.')
		for (end in 1 until segments.size) {
			if (isClass(segments.subList(0, end).joinToString("."))) return Walk.ClassMember
		}
		val isClass = isClass(name)
		val isPackage = isPackage(name)
		return if (isClass || isPackage) Walk.Found(isClass, isPackage) else Walk.Absent
	}

	private fun ModuleClasspathLookup.subpackagesOf(packageName: String) =
		children(packageName).filterNot { it.isClass }.map { it.qualifiedName }

	private fun ModuleClasspathLookup.classesIn(packageName: String) = children(packageName).filter { it.isClass }.map { it.qualifiedName }

	@Test
	fun `the root children are the trie's root children`() {
		val oracle = Oracle(stdlib, junit)
		val expected =
			oracle.trie.root.children.values.flatMap { node ->
				listOfNotNull(
					Child(node.name, node.qualifiedName, isClass = false).takeIf { node.children.isNotEmpty() },
					Child(node.name, node.qualifiedName, isClass = true).takeIf { node.qualifiedName in oracle.offered },
				)
			}

		val children = lookup(stdlib, junit).children("")

		assertThat(children).containsExactlyElementsIn(expected)
		assertThat(children.map { it.name }).containsAtLeast("kotlin", "org", "junit")
	}

	@Test
	fun `every package's subpackages are the trie node's package children`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)

		for (node in oracle.packages) {
			assertWithMessage("subpackages of '${node.qualifiedName}'")
				.that(lookup.subpackagesOf(node.qualifiedName))
				.containsExactlyElementsIn(oracle.subpackagesOf(node))
		}
		assertThat(oracle.packages.size).isGreaterThan(20)
	}

	@Test
	fun `every package's classes are the trie node's class children`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)

		for (node in oracle.packages) {
			assertWithMessage("classes in '${node.qualifiedName}'")
				.that(lookup.classesIn(node.qualifiedName))
				.containsExactlyElementsIn(oracle.offeredClassesIn(node))
		}
	}

	@Test
	fun `every trie class and package ends the segment walk where the trie's walk ended`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)
		val names = oracle.offered + oracle.packages.map { it.qualifiedName }.filter { it.isNotEmpty() }

		for (name in names) {
			assertWithMessage("walk of '$name'").that(lookup.walk(name)).isEqualTo(oracle.walk(name))
		}
	}

	@Test
	fun `a name below a trie class asks for member completion, as the trie's walk did`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)

		for (name in oracle.offered.map { "$it.x" }) {
			assertWithMessage("walk of '$name'").that(oracle.walk(name)).isEqualTo(Walk.ClassMember)
			assertWithMessage("walk of '$name'").that(lookup.walk(name)).isEqualTo(Walk.ClassMember)
		}
	}

	@Test
	fun `every simple name resolves to the trie classes of that name`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)
		val bySimpleName = oracle.offered.groupBy(::simpleName)

		for (simpleName in oracle.classes.map(::simpleName).toSet()) {
			val found = lookup.qualifiedNamesOf(simpleName)

			assertWithMessage("classes named '$simpleName'")
				.that(found)
				.containsExactlyElementsIn(bySimpleName[simpleName].orEmpty())
			assertWithMessage("classes named '$simpleName'").that(found).containsNoDuplicates()
		}
	}

	@Test
	fun `every one and two character prefix matches the trie classes it matched, ignoring case`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)
		val present =
			oracle.offered
				.map(::simpleName)
				.flatMap { listOf(it.take(1), it.take(2)) }
				.toSet()
		val prefixes = present + present.map(::swapCase)

		for (prefix in prefixes) {
			val found = lookup.qualifiedNamesByPrefix(prefix, limit = Int.MAX_VALUE)

			assertWithMessage("classes by prefix '$prefix'")
				.that(found)
				.containsExactlyElementsIn(oracle.offered.filter { oracle.matchesPrefix(it, prefix) })
			assertWithMessage("classes by prefix '$prefix'").that(found).containsNoDuplicates()
		}
		assertThat(prefixes.size).isGreaterThan(200)
	}

	@Test
	fun `a module scoped to one JAR is offered nothing only the other JAR holds`() {
		for ((kept, other) in listOf(stdlib to junit, junit to stdlib)) {
			val keptOracle = Oracle(kept)
			val otherOracle = Oracle(other)
			val onlyOther = otherOracle.offered - keptOracle.classes
			val otherPackages = otherOracle.packages.map { it.qualifiedName } - keptOracle.packages.map { it.qualifiedName }.toSet()
			val lookup = lookup(kept)

			assertThat(onlyOther).isNotEmpty()
			assertThat(otherPackages).isNotEmpty()
			for (name in onlyOther) {
				assertWithMessage("class '$name'").that(lookup.isClass(name)).isFalse()
				assertWithMessage("classes named '${simpleName(name)}'")
					.that(lookup.qualifiedNamesOf(simpleName(name)))
					.doesNotContain(name)
			}
			for (name in otherPackages) {
				assertWithMessage("package '$name'").that(lookup.isPackage(name)).isFalse()
			}
			assertThat(lookup.children("").map { it.qualifiedName }.toSet())
				.containsExactlyElementsIn(keptOracle.trie.root.children.keys)
		}
	}

	@Test
	fun `the classes the lookup withholds are multi-file parts, and there are some`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)

		val withheld = oracle.classes.filterNot(lookup::isClass)

		assertThat(withheld).isNotEmpty()
		assertThat(withheld.filterNot(::isMultiFilePart)).isEmpty()
		assertThat(withheld.flatMap { lookup.qualifiedNamesOf(simpleName(it)) }).containsNoneIn(withheld)
	}

	@Test
	fun `a prefix query leaves out exactly the fuzzy matches the trie's consumers added, and there are some`() {
		val oracle = Oracle(stdlib, junit)
		val lookup = lookup(stdlib, junit)

		for (typo in TYPOS) {
			val fuzzyOnly =
				oracle.offered.filter { !oracle.matchesPrefix(it, typo) && fuzzyRatio(simpleName(it), typo) > FUZZY_MIN_RATIO }
			val consumerMatches = oracle.offered.filter { oracle.matchesPrefix(it, typo) } + fuzzyOnly

			val found = lookup.qualifiedNamesByPrefix(typo, limit = Int.MAX_VALUE)

			assertWithMessage("fuzzy-only matches of '$typo'").that(fuzzyOnly).isNotEmpty()
			assertWithMessage("matches of '$typo' the lookup leaves out")
				.that(consumerMatches - found.toSet())
				.containsExactlyElementsIn(fuzzyOnly)
			assertWithMessage("matches of '$typo'").that(found).containsNoneIn(fuzzyOnly)
		}
	}
}

/** The minimum ratio above which the trie's completion consumers took a fuzzy match. */
private const val FUZZY_MIN_RATIO = 59

/**
 * `FuzzySearch.ratio`, which is not on this module's classpath: the similarity of [a] and [b] in
 * percent, from their edit distance with insertions and deletions costing 1 and a substitution 2.
 *
 * That distance is `a.length + b.length - 2 * lcs`, so the ratio is `2 * lcs` over the total length.
 */
private fun fuzzyRatio(
	a: String,
	b: String,
): Int = Math.round(200.0 * longestCommonSubsequence(a, b) / (a.length + b.length)).toInt()

private fun longestCommonSubsequence(
	a: String,
	b: String,
): Int {
	var previous = IntArray(b.length + 1)
	for (i in a.indices) {
		val current = IntArray(b.length + 1)
		for (j in b.indices) {
			current[j + 1] = if (a[i] == b[j]) previous[j] + 1 else maxOf(previous[j + 1], current[j])
		}
		previous = current
	}
	return previous[b.length]
}

private fun simpleName(qualifiedName: String) = qualifiedName.substringAfterLast('.')

private fun swapCase(text: String) = text.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
