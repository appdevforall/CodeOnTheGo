package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [JvmSymbolIndex] using an in-memory backing index.
 *
 * Each test activates the source before inserting so that [FilteredIndex]
 * makes the entries visible.
 */
@RunWith(JUnit4::class)
class JvmSymbolIndexTest {

    private val defaultSource = "test.jar"

    private fun makeIndex(): JvmSymbolIndex {
        val backing = InMemoryIndex(JvmSymbolDescriptor)
        val indexer = BackgroundIndexer(backing)
        return JvmSymbolIndex(backing, indexer)
    }

    private fun classSymbol(
        internalName: String,
        pkg: String = internalName.substringBeforeLast('/').replace('/', '.'),
        shortName: String = internalName.substringAfterLast('/'),
        sourceId: String = defaultSource,
    ) = JvmSymbol(
        key = internalName,
        sourceId = sourceId,
        name = internalName,
        shortName = shortName,
        packageName = pkg,
        kind = JvmSymbolKind.CLASS,
        language = JvmSourceLanguage.KOTLIN,
        data = JvmClassInfo(),
    )

    private fun funSymbol(
        internalName: String,
        shortName: String,
        pkg: String,
        containingClass: String = "",
        receiverType: String = "",
        sourceId: String = defaultSource,
        params: List<JvmParameterInfo> = emptyList(),
    ): JvmSymbol {
        val kind = if (receiverType.isNotEmpty()) JvmSymbolKind.EXTENSION_FUNCTION else JvmSymbolKind.FUNCTION
        return JvmSymbol(
            key = "$internalName(${params.joinToString(",") { it.typeName }})",
            sourceId = sourceId,
            name = internalName,
            shortName = shortName,
            packageName = pkg,
            kind = kind,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(
                containingClassName = containingClass,
                parameters = params,
                parameterCount = params.size,
                kotlin = if (receiverType.isNotEmpty()) KotlinFunctionInfo(receiverTypeName = receiverType) else null,
            ),
        )
    }

    @Test
    fun `findByPrefix returns symbols with matching name prefix`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insertAll(
            sequenceOf(
                classSymbol("com/example/ArrayList"),
                classSymbol("com/example/ArrayDeque"),
                classSymbol("com/example/String"),
            )
        )

        val results = index.findByPrefix("Array").map { it.shortName }.toList()
        assertThat(results).containsExactly("ArrayList", "ArrayDeque")
    }

    @Test
    fun `findByPrefix is case-insensitive`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(classSymbol("com/example/ArrayList"))

        assertThat(index.findByPrefix("array").toList()).hasSize(1)
        assertThat(index.findByPrefix("ARRAY").toList()).hasSize(1)
    }

    @Test
    fun `findByPrefix respects limit`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        (1..20).forEach { i -> index.insert(classSymbol("com/example/ArrayType$i")) }

        assertThat(index.findByPrefix("Array", limit = 5).toList()).hasSize(5)
    }

    @Test
    fun `findByPrefix returns empty when no source activated`() = runTest {
        val index = makeIndex()
        index.insert(classSymbol("com/example/ArrayList"))
        // no activateSource call

        assertThat(index.findByPrefix("Array").toList()).isEmpty()
    }

    @Test
    fun `findByPrefix returns empty when prefix does not match`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(classSymbol("com/example/String"))

        assertThat(index.findByPrefix("Array").toList()).isEmpty()
    }

    @Test
    fun `findExtensionsFor returns extensions with given receiver type`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insertAll(
            sequenceOf(
                funSymbol("com/example/filter", "filter", "com.example",
                    receiverType = "kotlin/collections/List"),
                funSymbol("com/example/map", "map", "com.example",
                    receiverType = "kotlin/collections/List"),
                funSymbol("com/example/size", "size", "com.example",
                    receiverType = "kotlin/collections/Map"),
            )
        )

        val results = index.findExtensionsFor("kotlin/collections/List").map { it.shortName }.toList()
        assertThat(results).containsExactly("filter", "map")
    }

    @Test
    fun `findExtensionsFor with namePrefix filters further`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insertAll(
            sequenceOf(
                funSymbol("com/example/filter", "filter", "com.example",
                    receiverType = "kotlin/collections/List"),
                funSymbol("com/example/filterNot", "filterNot", "com.example",
                    receiverType = "kotlin/collections/List"),
                funSymbol("com/example/map", "map", "com.example",
                    receiverType = "kotlin/collections/List"),
            )
        )

        val results = index.findExtensionsFor("kotlin/collections/List", namePrefix = "filter")
            .map { it.shortName }.toList()
        assertThat(results).containsExactly("filter", "filterNot")
    }

    @Test
    fun `findTopLevelCallablesInPackage returns only top-level callables`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        // Top-level fun
        index.insert(funSymbol("com/example/topFun", "topFun", "com.example"))
        // Member fun (has containing class → not top-level)
        index.insert(
            funSymbol(
                "com/example/Foo.memberFun", "memberFun", "com.example",
                containingClass = "com/example/Foo",
            )
        )
        // Class (not callable)
        index.insert(classSymbol("com/example/Foo"))

        val results = index.findTopLevelCallablesInPackage("com.example").map { it.shortName }.toList()
        assertThat(results).containsExactly("topFun")
    }

    @Test
    fun `findTopLevelCallablesInPackage with namePrefix`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(funSymbol("com/example/filter", "filter", "com.example"))
        index.insert(funSymbol("com/example/filterNot", "filterNot", "com.example"))
        index.insert(funSymbol("com/example/map", "map", "com.example"))

        val results = index.findTopLevelCallablesInPackage("com.example", namePrefix = "filter")
            .map { it.shortName }.toList()
        assertThat(results).containsExactly("filter", "filterNot")
    }

    @Test
    fun `findClassifiersInPackage returns only classifiers`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(classSymbol("com/example/Foo"))
        index.insert(classSymbol("com/example/Bar"))
        index.insert(funSymbol("com/example/bazFun", "bazFun", "com.example"))

        val results = index.findClassifiersInPackage("com.example").map { it.shortName }.toList()
        assertThat(results).containsExactly("Foo", "Bar")
    }

    @Test
    fun `findClassifiersInPackage with namePrefix`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(classSymbol("com/example/Array"))
        index.insert(classSymbol("com/example/ArrayList"))
        index.insert(classSymbol("com/example/String"))

        val results = index.findClassifiersInPackage("com.example", namePrefix = "Array")
            .map { it.shortName }.toList()
        assertThat(results).containsExactly("Array", "ArrayList")
    }

    @Test
    fun `findMembersOf returns symbols with given containing class`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(funSymbol("com/example/Foo.m1", "m1", "com.example",
            containingClass = "com/example/Foo"))
        index.insert(funSymbol("com/example/Foo.m2", "m2", "com.example",
            containingClass = "com/example/Foo"))
        index.insert(funSymbol("com/example/Bar.other", "other", "com.example",
            containingClass = "com/example/Bar"))

        val results = index.findMembersOf("com/example/Foo").map { it.shortName }.toList()
        assertThat(results).containsExactly("m1", "m2")
    }

    @Test
    fun `findMembersOf with namePrefix`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(funSymbol("com/example/Foo.get", "get", "com.example",
            containingClass = "com/example/Foo"))
        index.insert(funSymbol("com/example/Foo.getOrNull", "getOrNull", "com.example",
            containingClass = "com/example/Foo"))
        index.insert(funSymbol("com/example/Foo.set", "set", "com.example",
            containingClass = "com/example/Foo"))

        val results = index.findMembersOf("com/example/Foo", namePrefix = "get")
            .map { it.shortName }.toList()
        assertThat(results).containsExactly("get", "getOrNull")
    }

    @Test
    fun `allPackages returns distinct packages from active sources`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        index.insert(classSymbol("com/example/Foo"))
        index.insert(classSymbol("com/example/Bar"))
        index.insert(classSymbol("org/other/Baz", pkg = "org.other"))

        val packages = index.allPackages().toSet()
        assertThat(packages).containsAtLeast("com.example", "org.other")
    }

    @Test
    fun `findByKey retrieves symbol by exact key`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        val symbol = classSymbol("com/example/Foo")
        index.insert(symbol)

        assertThat(index.findByKey("com/example/Foo")).isEqualTo(symbol)
    }

    @Test
    fun `findByKey returns null for unknown key`() = runTest {
        val index = makeIndex()
        index.activateSource(defaultSource)
        assertThat(index.findByKey("missing")).isNull()
    }

    @Test
    fun `findByKey returns null when source not activated`() = runTest {
        val index = makeIndex()
        val symbol = classSymbol("com/example/Foo")
        index.insert(symbol)
        // no activateSource
        assertThat(index.findByKey("com/example/Foo")).isNull()
    }

    @Test
    fun `symbols from inactive sources are not visible`() = runTest {
        val index = makeIndex()
        index.insert(classSymbol("com/a/A", sourceId = "a.jar"))
        index.insert(classSymbol("com/b/B", sourceId = "b.jar"))

        index.activateSource("a.jar")
        // b.jar not activated

        val results = index.findByPrefix("").toList()
        assertThat(results.all { it.sourceId == "a.jar" }).isTrue()
    }

    @Test
    fun `activating multiple sources shows all their symbols`() = runTest {
        val index = makeIndex()
        index.insert(classSymbol("com/a/A", sourceId = "a.jar"))
        index.insert(classSymbol("com/b/B", sourceId = "b.jar"))

        index.activateSource("a.jar")
        index.activateSource("b.jar")

        val results = index.findByPrefix("").toList()
        assertThat(results).hasSize(2)
    }
}
