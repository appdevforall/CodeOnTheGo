package org.appdevforall.codeonthego.lsp.kotlin.generator

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

/**
 * Generates stdlib-index.json from Kotlin standard library using reflection.
 *
 * Run with: ./gradlew :stdlib-generator:generateStdlibIndex
 */
fun main(args: Array<String>) {
    val outputDir = if (args.isNotEmpty()) File(args[0]) else File(".")
    outputDir.mkdirs()

    val generator = StdlibIndexGenerator()
    val indexData = generator.generate()

    val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    val outputFile = File(outputDir, "stdlib-index.json")
    outputFile.writeText(json.encodeToString(indexData))

    println("Generated ${outputFile.absolutePath}")
    println("Total symbols: ${indexData.totalCount}")
    println("Classes: ${indexData.classes.size}")
    println("Top-level functions: ${indexData.topLevelFunctions.size}")
    println("Top-level properties: ${indexData.topLevelProperties.size}")
    println("Extensions: ${indexData.extensions.values.sumOf { it.size }}")
}

class StdlibIndexGenerator {

    private val processedClasses = mutableSetOf<String>()
    private val extractor = ReflectionSymbolExtractor()

    fun generate(): GeneratedIndexData {
        val classes = mutableMapOf<String, GeneratedClassEntry>()
        val topLevelFunctions = mutableListOf<GeneratedIndexEntry>()
        val topLevelProperties = mutableListOf<GeneratedIndexEntry>()
        val extensions = mutableMapOf<String, MutableList<GeneratedIndexEntry>>()
        val typeAliases = mutableListOf<GeneratedIndexEntry>()

        val stdlibPackages = listOf(
            "kotlin",
            "kotlin.collections",
            "kotlin.comparisons",
            "kotlin.io",
            "kotlin.ranges",
            "kotlin.sequences",
            "kotlin.text",
            "kotlin.math",
            "kotlin.reflect"
        )

        for (packageName in stdlibPackages) {
            processPackage(packageName, classes, topLevelFunctions, topLevelProperties, extensions)
        }

        extractTopLevelFunctions(topLevelFunctions, extensions)

        processCoreTypes(classes)
        addManualClasses(classes)

        return GeneratedIndexData(
            version = "1.0",
            kotlinVersion = KotlinVersion.CURRENT.toString(),
            classes = classes,
            topLevelFunctions = topLevelFunctions,
            topLevelProperties = topLevelProperties,
            extensions = extensions,
            typeAliases = typeAliases
        )
    }

    private fun extractTopLevelFunctions(
        functions: MutableList<GeneratedIndexEntry>,
        extensions: MutableMap<String, MutableList<GeneratedIndexEntry>>
    ) {
        val facadeClasses = listOf(
            "kotlin.io.ConsoleKt" to "kotlin.io",
            "kotlin.StandardKt" to "kotlin",
            "kotlin.PreconditionsKt" to "kotlin",
            "kotlin.LateinitKt" to "kotlin",
            "kotlin.TuplesKt" to "kotlin",
            "kotlin.collections.CollectionsKt" to "kotlin.collections",
            "kotlin.collections.MapsKt" to "kotlin.collections",
            "kotlin.collections.SetsKt" to "kotlin.collections",
            "kotlin.collections.ArraysKt" to "kotlin.collections",
            "kotlin.collections.SequencesKt" to "kotlin.sequences",
            "kotlin.text.StringsKt" to "kotlin.text",
            "kotlin.text.CharsKt" to "kotlin.text",
            "kotlin.ranges.RangesKt" to "kotlin.ranges",
            "kotlin.comparisons.ComparisonsKt" to "kotlin.comparisons",
            "kotlin.math.MathKt" to "kotlin.math"
        )

        for ((className, packageName) in facadeClasses) {
            try {
                val facadeClass = Class.forName(className)
                extractFunctionsFromFacade(facadeClass, packageName, functions, extensions)
            } catch (e: ClassNotFoundException) {
                try {
                    val altClassName = "${className}__${className.substringAfterLast(".")}Kt"
                    val facadeClass = Class.forName(altClassName)
                    extractFunctionsFromFacade(facadeClass, packageName, functions, extensions)
                } catch (e2: Exception) {
                    // Skip if class not found
                }
            } catch (e: Exception) {
                System.err.println("Error processing facade $className: ${e.message}")
            }
        }

        addBuiltInFunctions(functions)
        addBuiltInExtensions(extensions)
    }

    private fun extractFunctionsFromFacade(
        facadeClass: Class<*>,
        packageName: String,
        functions: MutableList<GeneratedIndexEntry>,
        extensions: MutableMap<String, MutableList<GeneratedIndexEntry>>
    ) {
        for (method in facadeClass.declaredMethods) {
            if (!java.lang.reflect.Modifier.isPublic(method.modifiers)) continue
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue

            val name = method.name
            if (name.contains("$")) continue

            val params = method.parameters.mapIndexed { index, param ->
                GeneratedParamEntry(
                    name = param.name ?: "p$index",
                    type = param.type.kotlin.qualifiedName ?: "Any",
                    def = false,
                    vararg = param.isVarArgs
                )
            }

            val returnType = method.returnType.kotlin.qualifiedName ?: "Unit"
            val fqName = "$packageName.$name"

            val hasReceiver = params.isNotEmpty() &&
                method.parameterAnnotations.firstOrNull()?.any {
                    it.annotationClass.simpleName == "ExtensionFunctionType"
                } == true

            if (hasReceiver && params.isNotEmpty()) {
                val receiverType = params.first().type
                val entry = GeneratedIndexEntry(
                    name = name,
                    fqName = fqName,
                    kind = "FUNCTION",
                    pkg = packageName,
                    params = params.drop(1),
                    ret = returnType,
                    recv = receiverType
                )
                extensions.getOrPut(receiverType) { mutableListOf() }.add(entry)
            } else {
                val entry = GeneratedIndexEntry(
                    name = name,
                    fqName = fqName,
                    kind = "FUNCTION",
                    pkg = packageName,
                    params = params,
                    ret = returnType
                )
                if (functions.none { it.name == name && it.pkg == packageName }) {
                    functions.add(entry)
                }
            }
        }
    }

    private data class BuiltInFunction(
        val name: String,
        val pkg: String,
        val params: List<Pair<String, String>>,
        val returnType: String
    )

    private fun addBuiltInFunctions(functions: MutableList<GeneratedIndexEntry>) {
        val builtIns = listOf(
            BuiltInFunction("println", "kotlin.io", listOf("message" to "Any?"), "Unit"),
            BuiltInFunction("println", "kotlin.io", emptyList(), "Unit"),
            BuiltInFunction("print", "kotlin.io", listOf("message" to "Any?"), "Unit"),
            BuiltInFunction("readLine", "kotlin.io", emptyList(), "String?"),
            BuiltInFunction("readln", "kotlin.io", emptyList(), "String"),
            BuiltInFunction("listOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "List<T>"),
            BuiltInFunction("listOf", "kotlin.collections", emptyList(), "List<T>"),
            BuiltInFunction("listOfNotNull", "kotlin.collections", listOf("elements" to "Array<out T?>"), "List<T>"),
            BuiltInFunction("mutableListOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "MutableList<T>"),
            BuiltInFunction("mutableListOf", "kotlin.collections", emptyList(), "MutableList<T>"),
            BuiltInFunction("arrayListOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "ArrayList<T>"),
            BuiltInFunction("setOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "Set<T>"),
            BuiltInFunction("setOf", "kotlin.collections", emptyList(), "Set<T>"),
            BuiltInFunction("mutableSetOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "MutableSet<T>"),
            BuiltInFunction("hashSetOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "HashSet<T>"),
            BuiltInFunction("linkedSetOf", "kotlin.collections", listOf("elements" to "Array<out T>"), "LinkedHashSet<T>"),
            BuiltInFunction("mapOf", "kotlin.collections", listOf("pairs" to "Array<out Pair<K, V>>"), "Map<K, V>"),
            BuiltInFunction("mapOf", "kotlin.collections", emptyList(), "Map<K, V>"),
            BuiltInFunction("mutableMapOf", "kotlin.collections", listOf("pairs" to "Array<out Pair<K, V>>"), "MutableMap<K, V>"),
            BuiltInFunction("hashMapOf", "kotlin.collections", listOf("pairs" to "Array<out Pair<K, V>>"), "HashMap<K, V>"),
            BuiltInFunction("linkedMapOf", "kotlin.collections", listOf("pairs" to "Array<out Pair<K, V>>"), "LinkedHashMap<K, V>"),
            BuiltInFunction("emptyList", "kotlin.collections", emptyList(), "List<T>"),
            BuiltInFunction("emptySet", "kotlin.collections", emptyList(), "Set<T>"),
            BuiltInFunction("emptyMap", "kotlin.collections", emptyList(), "Map<K, V>"),
            BuiltInFunction("emptyArray", "kotlin.collections", emptyList(), "Array<T>"),
            BuiltInFunction("arrayOf", "kotlin", listOf("elements" to "Array<out T>"), "Array<T>"),
            BuiltInFunction("intArrayOf", "kotlin", listOf("elements" to "IntArray"), "IntArray"),
            BuiltInFunction("longArrayOf", "kotlin", listOf("elements" to "LongArray"), "LongArray"),
            BuiltInFunction("floatArrayOf", "kotlin", listOf("elements" to "FloatArray"), "FloatArray"),
            BuiltInFunction("doubleArrayOf", "kotlin", listOf("elements" to "DoubleArray"), "DoubleArray"),
            BuiltInFunction("booleanArrayOf", "kotlin", listOf("elements" to "BooleanArray"), "BooleanArray"),
            BuiltInFunction("charArrayOf", "kotlin", listOf("elements" to "CharArray"), "CharArray"),
            BuiltInFunction("byteArrayOf", "kotlin", listOf("elements" to "ByteArray"), "ByteArray"),
            BuiltInFunction("shortArrayOf", "kotlin", listOf("elements" to "ShortArray"), "ShortArray"),
            BuiltInFunction("sequenceOf", "kotlin.sequences", listOf("elements" to "Array<out T>"), "Sequence<T>"),
            BuiltInFunction("emptySequence", "kotlin.sequences", emptyList(), "Sequence<T>"),
            BuiltInFunction("require", "kotlin", listOf("value" to "Boolean"), "Unit"),
            BuiltInFunction("requireNotNull", "kotlin", listOf("value" to "T?"), "T"),
            BuiltInFunction("check", "kotlin", listOf("value" to "Boolean"), "Unit"),
            BuiltInFunction("checkNotNull", "kotlin", listOf("value" to "T?"), "T"),
            BuiltInFunction("error", "kotlin", listOf("message" to "Any"), "Nothing"),
            BuiltInFunction("TODO", "kotlin", listOf("reason" to "String"), "Nothing"),
            BuiltInFunction("TODO", "kotlin", emptyList(), "Nothing"),
            BuiltInFunction("run", "kotlin", listOf("block" to "() -> R"), "R"),
            BuiltInFunction("with", "kotlin", listOf("receiver" to "T", "block" to "T.() -> R"), "R"),
            BuiltInFunction("apply", "kotlin", listOf("block" to "T.() -> Unit"), "T"),
            BuiltInFunction("also", "kotlin", listOf("block" to "(T) -> Unit"), "T"),
            BuiltInFunction("let", "kotlin", listOf("block" to "(T) -> R"), "R"),
            BuiltInFunction("takeIf", "kotlin", listOf("predicate" to "(T) -> Boolean"), "T?"),
            BuiltInFunction("takeUnless", "kotlin", listOf("predicate" to "(T) -> Boolean"), "T?"),
            BuiltInFunction("repeat", "kotlin", listOf("times" to "Int", "action" to "(Int) -> Unit"), "Unit"),
            BuiltInFunction("lazy", "kotlin", listOf("initializer" to "() -> T"), "Lazy<T>"),
            BuiltInFunction("lazyOf", "kotlin", listOf("value" to "T"), "Lazy<T>"),
            BuiltInFunction("buildString", "kotlin.text", listOf("builderAction" to "StringBuilder.() -> Unit"), "String"),
            BuiltInFunction("buildList", "kotlin.collections", listOf("builderAction" to "MutableList<E>.() -> Unit"), "List<E>"),
            BuiltInFunction("buildSet", "kotlin.collections", listOf("builderAction" to "MutableSet<E>.() -> Unit"), "Set<E>"),
            BuiltInFunction("buildMap", "kotlin.collections", listOf("builderAction" to "MutableMap<K, V>.() -> Unit"), "Map<K, V>"),
            BuiltInFunction("maxOf", "kotlin.comparisons", listOf("a" to "T", "b" to "T"), "T"),
            BuiltInFunction("minOf", "kotlin.comparisons", listOf("a" to "T", "b" to "T"), "T"),
            BuiltInFunction("to", "kotlin", listOf("that" to "B"), "Pair<A, B>"),
            BuiltInFunction("Pair", "kotlin", listOf("first" to "A", "second" to "B"), "Pair<A, B>"),
            BuiltInFunction("Triple", "kotlin", listOf("first" to "A", "second" to "B", "third" to "C"), "Triple<A, B, C>")
        )

        for (func in builtIns) {
            if (functions.any { it.name == func.name && it.pkg == func.pkg && it.params.size == func.params.size }) continue

            val params = func.params.map { (pName, pType) ->
                GeneratedParamEntry(name = pName, type = pType)
            }

            functions.add(GeneratedIndexEntry(
                name = func.name,
                fqName = "${func.pkg}.${func.name}",
                kind = "FUNCTION",
                pkg = func.pkg,
                params = params,
                ret = func.returnType
            ))
        }
    }

    private fun addBuiltInExtensions(extensions: MutableMap<String, MutableList<GeneratedIndexEntry>>) {
        val iterableExtensions = mapOf(
            "forEach" to "Unit",
            "forEachIndexed" to "Unit",
            "map" to "List<R>",
            "mapNotNull" to "List<R>",
            "mapIndexed" to "List<R>",
            "filter" to "List<T>",
            "filterNot" to "List<T>",
            "filterNotNull" to "List<T>",
            "flatMap" to "List<R>",
            "flatMapIndexed" to "List<R>",
            "fold" to "R",
            "reduce" to "T",
            "first" to "T",
            "firstOrNull" to "T?",
            "last" to "T",
            "lastOrNull" to "T?",
            "single" to "T",
            "singleOrNull" to "T?",
            "find" to "T?",
            "findLast" to "T?",
            "any" to "Boolean",
            "all" to "Boolean",
            "none" to "Boolean",
            "count" to "Int",
            "toList" to "List<T>",
            "toMutableList" to "MutableList<T>",
            "toSet" to "Set<T>",
            "toMutableSet" to "MutableSet<T>",
            "sorted" to "List<T>",
            "sortedBy" to "List<T>",
            "sortedDescending" to "List<T>",
            "sortedByDescending" to "List<T>",
            "reversed" to "List<T>",
            "shuffled" to "List<T>",
            "distinct" to "List<T>",
            "distinctBy" to "List<T>",
            "take" to "List<T>",
            "takeLast" to "List<T>",
            "takeWhile" to "List<T>",
            "takeLastWhile" to "List<T>",
            "drop" to "List<T>",
            "dropLast" to "List<T>",
            "dropWhile" to "List<T>",
            "dropLastWhile" to "List<T>",
            "zip" to "List<Pair<T, R>>",
            "zipWithNext" to "List<R>",
            "partition" to "Pair<List<T>, List<T>>",
            "groupBy" to "Map<K, List<T>>",
            "associate" to "Map<K, V>",
            "associateBy" to "Map<K, T>",
            "associateWith" to "Map<T, V>",
            "joinToString" to "String",
            "joinTo" to "A",
            "sum" to "Int",
            "sumOf" to "R",
            "average" to "Double",
            "max" to "T",
            "maxOrNull" to "T?",
            "min" to "T",
            "minOrNull" to "T?",
            "maxBy" to "T",
            "maxByOrNull" to "T?",
            "minBy" to "T",
            "minByOrNull" to "T?",
            "contains" to "Boolean",
            "indexOf" to "Int",
            "lastIndexOf" to "Int",
            "isEmpty" to "Boolean",
            "isNotEmpty" to "Boolean",
            "plus" to "List<T>",
            "minus" to "List<T>",
            "plusElement" to "List<T>",
            "minusElement" to "List<T>",
            "onEach" to "C",
            "onEachIndexed" to "C",
            "asSequence" to "Sequence<T>",
            "asIterable" to "Iterable<T>",
            "iterator" to "Iterator<T>",
            "withIndex" to "Iterable<IndexedValue<T>>",
            "chunked" to "List<List<T>>",
            "windowed" to "List<List<T>>",
            "unzip" to "Pair<List<T>, List<R>>"
        )

        val stringExtensions = mapOf(
            "trim" to "String",
            "trimStart" to "String",
            "trimEnd" to "String",
            "padStart" to "String",
            "padEnd" to "String",
            "split" to "List<String>",
            "lines" to "List<String>",
            "replace" to "String",
            "replaceFirst" to "String",
            "replaceRange" to "String",
            "substring" to "String",
            "substringBefore" to "String",
            "substringAfter" to "String",
            "substringBeforeLast" to "String",
            "substringAfterLast" to "String",
            "startsWith" to "Boolean",
            "endsWith" to "Boolean",
            "removePrefix" to "String",
            "removeSuffix" to "String",
            "uppercase" to "String",
            "lowercase" to "String",
            "capitalize" to "String",
            "decapitalize" to "String",
            "toInt" to "Int",
            "toLong" to "Long",
            "toFloat" to "Float",
            "toDouble" to "Double",
            "toBoolean" to "Boolean",
            "toIntOrNull" to "Int?",
            "toLongOrNull" to "Long?",
            "toFloatOrNull" to "Float?",
            "toDoubleOrNull" to "Double?",
            "toByteArray" to "ByteArray",
            "toCharArray" to "CharArray",
            "isBlank" to "Boolean",
            "isNotBlank" to "Boolean",
            "isEmpty" to "Boolean",
            "isNotEmpty" to "Boolean",
            "isNullOrBlank" to "Boolean",
            "isNullOrEmpty" to "Boolean",
            "orEmpty" to "String",
            "reversed" to "String",
            "repeat" to "String",
            "format" to "String",
            "contains" to "Boolean",
            "indexOf" to "Int",
            "lastIndexOf" to "Int",
            "first" to "Char",
            "last" to "Char",
            "single" to "Char",
            "take" to "String",
            "drop" to "String",
            "filter" to "String",
            "map" to "List<R>",
            "flatMap" to "List<R>",
            "forEach" to "Unit"
        )

        val mapExtensions = mapOf(
            "get" to "V?",
            "getValue" to "V",
            "getOrDefault" to "V",
            "getOrElse" to "V",
            "getOrPut" to "V",
            "keys" to "Set<K>",
            "values" to "Collection<V>",
            "entries" to "Set<Map.Entry<K, V>>",
            "containsKey" to "Boolean",
            "containsValue" to "Boolean",
            "forEach" to "Unit",
            "map" to "List<R>",
            "mapKeys" to "Map<R, V>",
            "mapValues" to "Map<K, R>",
            "filter" to "Map<K, V>",
            "filterKeys" to "Map<K, V>",
            "filterValues" to "Map<K, V>",
            "filterNot" to "Map<K, V>",
            "toList" to "List<Pair<K, V>>",
            "toMutableMap" to "MutableMap<K, V>",
            "plus" to "Map<K, V>",
            "minus" to "Map<K, V>",
            "isEmpty" to "Boolean",
            "isNotEmpty" to "Boolean",
            "any" to "Boolean",
            "all" to "Boolean",
            "none" to "Boolean",
            "count" to "Int"
        )

        val collectionExtensions = mapOf(
            "size" to "Int",
            "indices" to "IntRange",
            "lastIndex" to "Int",
            "isEmpty" to "Boolean",
            "isNotEmpty" to "Boolean",
            "contains" to "Boolean",
            "containsAll" to "Boolean",
            "random" to "T",
            "randomOrNull" to "T?"
        )

        val anyExtensions = mapOf(
            "toString" to "String",
            "hashCode" to "Int",
            "equals" to "Boolean",
            "let" to "R",
            "run" to "R",
            "apply" to "T",
            "also" to "T",
            "takeIf" to "T?",
            "takeUnless" to "T?"
        )

        fun addExtensions(receiverType: String, extensionMap: Map<String, String>, pkg: String = "kotlin.collections") {
            val list = extensions.getOrPut(receiverType) { mutableListOf() }
            for ((name, returnType) in extensionMap) {
                if (list.any { it.name == name }) continue
                list.add(GeneratedIndexEntry(
                    name = name,
                    fqName = "$pkg.$name",
                    kind = "FUNCTION",
                    pkg = pkg,
                    recv = receiverType,
                    ret = returnType
                ))
            }
        }

        addExtensions("kotlin.collections.Iterable", iterableExtensions)
        addExtensions("Iterable", iterableExtensions)
        addExtensions("kotlin.collections.Collection", collectionExtensions)
        addExtensions("Collection", collectionExtensions)
        addExtensions("kotlin.collections.List", iterableExtensions + collectionExtensions)
        addExtensions("List", iterableExtensions + collectionExtensions)
        addExtensions("kotlin.collections.MutableList", iterableExtensions + collectionExtensions)
        addExtensions("MutableList", iterableExtensions + collectionExtensions)
        addExtensions("kotlin.collections.Set", iterableExtensions + collectionExtensions)
        addExtensions("Set", iterableExtensions + collectionExtensions)
        addExtensions("kotlin.collections.Map", mapExtensions)
        addExtensions("Map", mapExtensions)
        addExtensions("kotlin.String", stringExtensions, "kotlin.text")
        addExtensions("String", stringExtensions, "kotlin.text")
        addExtensions("kotlin.CharSequence", stringExtensions, "kotlin.text")
        addExtensions("CharSequence", stringExtensions, "kotlin.text")
        addExtensions("kotlin.Any", anyExtensions, "kotlin")
        addExtensions("Any", anyExtensions, "kotlin")
        addExtensions("kotlin.Array", iterableExtensions + collectionExtensions)
        addExtensions("Array", iterableExtensions + collectionExtensions)

        val primitiveArrayExtensions = mapOf(
            "sum" to "Int",
            "average" to "Double",
            "min" to "Int",
            "max" to "Int",
            "minOrNull" to "Int?",
            "maxOrNull" to "Int?",
            "forEach" to "Unit",
            "forEachIndexed" to "Unit",
            "map" to "List<R>",
            "filter" to "List<Int>",
            "first" to "Int",
            "firstOrNull" to "Int?",
            "last" to "Int",
            "lastOrNull" to "Int?",
            "contains" to "Boolean",
            "indexOf" to "Int",
            "lastIndexOf" to "Int",
            "toList" to "List<Int>",
            "toMutableList" to "MutableList<Int>",
            "toSet" to "Set<Int>",
            "sorted" to "List<Int>",
            "sortedDescending" to "List<Int>",
            "reversed" to "List<Int>",
            "distinct" to "List<Int>",
            "any" to "Boolean",
            "all" to "Boolean",
            "none" to "Boolean",
            "count" to "Int",
            "isEmpty" to "Boolean",
            "isNotEmpty" to "Boolean",
            "asList" to "List<Int>",
            "asIterable" to "Iterable<Int>",
            "asSequence" to "Sequence<Int>",
            "copyOf" to "IntArray",
            "copyOfRange" to "IntArray",
            "sliceArray" to "IntArray",
            "sortedArray" to "IntArray",
            "sortedArrayDescending" to "IntArray"
        )

        addExtensions("kotlin.IntArray", iterableExtensions + collectionExtensions + primitiveArrayExtensions)
        addExtensions("IntArray", iterableExtensions + collectionExtensions + primitiveArrayExtensions)

        val longArrayExtensions = primitiveArrayExtensions.mapValues { (k, v) ->
            v.replace("Int", "Long")
        }
        addExtensions("kotlin.LongArray", iterableExtensions + collectionExtensions + longArrayExtensions)
        addExtensions("LongArray", iterableExtensions + collectionExtensions + longArrayExtensions)

        val doubleArrayExtensions = primitiveArrayExtensions.mapValues { (k, v) ->
            v.replace("Int", "Double")
        }
        addExtensions("kotlin.DoubleArray", iterableExtensions + collectionExtensions + doubleArrayExtensions)
        addExtensions("DoubleArray", iterableExtensions + collectionExtensions + doubleArrayExtensions)

        val floatArrayExtensions = primitiveArrayExtensions.mapValues { (k, v) ->
            when {
                v == "Int" -> "Float"
                v.contains("Int") -> v.replace("Int", "Float")
                else -> v
            }
        }
        addExtensions("kotlin.FloatArray", iterableExtensions + collectionExtensions + floatArrayExtensions)
        addExtensions("FloatArray", iterableExtensions + collectionExtensions + floatArrayExtensions)

        addExtensions("kotlin.Sequence", iterableExtensions)
        addExtensions("Sequence", iterableExtensions)
    }

    private fun processPackage(
        packageName: String,
        classes: MutableMap<String, GeneratedClassEntry>,
        functions: MutableList<GeneratedIndexEntry>,
        properties: MutableList<GeneratedIndexEntry>,
        extensions: MutableMap<String, MutableList<GeneratedIndexEntry>>
    ) {
        val packageClasses = getClassesInPackage(packageName)

        for (kClass in packageClasses) {
            try {
                processClass(kClass, classes, extensions)
            } catch (e: Exception) {
                System.err.println("Error processing ${kClass.qualifiedName}: ${e.message}")
            }
        }
    }

    private fun getClassesInPackage(packageName: String): List<KClass<*>> {
        val coreTypes = when (packageName) {
            "kotlin" -> listOf(
                Any::class, Nothing::class, Unit::class,
                Boolean::class, Byte::class, Short::class, Int::class, Long::class,
                Float::class, Double::class, Char::class,
                String::class, CharSequence::class,
                Number::class, Comparable::class,
                Throwable::class, Exception::class, Error::class,
                RuntimeException::class, IllegalArgumentException::class,
                IllegalStateException::class, NullPointerException::class,
                IndexOutOfBoundsException::class, NoSuchElementException::class,
                UnsupportedOperationException::class, ConcurrentModificationException::class,
                ClassCastException::class, ArithmeticException::class,
                NumberFormatException::class, AssertionError::class,
                Pair::class, Triple::class,
                Lazy::class, Result::class,
                Function::class, KClass::class,
                Enum::class, Annotation::class,
                Cloneable::class
            )
            "kotlin.collections" -> listOf(
                Iterable::class, MutableIterable::class,
                Collection::class, MutableCollection::class,
                List::class, MutableList::class,
                Set::class, MutableSet::class,
                Map::class, MutableMap::class,
                Map.Entry::class, MutableMap.MutableEntry::class,
                Iterator::class, MutableIterator::class,
                ListIterator::class, MutableListIterator::class,
                ArrayList::class, LinkedHashSet::class, LinkedHashMap::class,
                HashSet::class, HashMap::class,
                ArrayDeque::class
            )
            "kotlin.ranges" -> listOf(
                IntRange::class, LongRange::class, CharRange::class,
                IntProgression::class, LongProgression::class, CharProgression::class,
                ClosedRange::class
            )
            "kotlin.sequences" -> listOf(
                Sequence::class
            )
            "kotlin.text" -> listOf(
                Regex::class, MatchResult::class, MatchGroup::class,
                StringBuilder::class, Appendable::class
            )
            else -> emptyList()
        }
        return coreTypes
    }

    private fun addManualClasses(classes: MutableMap<String, GeneratedClassEntry>) {
        val manualClasses = listOf(
            GeneratedClassEntry(
                fqName = "kotlin.text.StringBuilder",
                kind = "CLASS",
                supers = listOf("kotlin.CharSequence", "kotlin.text.Appendable"),
                members = listOf(
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Any?): StringBuilder", listOf(GeneratedParamEntry("value", "Any?")), "StringBuilder"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: String?): StringBuilder", listOf(GeneratedParamEntry("value", "String?")), "StringBuilder"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Char): StringBuilder", listOf(GeneratedParamEntry("value", "Char")), "StringBuilder"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Int): StringBuilder", listOf(GeneratedParamEntry("value", "Int")), "StringBuilder"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Long): StringBuilder", listOf(GeneratedParamEntry("value", "Long")), "StringBuilder"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Boolean): StringBuilder", listOf(GeneratedParamEntry("value", "Boolean")), "StringBuilder"),
                    GeneratedMemberEntry("appendLine", "FUNCTION", "fun appendLine(): StringBuilder", emptyList(), "StringBuilder"),
                    GeneratedMemberEntry("appendLine", "FUNCTION", "fun appendLine(value: Any?): StringBuilder", listOf(GeneratedParamEntry("value", "Any?")), "StringBuilder"),
                    GeneratedMemberEntry("insert", "FUNCTION", "fun insert(index: Int, value: Any?): StringBuilder", listOf(GeneratedParamEntry("index", "Int"), GeneratedParamEntry("value", "Any?")), "StringBuilder"),
                    GeneratedMemberEntry("delete", "FUNCTION", "fun delete(startIndex: Int, endIndex: Int): StringBuilder", listOf(GeneratedParamEntry("startIndex", "Int"), GeneratedParamEntry("endIndex", "Int")), "StringBuilder"),
                    GeneratedMemberEntry("deleteAt", "FUNCTION", "fun deleteAt(index: Int): StringBuilder", listOf(GeneratedParamEntry("index", "Int")), "StringBuilder"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): StringBuilder", emptyList(), "StringBuilder"),
                    GeneratedMemberEntry("reverse", "FUNCTION", "fun reverse(): StringBuilder", emptyList(), "StringBuilder"),
                    GeneratedMemberEntry("setLength", "FUNCTION", "fun setLength(newLength: Int): Unit", listOf(GeneratedParamEntry("newLength", "Int")), "Unit"),
                    GeneratedMemberEntry("length", "PROPERTY", "val length: Int", emptyList(), "Int"),
                    GeneratedMemberEntry("capacity", "PROPERTY", "val capacity: Int", emptyList(), "Int"),
                    GeneratedMemberEntry("toString", "FUNCTION", "fun toString(): String", emptyList(), "String"),
                    GeneratedMemberEntry("substring", "FUNCTION", "fun substring(startIndex: Int): String", listOf(GeneratedParamEntry("startIndex", "Int")), "String"),
                    GeneratedMemberEntry("substring", "FUNCTION", "fun substring(startIndex: Int, endIndex: Int): String", listOf(GeneratedParamEntry("startIndex", "Int"), GeneratedParamEntry("endIndex", "Int")), "String")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.text.Appendable",
                kind = "INTERFACE",
                members = listOf(
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: Char): Appendable", listOf(GeneratedParamEntry("value", "Char")), "Appendable"),
                    GeneratedMemberEntry("append", "FUNCTION", "fun append(value: CharSequence?): Appendable", listOf(GeneratedParamEntry("value", "CharSequence?")), "Appendable")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.ArrayList",
                kind = "CLASS",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.MutableList<E>"),
                members = listOf(
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(index: Int, element: E): Unit", listOf(GeneratedParamEntry("index", "Int"), GeneratedParamEntry("element", "E")), "Unit"),
                    GeneratedMemberEntry("addAll", "FUNCTION", "fun addAll(elements: Collection<E>): Boolean", listOf(GeneratedParamEntry("elements", "Collection<E>")), "Boolean"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("removeAt", "FUNCTION", "fun removeAt(index: Int): E", listOf(GeneratedParamEntry("index", "Int")), "E"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit"),
                    GeneratedMemberEntry("get", "FUNCTION", "fun get(index: Int): E", listOf(GeneratedParamEntry("index", "Int")), "E"),
                    GeneratedMemberEntry("set", "FUNCTION", "fun set(index: Int, element: E): E", listOf(GeneratedParamEntry("index", "Int"), GeneratedParamEntry("element", "E")), "E"),
                    GeneratedMemberEntry("size", "PROPERTY", "val size: Int", emptyList(), "Int"),
                    GeneratedMemberEntry("isEmpty", "FUNCTION", "fun isEmpty(): Boolean", emptyList(), "Boolean"),
                    GeneratedMemberEntry("contains", "FUNCTION", "fun contains(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("indexOf", "FUNCTION", "fun indexOf(element: E): Int", listOf(GeneratedParamEntry("element", "E")), "Int"),
                    GeneratedMemberEntry("lastIndexOf", "FUNCTION", "fun lastIndexOf(element: E): Int", listOf(GeneratedParamEntry("element", "E")), "Int")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.HashMap",
                kind = "CLASS",
                typeParams = listOf("K", "V"),
                supers = listOf("kotlin.collections.MutableMap<K, V>"),
                members = listOf(
                    GeneratedMemberEntry("put", "FUNCTION", "fun put(key: K, value: V): V?", listOf(GeneratedParamEntry("key", "K"), GeneratedParamEntry("value", "V")), "V?"),
                    GeneratedMemberEntry("get", "FUNCTION", "fun get(key: K): V?", listOf(GeneratedParamEntry("key", "K")), "V?"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(key: K): V?", listOf(GeneratedParamEntry("key", "K")), "V?"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit"),
                    GeneratedMemberEntry("containsKey", "FUNCTION", "fun containsKey(key: K): Boolean", listOf(GeneratedParamEntry("key", "K")), "Boolean"),
                    GeneratedMemberEntry("containsValue", "FUNCTION", "fun containsValue(value: V): Boolean", listOf(GeneratedParamEntry("value", "V")), "Boolean"),
                    GeneratedMemberEntry("size", "PROPERTY", "val size: Int", emptyList(), "Int"),
                    GeneratedMemberEntry("isEmpty", "FUNCTION", "fun isEmpty(): Boolean", emptyList(), "Boolean"),
                    GeneratedMemberEntry("keys", "PROPERTY", "val keys: MutableSet<K>", emptyList(), "MutableSet<K>"),
                    GeneratedMemberEntry("values", "PROPERTY", "val values: MutableCollection<V>", emptyList(), "MutableCollection<V>"),
                    GeneratedMemberEntry("entries", "PROPERTY", "val entries: MutableSet<MutableMap.MutableEntry<K, V>>", emptyList(), "MutableSet<MutableMap.MutableEntry<K, V>>")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.HashSet",
                kind = "CLASS",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.MutableSet<E>"),
                members = listOf(
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit"),
                    GeneratedMemberEntry("contains", "FUNCTION", "fun contains(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("size", "PROPERTY", "val size: Int", emptyList(), "Int"),
                    GeneratedMemberEntry("isEmpty", "FUNCTION", "fun isEmpty(): Boolean", emptyList(), "Boolean")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.LinkedHashMap",
                kind = "CLASS",
                typeParams = listOf("K", "V"),
                supers = listOf("kotlin.collections.HashMap<K, V>")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.LinkedHashSet",
                kind = "CLASS",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.HashSet<E>")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableList",
                kind = "INTERFACE",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.List<E>", "kotlin.collections.MutableCollection<E>"),
                members = listOf(
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(index: Int, element: E): Unit", listOf(GeneratedParamEntry("index", "Int"), GeneratedParamEntry("element", "E")), "Unit"),
                    GeneratedMemberEntry("addAll", "FUNCTION", "fun addAll(elements: Collection<E>): Boolean", listOf(GeneratedParamEntry("elements", "Collection<E>")), "Boolean"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("removeAt", "FUNCTION", "fun removeAt(index: Int): E", listOf(GeneratedParamEntry("index", "Int")), "E"),
                    GeneratedMemberEntry("set", "FUNCTION", "fun set(index: Int, element: E): E", listOf(GeneratedParamEntry("index", "Int"), GeneratedParamEntry("element", "E")), "E"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableSet",
                kind = "INTERFACE",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.Set<E>", "kotlin.collections.MutableCollection<E>"),
                members = listOf(
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableMap",
                kind = "INTERFACE",
                typeParams = listOf("K", "V"),
                supers = listOf("kotlin.collections.Map<K, V>"),
                members = listOf(
                    GeneratedMemberEntry("put", "FUNCTION", "fun put(key: K, value: V): V?", listOf(GeneratedParamEntry("key", "K"), GeneratedParamEntry("value", "V")), "V?"),
                    GeneratedMemberEntry("putAll", "FUNCTION", "fun putAll(from: Map<out K, V>): Unit", listOf(GeneratedParamEntry("from", "Map<out K, V>")), "Unit"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(key: K): V?", listOf(GeneratedParamEntry("key", "K")), "V?"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit"),
                    GeneratedMemberEntry("keys", "PROPERTY", "val keys: MutableSet<K>", emptyList(), "MutableSet<K>"),
                    GeneratedMemberEntry("values", "PROPERTY", "val values: MutableCollection<V>", emptyList(), "MutableCollection<V>"),
                    GeneratedMemberEntry("entries", "PROPERTY", "val entries: MutableSet<MutableMap.MutableEntry<K, V>>", emptyList(), "MutableSet<MutableMap.MutableEntry<K, V>>")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableCollection",
                kind = "INTERFACE",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.Collection<E>", "kotlin.collections.MutableIterable<E>"),
                members = listOf(
                    GeneratedMemberEntry("add", "FUNCTION", "fun add(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("addAll", "FUNCTION", "fun addAll(elements: Collection<E>): Boolean", listOf(GeneratedParamEntry("elements", "Collection<E>")), "Boolean"),
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(element: E): Boolean", listOf(GeneratedParamEntry("element", "E")), "Boolean"),
                    GeneratedMemberEntry("removeAll", "FUNCTION", "fun removeAll(elements: Collection<E>): Boolean", listOf(GeneratedParamEntry("elements", "Collection<E>")), "Boolean"),
                    GeneratedMemberEntry("retainAll", "FUNCTION", "fun retainAll(elements: Collection<E>): Boolean", listOf(GeneratedParamEntry("elements", "Collection<E>")), "Boolean"),
                    GeneratedMemberEntry("clear", "FUNCTION", "fun clear(): Unit", emptyList(), "Unit")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableIterable",
                kind = "INTERFACE",
                typeParams = listOf("T"),
                supers = listOf("kotlin.collections.Iterable<T>"),
                members = listOf(
                    GeneratedMemberEntry("iterator", "FUNCTION", "fun iterator(): MutableIterator<T>", emptyList(), "MutableIterator<T>")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.MutableIterator",
                kind = "INTERFACE",
                typeParams = listOf("T"),
                supers = listOf("kotlin.collections.Iterator<T>"),
                members = listOf(
                    GeneratedMemberEntry("remove", "FUNCTION", "fun remove(): Unit", emptyList(), "Unit")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.collections.ArrayDeque",
                kind = "CLASS",
                typeParams = listOf("E"),
                supers = listOf("kotlin.collections.MutableList<E>"),
                members = listOf(
                    GeneratedMemberEntry("addFirst", "FUNCTION", "fun addFirst(element: E): Unit", listOf(GeneratedParamEntry("element", "E")), "Unit"),
                    GeneratedMemberEntry("addLast", "FUNCTION", "fun addLast(element: E): Unit", listOf(GeneratedParamEntry("element", "E")), "Unit"),
                    GeneratedMemberEntry("removeFirst", "FUNCTION", "fun removeFirst(): E", emptyList(), "E"),
                    GeneratedMemberEntry("removeLast", "FUNCTION", "fun removeLast(): E", emptyList(), "E"),
                    GeneratedMemberEntry("first", "FUNCTION", "fun first(): E", emptyList(), "E"),
                    GeneratedMemberEntry("last", "FUNCTION", "fun last(): E", emptyList(), "E"),
                    GeneratedMemberEntry("size", "PROPERTY", "val size: Int", emptyList(), "Int")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Enum",
                kind = "CLASS",
                typeParams = listOf("E"),
                supers = listOf("kotlin.Comparable<E>"),
                members = listOf(
                    GeneratedMemberEntry("name", "PROPERTY", "val name: String", emptyList(), "String"),
                    GeneratedMemberEntry("ordinal", "PROPERTY", "val ordinal: Int", emptyList(), "Int")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Annotation",
                kind = "INTERFACE"
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Cloneable",
                kind = "INTERFACE"
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Nothing",
                kind = "CLASS"
            ),
            GeneratedClassEntry(
                fqName = "kotlin.RuntimeException",
                kind = "CLASS",
                supers = listOf("kotlin.Exception"),
                members = listOf(
                    GeneratedMemberEntry("message", "PROPERTY", "val message: String?", emptyList(), "String?"),
                    GeneratedMemberEntry("cause", "PROPERTY", "val cause: Throwable?", emptyList(), "Throwable?")
                )
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Exception",
                kind = "CLASS",
                supers = listOf("kotlin.Throwable")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.Error",
                kind = "CLASS",
                supers = listOf("kotlin.Throwable")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.IllegalArgumentException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.IllegalStateException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.NullPointerException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.IndexOutOfBoundsException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.NoSuchElementException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.UnsupportedOperationException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.ConcurrentModificationException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.ClassCastException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.ArithmeticException",
                kind = "CLASS",
                supers = listOf("kotlin.RuntimeException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.NumberFormatException",
                kind = "CLASS",
                supers = listOf("kotlin.IllegalArgumentException")
            ),
            GeneratedClassEntry(
                fqName = "kotlin.AssertionError",
                kind = "CLASS",
                supers = listOf("kotlin.Error")
            )
        )

        for (classEntry in manualClasses) {
            if (classEntry.fqName !in classes) {
                classes[classEntry.fqName] = classEntry
            }
        }
    }

    private fun processCoreTypes(classes: MutableMap<String, GeneratedClassEntry>) {
        val primitiveTypes = mapOf(
            "kotlin.Int" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Int>"),
            "kotlin.Long" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Long>"),
            "kotlin.Short" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Short>"),
            "kotlin.Byte" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Byte>"),
            "kotlin.Float" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Float>"),
            "kotlin.Double" to listOf("kotlin.Number", "kotlin.Comparable<kotlin.Double>"),
            "kotlin.Char" to listOf("kotlin.Comparable<kotlin.Char>"),
            "kotlin.Boolean" to listOf("kotlin.Comparable<kotlin.Boolean>")
        )

        for ((fqName, supers) in primitiveTypes) {
            val existing = classes[fqName]
            if (existing != null) {
                classes[fqName] = existing.copy(supers = supers)
            }
        }
    }

    private fun processClass(
        kClass: KClass<*>,
        classes: MutableMap<String, GeneratedClassEntry>,
        extensions: MutableMap<String, MutableList<GeneratedIndexEntry>>
    ) {
        val fqName = kClass.qualifiedName ?: return
        if (fqName in processedClasses) return
        if (!fqName.startsWith("kotlin")) return
        processedClasses.add(fqName)

        val classEntry = extractor.extractClass(kClass)
        if (classEntry != null) {
            classes[fqName] = classEntry
        }

        try {
            for (member in kClass.memberExtensionFunctions) {
                try {
                    val entry = extractor.extractExtension(member)
                    if (entry != null) {
                        val receiverType = entry.recv ?: continue
                        extensions.getOrPut(receiverType) { mutableListOf() }.add(entry)
                    }
                } catch (e: Exception) {
                    // Skip problematic extension
                }
            }
        } catch (e: Exception) {
            // Skip if you can't access extensions
        }
    }
}

class ReflectionSymbolExtractor {

    fun extractClass(kClass: KClass<*>): GeneratedClassEntry? {
        val fqName = kClass.qualifiedName ?: return null
        val simpleName = kClass.simpleName ?: return null

        val kind = when {
            kClass.isData -> "DATA_CLASS"
            kClass.isValue -> "VALUE_CLASS"
            kClass.isSealed -> "CLASS"
            kClass.java.isInterface -> "INTERFACE"
            kClass.java.isEnum -> "ENUM_CLASS"
            kClass.java.isAnnotation -> "ANNOTATION_CLASS"
            kClass.objectInstance != null -> "OBJECT"
            else -> "CLASS"
        }

        val typeParams = try {
            kClass.typeParameters.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }

        val supers = try {
            kClass.supertypes
                .mapNotNull { renderType(it) }
                .filter { it != "kotlin.Any" }
        } catch (e: Exception) {
            emptyList()
        }

        val members = mutableListOf<GeneratedMemberEntry>()

        try {
            for (func in kClass.memberFunctions) {
                if (!func.visibility.isPublicApi()) continue
                val member = extractMember(func)
                if (member != null) members.add(member)
            }
        } catch (e: Exception) {
            // Skip members on error
        }

        try {
            for (prop in kClass.memberProperties) {
                if (!prop.visibility.isPublicApi()) continue
                val member = extractProperty(prop)
                if (member != null) members.add(member)
            }
        } catch (e: Exception) {
            // Skip properties on error
        }

        val isDeprecated = kClass.annotations.any { it.annotationClass == Deprecated::class }
        val deprecationMessage = kClass.annotations
            .filterIsInstance<Deprecated>()
            .firstOrNull()?.message

        return GeneratedClassEntry(
            fqName = fqName,
            kind = kind,
            typeParams = typeParams,
            supers = supers,
            members = members,
            companions = emptyList(),
            nested = emptyList(),
            dep = isDeprecated,
            depMsg = deprecationMessage
        )
    }

    fun extractExtension(func: KFunction<*>): GeneratedIndexEntry? {
        val name = func.name
        val extensionReceiver = func.extensionReceiverParameter ?: return null
        val receiverType = renderType(extensionReceiver.type) ?: return null

        val params = func.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .map { param ->
                GeneratedParamEntry(
                    name = param.name ?: "p${param.index}",
                    type = renderType(param.type) ?: "Any",
                    def = param.isOptional,
                    vararg = param.isVararg
                )
            }

        val returnType = renderType(func.returnType)
        val typeParams = func.typeParameters.map { it.name }

        val packageName = func.javaClass.`package`?.name ?: "kotlin"
        val fqName = "$packageName.$name"

        return GeneratedIndexEntry(
            name = name,
            fqName = fqName,
            kind = "FUNCTION",
            pkg = packageName,
            typeParams = typeParams,
            params = params,
            ret = returnType,
            recv = receiverType
        )
    }

    private fun extractMember(func: KFunction<*>): GeneratedMemberEntry? {
        val name = func.name
        if (name.startsWith("component") && name.length == 10) return null
        if (name in setOf("copy", "equals", "hashCode", "toString")) return null

        val params = func.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .map { param ->
                GeneratedParamEntry(
                    name = param.name ?: "p${param.index}",
                    type = renderType(param.type) ?: "Any",
                    def = param.isOptional,
                    vararg = param.isVararg
                )
            }

        val returnType = renderType(func.returnType)
        val visibility = func.visibility?.name ?: "PUBLIC"

        val isDeprecated = func.annotations.any { it.annotationClass == Deprecated::class }

        val signature = buildString {
            append("fun ")
            if (func.typeParameters.isNotEmpty()) {
                append("<")
                append(func.typeParameters.joinToString { it.name })
                append("> ")
            }
            append(name)
            append("(")
            append(params.joinToString { "${it.name}: ${it.type}" })
            append(")")
            returnType?.let { append(": $it") }
        }

        return GeneratedMemberEntry(
            name = name,
            kind = "FUNCTION",
            sig = signature,
            params = params,
            ret = returnType,
            vis = visibility,
            dep = isDeprecated
        )
    }

    private fun extractProperty(prop: KProperty<*>): GeneratedMemberEntry? {
        val name = prop.name
        val type = renderType(prop.returnType)
        val visibility = prop.visibility?.name ?: "PUBLIC"
        val isDeprecated = prop.annotations.any { it.annotationClass == Deprecated::class }

        val signature = buildString {
            append(if (prop is KMutableProperty<*>) "var " else "val ")
            append(name)
            type?.let { append(": $it") }
        }

        return GeneratedMemberEntry(
            name = name,
            kind = "PROPERTY",
            sig = signature,
            params = emptyList(),
            ret = type,
            vis = visibility,
            dep = isDeprecated
        )
    }

    private fun renderType(type: KType): String? {
        val classifier = type.classifier ?: return null

        val baseName = when (classifier) {
            is KClass<*> -> classifier.qualifiedName ?: classifier.simpleName ?: return null
            is KTypeParameter -> classifier.name
            else -> return null
        }

        val result = buildString {
            append(baseName)

            val args = type.arguments
            if (args.isNotEmpty()) {
                append("<")
                append(args.joinToString { arg ->
                    when (arg.variance) {
                        KVariance.INVARIANT, null ->
                            arg.type?.let { renderType(it) } ?: "*"

                        KVariance.IN ->
                            "in ${arg.type?.let { renderType(it) } ?: "Any?"}"

                        KVariance.OUT ->
                            "out ${arg.type?.let { renderType(it) } ?: "Any?"}"

                    }
                })
                append(">")
            }

            if (type.isMarkedNullable) {
                append("?")
            }
        }

        return result
    }

    private fun KVisibility?.isPublicApi(): Boolean {
        return this == KVisibility.PUBLIC || this == KVisibility.PROTECTED
    }
}

@Serializable
data class GeneratedIndexData(
    val version: String,
    val kotlinVersion: String,
    val generatedAt: Long = System.currentTimeMillis(),
    val classes: Map<String, GeneratedClassEntry> = emptyMap(),
    val topLevelFunctions: List<GeneratedIndexEntry> = emptyList(),
    val topLevelProperties: List<GeneratedIndexEntry> = emptyList(),
    val extensions: Map<String, List<GeneratedIndexEntry>> = emptyMap(),
    val typeAliases: List<GeneratedIndexEntry> = emptyList()
) {
    val totalCount: Int get() {
        var count = classes.size + topLevelFunctions.size + topLevelProperties.size + typeAliases.size
        classes.values.forEach { count += it.members.size }
        extensions.values.forEach { count += it.size }
        return count
    }
}

@Serializable
data class GeneratedClassEntry(
    val fqName: String,
    val kind: String,
    val typeParams: List<String> = emptyList(),
    val supers: List<String> = emptyList(),
    val members: List<GeneratedMemberEntry> = emptyList(),
    val companions: List<GeneratedMemberEntry> = emptyList(),
    val nested: List<String> = emptyList(),
    val dep: Boolean = false,
    val depMsg: String? = null
)

@Serializable
data class GeneratedMemberEntry(
    val name: String,
    val kind: String,
    val sig: String = "",
    val params: List<GeneratedParamEntry> = emptyList(),
    val ret: String? = null,
    val vis: String = "PUBLIC",
    val dep: Boolean = false
)

@Serializable
data class GeneratedIndexEntry(
    val name: String,
    val fqName: String,
    val kind: String,
    val pkg: String,
    val container: String? = null,
    val sig: String = "",
    val vis: String = "PUBLIC",
    val typeParams: List<String> = emptyList(),
    val params: List<GeneratedParamEntry> = emptyList(),
    val ret: String? = null,
    val recv: String? = null,
    val supers: List<String> = emptyList(),
    val file: String? = null,
    val dep: Boolean = false,
    val depMsg: String? = null
)

@Serializable
data class GeneratedParamEntry(
    val name: String,
    val type: String,
    val def: Boolean = false,
    val vararg: Boolean = false
)
