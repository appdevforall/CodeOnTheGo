package org.appdevforall.codeonthego.lsp.kotlin.index

import org.appdevforall.codeonthego.lsp.kotlin.symbol.Visibility
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile
import java.util.zip.ZipFile

class ClasspathIndexer {

    fun index(files: List<File>): ClasspathIndex {
        val index = ClasspathIndex()

        for (file in files) {
            if (!file.exists()) continue

            when {
                file.name.endsWith(".jar") -> indexJar(file, index)
                file.name.endsWith(".aar") -> indexAar(file, index)
                file.isDirectory -> indexDirectory(file, index)
            }
        }

        return index
    }

    fun indexIncremental(files: List<File>, existingIndex: ClasspathIndex): ClasspathIndex {
        for (file in files) {
            if (!file.exists()) continue
            if (existingIndex.hasJar(file.absolutePath)) continue

            when {
                file.name.endsWith(".jar") -> indexJar(file, existingIndex)
                file.name.endsWith(".aar") -> indexAar(file, existingIndex)
                file.isDirectory -> indexDirectory(file, existingIndex)
            }
        }

        return existingIndex
    }

    private fun indexJar(jarFile: File, index: ClasspathIndex) {
        try {
            JarFile(jarFile).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class") && !entry.name.contains('$')) {
                        val className = entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')

                        if (shouldIndex(className)) {
                            jar.getInputStream(entry).use { inputStream ->
                                val symbols = parseClassFile(className, inputStream, jarFile.absolutePath)
                                index.addAll(symbols)
                            }
                        }
                    }
                }
                index.addSourceJar(jarFile.absolutePath)
            }
        } catch (e: Exception) {
            // Silently skip corrupted jars
        }
    }

    private fun indexAar(aarFile: File, index: ClasspathIndex) {
        try {
            ZipFile(aarFile).use { aar ->
                val classesJar = aar.getEntry("classes.jar") ?: return

                val tempFile = File.createTempFile("classes", ".jar")
                tempFile.deleteOnExit()

                aar.getInputStream(classesJar).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                indexJar(tempFile, index)
                index.addSourceJar(aarFile.absolutePath)

                tempFile.delete()
            }
        } catch (e: Exception) {
            // Silently skip corrupted aars
        }
    }

    private fun indexDirectory(directory: File, index: ClasspathIndex) {
        directory.walkTopDown()
            .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
            .forEach { classFile ->
                val relativePath = classFile.relativeTo(directory).path
                val className = relativePath
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')

                if (shouldIndex(className)) {
                    classFile.inputStream().use { inputStream ->
                        val symbols = parseClassFile(className, inputStream, directory.absolutePath)
                        index.addAll(symbols)
                    }
                }
            }
        index.addSourceJar(directory.absolutePath)
    }

    private fun shouldIndex(className: String): Boolean {
        if (className.startsWith("java.") || className.startsWith("javax.")) {
            return false
        }
        if (className.contains(".internal.") || className.contains(".impl.")) {
            return false
        }
        if (className.startsWith("sun.") || className.startsWith("com.sun.")) {
            return false
        }
        return true
    }

    private fun parseClassFile(className: String, inputStream: InputStream, sourcePath: String): List<IndexedSymbol> {
        val symbols = mutableListOf<IndexedSymbol>()

        try {
            val classInfo = ClassFileReader.read(inputStream)

            val packageName = className.substringBeforeLast('.', "")
            val simpleName = className.substringAfterLast('.')

            val classSymbol = IndexedSymbol(
                name = simpleName,
                fqName = className,
                kind = classInfo.kind,
                packageName = packageName,
                visibility = classInfo.visibility,
                typeParameters = classInfo.typeParameters,
                superTypes = classInfo.superTypes,
                filePath = sourcePath
            )
            symbols.add(classSymbol)

            for (method in classInfo.methods) {
                if (method.visibility != Visibility.PUBLIC && method.visibility != Visibility.PROTECTED) {
                    continue
                }
                if (method.name.startsWith("access$") || method.name.contains('$')) {
                    continue
                }

                val methodSymbol = IndexedSymbol(
                    name = method.name,
                    fqName = "$className.${method.name}",
                    kind = if (method.name == "<init>") IndexedSymbolKind.CONSTRUCTOR else IndexedSymbolKind.FUNCTION,
                    packageName = packageName,
                    containingClass = className,
                    visibility = method.visibility,
                    parameters = method.parameters,
                    returnType = method.returnType,
                    typeParameters = method.typeParameters,
                    filePath = sourcePath
                )
                symbols.add(methodSymbol)
            }

            for (field in classInfo.fields) {
                if (field.visibility != Visibility.PUBLIC && field.visibility != Visibility.PROTECTED) {
                    continue
                }
                if (field.name.contains('$')) {
                    continue
                }

                val fieldSymbol = IndexedSymbol(
                    name = field.name,
                    fqName = "$className.${field.name}",
                    kind = IndexedSymbolKind.PROPERTY,
                    packageName = packageName,
                    containingClass = className,
                    visibility = field.visibility,
                    returnType = field.type,
                    filePath = sourcePath
                )
                symbols.add(fieldSymbol)
            }

        } catch (e: Exception) {
            val packageName = className.substringBeforeLast('.', "")
            val simpleName = className.substringAfterLast('.')
            symbols.add(IndexedSymbol(
                name = simpleName,
                fqName = className,
                kind = IndexedSymbolKind.CLASS,
                packageName = packageName,
                visibility = Visibility.PUBLIC,
                filePath = sourcePath
            ))
        }

        return symbols
    }
}

internal object ClassFileReader {

    fun read(inputStream: InputStream): ClassInfo {
        val bytes = inputStream.readBytes()
        return parseClass(bytes)
    }

    private fun parseClass(bytes: ByteArray): ClassInfo {
        if (bytes.size < 10) {
            return ClassInfo.EMPTY
        }

        val magic = readU4(bytes, 0)
        if (magic != 0xCAFEBABE.toInt()) {
            return ClassInfo.EMPTY
        }

        val constantPoolCount = readU2(bytes, 8)
        val constantPool = parseConstantPool(bytes, constantPoolCount)

        var offset = 10
        for (i in 1 until constantPoolCount) {
            val tag = bytes[offset].toInt() and 0xFF
            offset += constantPoolEntrySize(tag, bytes, offset)
        }

        val accessFlags = readU2(bytes, offset)
        offset += 2

        val thisClassIndex = readU2(bytes, offset)
        offset += 2

        val superClassIndex = readU2(bytes, offset)
        offset += 2

        val interfacesCount = readU2(bytes, offset)
        offset += 2
        val interfaces = mutableListOf<String>()
        for (i in 0 until interfacesCount) {
            val interfaceIndex = readU2(bytes, offset)
            offset += 2
            constantPool.getClassName(interfaceIndex)?.let { interfaces.add(it) }
        }

        val fieldsCount = readU2(bytes, offset)
        offset += 2
        val fields = mutableListOf<FieldInfo>()
        for (i in 0 until fieldsCount) {
            val fieldInfo = parseField(bytes, offset, constantPool)
            fields.add(fieldInfo.first)
            offset = fieldInfo.second
        }

        val methodsCount = readU2(bytes, offset)
        offset += 2
        val methods = mutableListOf<MethodInfo>()
        for (i in 0 until methodsCount) {
            val methodInfo = parseMethod(bytes, offset, constantPool)
            methods.add(methodInfo.first)
            offset = methodInfo.second
        }

        val superTypes = mutableListOf<String>()
        constantPool.getClassName(superClassIndex)?.let {
            if (it != "java.lang.Object") {
                superTypes.add(it)
            }
        }
        superTypes.addAll(interfaces)

        return ClassInfo(
            kind = parseClassKind(accessFlags),
            visibility = parseVisibility(accessFlags),
            typeParameters = emptyList(),
            superTypes = superTypes,
            methods = methods,
            fields = fields
        )
    }

    private fun parseConstantPool(bytes: ByteArray, count: Int): ConstantPool {
        val pool = ConstantPool(count)
        var offset = 10

        var i = 1
        while (i < count) {
            val tag = bytes[offset].toInt() and 0xFF
            when (tag) {
                CONSTANT_Utf8 -> {
                    val length = readU2(bytes, offset + 1)
                    val value = String(bytes, offset + 3, length, Charsets.UTF_8)
                    pool.setUtf8(i, value)
                    offset += 3 + length
                }
                CONSTANT_Integer, CONSTANT_Float -> {
                    offset += 5
                }
                CONSTANT_Long, CONSTANT_Double -> {
                    offset += 9
                    i++
                }
                CONSTANT_Class -> {
                    val nameIndex = readU2(bytes, offset + 1)
                    pool.setClass(i, nameIndex)
                    offset += 3
                }
                CONSTANT_String -> {
                    offset += 3
                }
                CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref -> {
                    offset += 5
                }
                CONSTANT_NameAndType -> {
                    val nameIndex = readU2(bytes, offset + 1)
                    val descriptorIndex = readU2(bytes, offset + 3)
                    pool.setNameAndType(i, nameIndex, descriptorIndex)
                    offset += 5
                }
                CONSTANT_MethodHandle -> {
                    offset += 4
                }
                CONSTANT_MethodType -> {
                    offset += 3
                }
                CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> {
                    offset += 5
                }
                CONSTANT_Module, CONSTANT_Package -> {
                    offset += 3
                }
                else -> {
                    offset += 3
                }
            }
            i++
        }

        return pool
    }

    private fun constantPoolEntrySize(tag: Int, bytes: ByteArray, offset: Int): Int {
        return when (tag) {
            CONSTANT_Utf8 -> 3 + readU2(bytes, offset + 1)
            CONSTANT_Integer, CONSTANT_Float -> 5
            CONSTANT_Long, CONSTANT_Double -> 9
            CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package -> 3
            CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
            CONSTANT_NameAndType, CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> 5
            CONSTANT_MethodHandle -> 4
            else -> 3
        }
    }

    private fun parseField(bytes: ByteArray, startOffset: Int, pool: ConstantPool): Pair<FieldInfo, Int> {
        var offset = startOffset

        val accessFlags = readU2(bytes, offset)
        offset += 2

        val nameIndex = readU2(bytes, offset)
        offset += 2

        val descriptorIndex = readU2(bytes, offset)
        offset += 2

        val attributesCount = readU2(bytes, offset)
        offset += 2

        for (i in 0 until attributesCount) {
            offset += 2
            val attrLength = readU4(bytes, offset)
            offset += 4 + attrLength
        }

        val name = pool.getUtf8(nameIndex) ?: "unknown"
        val descriptor = pool.getUtf8(descriptorIndex) ?: ""
        val type = parseTypeDescriptor(descriptor)

        return FieldInfo(
            name = name,
            type = type,
            visibility = parseVisibility(accessFlags)
        ) to offset
    }

    private fun parseMethod(bytes: ByteArray, startOffset: Int, pool: ConstantPool): Pair<MethodInfo, Int> {
        var offset = startOffset

        val accessFlags = readU2(bytes, offset)
        offset += 2

        val nameIndex = readU2(bytes, offset)
        offset += 2

        val descriptorIndex = readU2(bytes, offset)
        offset += 2

        val attributesCount = readU2(bytes, offset)
        offset += 2

        for (i in 0 until attributesCount) {
            offset += 2
            val attrLength = readU4(bytes, offset)
            offset += 4 + attrLength
        }

        val name = pool.getUtf8(nameIndex) ?: "unknown"
        val descriptor = pool.getUtf8(descriptorIndex) ?: "()"

        val (params, returnType) = parseMethodDescriptor(descriptor)

        return MethodInfo(
            name = name,
            parameters = params,
            returnType = returnType,
            visibility = parseVisibility(accessFlags),
            typeParameters = emptyList()
        ) to offset
    }

    private fun parseClassKind(accessFlags: Int): IndexedSymbolKind {
        return when {
            (accessFlags and ACC_ANNOTATION) != 0 -> IndexedSymbolKind.ANNOTATION_CLASS
            (accessFlags and ACC_ENUM) != 0 -> IndexedSymbolKind.ENUM_CLASS
            (accessFlags and ACC_INTERFACE) != 0 -> IndexedSymbolKind.INTERFACE
            else -> IndexedSymbolKind.CLASS
        }
    }

    private fun parseVisibility(accessFlags: Int): Visibility {
        return when {
            (accessFlags and ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (accessFlags and ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (accessFlags and ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.INTERNAL
        }
    }

    private fun parseTypeDescriptor(descriptor: String): String {
        if (descriptor.isEmpty()) return "Any"

        return when (descriptor[0]) {
            'B' -> "Byte"
            'C' -> "Char"
            'D' -> "Double"
            'F' -> "Float"
            'I' -> "Int"
            'J' -> "Long"
            'S' -> "Short"
            'Z' -> "Boolean"
            'V' -> "Unit"
            'L' -> {
                val endIndex = descriptor.indexOf(';')
                if (endIndex > 1) {
                    descriptor.substring(1, endIndex).replace('/', '.').replace('$', '.')
                } else "Any"
            }
            '[' -> {
                val componentType = parseTypeDescriptor(descriptor.substring(1))
                "Array<$componentType>"
            }
            else -> "Any"
        }
    }

    private fun parseMethodDescriptor(descriptor: String): Pair<List<IndexedParameter>, String> {
        val params = mutableListOf<IndexedParameter>()

        val paramsEnd = descriptor.indexOf(')')
        if (paramsEnd < 0) return emptyList<IndexedParameter>() to "Unit"

        var i = 1
        var paramIndex = 0
        while (i < paramsEnd) {
            val (type, nextIndex) = parseTypeAtIndex(descriptor, i)
            params.add(IndexedParameter(
                name = "p$paramIndex",
                type = type
            ))
            paramIndex++
            i = nextIndex
        }

        val returnDescriptor = descriptor.substring(paramsEnd + 1)
        val returnType = parseTypeDescriptor(returnDescriptor)

        return params to returnType
    }

    private fun parseTypeAtIndex(descriptor: String, startIndex: Int): Pair<String, Int> {
        if (startIndex >= descriptor.length) return "Any" to startIndex

        return when (descriptor[startIndex]) {
            'B' -> "Byte" to startIndex + 1
            'C' -> "Char" to startIndex + 1
            'D' -> "Double" to startIndex + 1
            'F' -> "Float" to startIndex + 1
            'I' -> "Int" to startIndex + 1
            'J' -> "Long" to startIndex + 1
            'S' -> "Short" to startIndex + 1
            'Z' -> "Boolean" to startIndex + 1
            'V' -> "Unit" to startIndex + 1
            'L' -> {
                val endIndex = descriptor.indexOf(';', startIndex)
                if (endIndex > startIndex) {
                    val className = descriptor.substring(startIndex + 1, endIndex)
                        .replace('/', '.')
                        .replace('$', '.')
                    className to endIndex + 1
                } else {
                    "Any" to descriptor.length
                }
            }
            '[' -> {
                val (componentType, nextIndex) = parseTypeAtIndex(descriptor, startIndex + 1)
                "Array<$componentType>" to nextIndex
            }
            else -> "Any" to startIndex + 1
        }
    }

    private fun readU2(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU4(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private const val CONSTANT_Utf8 = 1
    private const val CONSTANT_Integer = 3
    private const val CONSTANT_Float = 4
    private const val CONSTANT_Long = 5
    private const val CONSTANT_Double = 6
    private const val CONSTANT_Class = 7
    private const val CONSTANT_String = 8
    private const val CONSTANT_Fieldref = 9
    private const val CONSTANT_Methodref = 10
    private const val CONSTANT_InterfaceMethodref = 11
    private const val CONSTANT_NameAndType = 12
    private const val CONSTANT_MethodHandle = 15
    private const val CONSTANT_MethodType = 16
    private const val CONSTANT_Dynamic = 17
    private const val CONSTANT_InvokeDynamic = 18
    private const val CONSTANT_Module = 19
    private const val CONSTANT_Package = 20

    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ENUM = 0x4000
    private const val ACC_ANNOTATION = 0x2000
}

private class ConstantPool(size: Int) {
    private val utf8Entries = arrayOfNulls<String>(size)
    private val classEntries = IntArray(size)
    private val nameAndTypeEntries = Array(size) { intArrayOf(0, 0) }

    fun setUtf8(index: Int, value: String) {
        utf8Entries[index] = value
    }

    fun getUtf8(index: Int): String? {
        return if (index > 0 && index < utf8Entries.size) utf8Entries[index] else null
    }

    fun setClass(index: Int, nameIndex: Int) {
        classEntries[index] = nameIndex
    }

    fun getClassName(index: Int): String? {
        if (index <= 0 || index >= classEntries.size) return null
        val nameIndex = classEntries[index]
        return getUtf8(nameIndex)?.replace('/', '.')
    }

    fun setNameAndType(index: Int, nameIndex: Int, descriptorIndex: Int) {
        nameAndTypeEntries[index] = intArrayOf(nameIndex, descriptorIndex)
    }
}

data class ClassInfo(
    val kind: IndexedSymbolKind,
    val visibility: Visibility,
    val typeParameters: List<String>,
    val superTypes: List<String>,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>
) {
    companion object {
        val EMPTY = ClassInfo(
            kind = IndexedSymbolKind.CLASS,
            visibility = Visibility.PUBLIC,
            typeParameters = emptyList(),
            superTypes = emptyList(),
            methods = emptyList(),
            fields = emptyList()
        )
    }
}

data class MethodInfo(
    val name: String,
    val parameters: List<IndexedParameter>,
    val returnType: String,
    val visibility: Visibility,
    val typeParameters: List<String>
)

data class FieldInfo(
    val name: String,
    val type: String,
    val visibility: Visibility
)
