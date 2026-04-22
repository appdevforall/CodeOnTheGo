package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class JvmSymbolDescriptorTest {

    private fun classSymbol(
        name: String = "com/example/Foo",
        shortName: String = "Foo",
        pkg: String = "com.example",
        sourceId: String = "foo.jar",
        kind: JvmSymbolKind = JvmSymbolKind.CLASS,
        language: JvmSourceLanguage = JvmSourceLanguage.KOTLIN,
        visibility: JvmVisibility = JvmVisibility.PUBLIC,
        kotlin: KotlinClassInfo? = null,
        containingClass: String = "",
    ) = JvmSymbol(
        key = name,
        sourceId = sourceId,
        name = name,
        shortName = shortName,
        packageName = pkg,
        kind = kind,
        language = language,
        visibility = visibility,
        data = JvmClassInfo(containingClassName = containingClass, kotlin = kotlin),
    )

    private fun roundtrip(symbol: JvmSymbol): JvmSymbol =
        JvmSymbolDescriptor.deserialize(JvmSymbolDescriptor.serialize(symbol))

    @Test
    fun `roundtrip preserves all core fields for class`() {
        val symbol = classSymbol(
            name = "com/example/MyClass",
            shortName = "MyClass",
            pkg = "com.example",
            sourceId = "mylib.jar",
            visibility = JvmVisibility.INTERNAL,
            language = JvmSourceLanguage.JAVA,
        )
        val restored = roundtrip(symbol)
        assertThat(restored.name).isEqualTo(symbol.name)
        assertThat(restored.shortName).isEqualTo(symbol.shortName)
        assertThat(restored.packageName).isEqualTo(symbol.packageName)
        assertThat(restored.sourceId).isEqualTo(symbol.sourceId)
        assertThat(restored.kind).isEqualTo(symbol.kind)
        assertThat(restored.language).isEqualTo(symbol.language)
        assertThat(restored.visibility).isEqualTo(symbol.visibility)
    }

    @Test
    fun `roundtrip preserves kotlin class metadata`() {
        val symbol = classSymbol(
            kind = JvmSymbolKind.DATA_CLASS,
            kotlin = KotlinClassInfo(
                isData = true,
                companionObjectName = "Companion",
                sealedSubclasses = listOf("com/example/Foo\$Sub1", "com/example/Foo\$Sub2"),
                isSealed = false,
            ),
        )
        val restored = roundtrip(symbol)
        val kt = (restored.data as JvmClassInfo).kotlin!!
        assertThat(kt.isData).isTrue()
        assertThat(kt.companionObjectName).isEqualTo("Companion")
        assertThat(kt.sealedSubclasses).containsExactly("com/example/Foo\$Sub1", "com/example/Foo\$Sub2")
    }

    @Test
    fun `roundtrip for sealed class with subclasses`() {
        val symbol = classSymbol(
            kind = JvmSymbolKind.SEALED_CLASS,
            kotlin = KotlinClassInfo(
                isSealed = true,
                sealedSubclasses = listOf("A", "B", "C"),
            ),
        )
        val kt = (roundtrip(symbol).data as JvmClassInfo).kotlin!!
        assertThat(kt.isSealed).isTrue()
        assertThat(kt.sealedSubclasses).containsExactly("A", "B", "C")
    }

    @Test
    fun `roundtrip for class without kotlin metadata`() {
        val symbol = classSymbol(language = JvmSourceLanguage.JAVA)
        val restored = roundtrip(symbol)
        assertThat((restored.data as JvmClassInfo).kotlin).isNull()
    }

    @Test
    fun `roundtrip preserves function with parameters`() {
        val params = listOf(
            JvmParameterInfo("x", "java/lang/String", "String"),
            JvmParameterInfo("y", "I", "Int", hasDefaultValue = true),
        )
        val symbol = JvmSymbol(
            key = "com/example/Foo.bar(java/lang/String,I)",
            sourceId = "foo.jar",
            name = "com/example/Foo.bar",
            shortName = "bar",
            packageName = "com.example",
            kind = JvmSymbolKind.FUNCTION,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(
                containingClassName = "com/example/Foo",
                returnTypeName = "kotlin/String",
                returnTypeDisplayName = "String",
                parameterCount = 2,
                parameters = params,
                signatureDisplay = "fun bar(x: String, y: Int = 0): String",
                typeParameters = listOf("T"),
                isStatic = false,
            ),
        )
        val restored = roundtrip(symbol)
        val data = restored.data as JvmFunctionInfo
        assertThat(data.parameters).hasSize(2)
        assertThat(data.parameters[0].name).isEqualTo("x")
        assertThat(data.parameters[0].typeName).isEqualTo("java/lang/String")
        assertThat(data.parameters[1].hasDefaultValue).isTrue()
        assertThat(data.returnTypeDisplayName).isEqualTo("String")
        assertThat(data.signatureDisplay).isEqualTo("fun bar(x: String, y: Int = 0): String")
        assertThat(data.typeParameters).containsExactly("T")
    }

    @Test
    fun `roundtrip preserves kotlin function metadata`() {
        val symbol = JvmSymbol(
            key = "com/example/exFun()",
            sourceId = "foo.jar",
            name = "com/example/exFun",
            shortName = "exFun",
            packageName = "com.example",
            kind = JvmSymbolKind.EXTENSION_FUNCTION,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(
                kotlin = KotlinFunctionInfo(
                    receiverTypeName = "kotlin/collections/List",
                    receiverTypeDisplayName = "List<*>",
                    isSuspend = true,
                    isInline = true,
                    isInfix = false,
                    isOperator = false,
                ),
            ),
        )
        val kt = (roundtrip(symbol).data as JvmFunctionInfo).kotlin!!
        assertThat(kt.receiverTypeName).isEqualTo("kotlin/collections/List")
        assertThat(kt.receiverTypeDisplayName).isEqualTo("List<*>")
        assertThat(kt.isSuspend).isTrue()
        assertThat(kt.isInline).isTrue()
    }

    @Test
    fun `roundtrip preserves vararg parameter flag`() {
        val symbol = JvmSymbol(
            key = "f(I)",
            sourceId = "s",
            name = "f",
            shortName = "f",
            packageName = "p",
            kind = JvmSymbolKind.FUNCTION,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(
                parameters = listOf(
                    JvmParameterInfo("args", "[I", "IntArray", isVararg = true),
                ),
            ),
        )
        val restored = roundtrip(symbol)
        val param = (restored.data as JvmFunctionInfo).parameters.first()
        assertThat(param.isVararg).isTrue()
    }

    @Test
    fun `roundtrip preserves field with kotlin property metadata`() {
        val symbol = JvmSymbol(
            key = "com/example/Foo.count",
            sourceId = "foo.jar",
            name = "com/example/Foo.count",
            shortName = "count",
            packageName = "com.example",
            kind = JvmSymbolKind.PROPERTY,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFieldInfo(
                containingClassName = "com/example/Foo",
                typeName = "I",
                typeDisplayName = "Int",
                isFinal = true,
                kotlin = KotlinPropertyInfo(
                    isConst = true,
                    hasGetter = true,
                    hasSetter = false,
                    isTypeNullable = false,
                ),
            ),
        )
        val data = roundtrip(symbol).data as JvmFieldInfo
        assertThat(data.typeDisplayName).isEqualTo("Int")
        assertThat(data.isFinal).isTrue()
        val kotlin = data.kotlin!!
        assertThat(kotlin.isConst).isTrue()
        assertThat(kotlin.hasGetter).isTrue()
        assertThat(kotlin.hasSetter).isFalse()
    }

    @Test
    fun `roundtrip preserves enum entry`() {
        val symbol = JvmSymbol(
            key = "com/example/Status.ACTIVE",
            sourceId = "foo.jar",
            name = "com/example/Status.ACTIVE",
            shortName = "ACTIVE",
            packageName = "com.example",
            kind = JvmSymbolKind.ENUM_ENTRY,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmEnumEntryInfo(containingClassName = "com/example/Status", ordinal = 2),
        )
        val data = roundtrip(symbol).data as JvmEnumEntryInfo
        assertThat(data.ordinal).isEqualTo(2)
        assertThat(data.containingClassName).isEqualTo("com/example/Status")
    }

    @Test
    fun `roundtrip preserves type alias`() {
        val symbol = JvmSymbol(
            key = "com/example/MyList",
            sourceId = "foo.jar",
            name = "com/example/MyList",
            shortName = "MyList",
            packageName = "com.example",
            kind = JvmSymbolKind.TYPE_ALIAS,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmTypeAliasInfo(
                expandedTypeName = "java/util/ArrayList",
                expandedTypeDisplayName = "ArrayList<String>",
                typeParameters = listOf("T"),
            ),
        )
        val data = roundtrip(symbol).data as JvmTypeAliasInfo
        assertThat(data.expandedTypeName).isEqualTo("java/util/ArrayList")
        assertThat(data.expandedTypeDisplayName).isEqualTo("ArrayList<String>")
        assertThat(data.typeParameters).containsExactly("T")
    }

    @Test
    fun `all JvmSymbolKind values roundtrip through serialization`() {
        for (kind in JvmSymbolKind.entries) {
            val data: JvmSymbolInfo = when {
                kind == JvmSymbolKind.ENUM_ENTRY -> JvmEnumEntryInfo()
                kind == JvmSymbolKind.TYPE_ALIAS -> JvmTypeAliasInfo()
                kind == JvmSymbolKind.FIELD
                        || kind == JvmSymbolKind.PROPERTY
                        || kind == JvmSymbolKind.EXTENSION_PROPERTY -> JvmFieldInfo()
                kind.isCallable -> JvmFunctionInfo()
                else -> JvmClassInfo()
            }
            val symbol = JvmSymbol(
                key = "k", sourceId = "s", name = "n", shortName = "n",
                packageName = "p", kind = kind,
                language = JvmSourceLanguage.KOTLIN, data = data,
            )
            assertThat(roundtrip(symbol).kind).isEqualTo(kind)
        }
    }

    @Test
    fun `all JvmVisibility values roundtrip`() {
        for (vis in JvmVisibility.entries) {
            val restored = roundtrip(classSymbol(visibility = vis))
            assertThat(restored.visibility).isEqualTo(vis)
        }
    }

    @Test
    fun `all JvmSourceLanguage values roundtrip`() {
        for (lang in JvmSourceLanguage.entries) {
            val restored = roundtrip(classSymbol(language = lang))
            assertThat(restored.language).isEqualTo(lang)
        }
    }

    @Test
    fun `isDeprecated flag roundtrips`() {
        val s = classSymbol().copy(isDeprecated = true)
        assertThat(roundtrip(s).isDeprecated).isTrue()
    }

    @Test
    fun `fieldValues extracts name shortName package kind language`() {
        val symbol = classSymbol(
            name = "com/example/Foo",
            shortName = "Foo",
            pkg = "com.example",
            kind = JvmSymbolKind.CLASS,
            language = JvmSourceLanguage.KOTLIN,
        )
        val fields = JvmSymbolDescriptor.fieldValues(symbol)
        assertThat(fields[JvmSymbolDescriptor.KEY_NAME]).isEqualTo("Foo")
        assertThat(fields[JvmSymbolDescriptor.KEY_PACKAGE]).isEqualTo("com.example")
        assertThat(fields[JvmSymbolDescriptor.KEY_KIND]).isEqualTo("CLASS")
        assertThat(fields[JvmSymbolDescriptor.KEY_LANGUAGE]).isEqualTo("KOTLIN")
    }

    @Test
    fun `fieldValues containingClass is null for top-level class`() {
        val fields = JvmSymbolDescriptor.fieldValues(classSymbol())
        assertThat(fields[JvmSymbolDescriptor.KEY_CONTAINING_CLASS]).isNull()
    }

    @Test
    fun `fieldValues containingClass is set for nested class`() {
        val symbol = classSymbol(containingClass = "com/example/Outer")
        val fields = JvmSymbolDescriptor.fieldValues(symbol)
        assertThat(fields[JvmSymbolDescriptor.KEY_CONTAINING_CLASS]).isEqualTo("com/example/Outer")
    }

    @Test
    fun `fieldValues receiverType is null for regular function`() {
        val symbol = JvmSymbol(
            key = "f()", sourceId = "s", name = "f", shortName = "f", packageName = "p",
            kind = JvmSymbolKind.FUNCTION, language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(),
        )
        assertThat(JvmSymbolDescriptor.fieldValues(symbol)[JvmSymbolDescriptor.KEY_RECEIVER_TYPE])
            .isNull()
    }

    @Test
    fun `fieldValues receiverType is set for extension function`() {
        val symbol = JvmSymbol(
            key = "f()", sourceId = "s", name = "f", shortName = "f", packageName = "p",
            kind = JvmSymbolKind.EXTENSION_FUNCTION, language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(
                kotlin = KotlinFunctionInfo(receiverTypeName = "kotlin/collections/List"),
            ),
        )
        assertThat(JvmSymbolDescriptor.fieldValues(symbol)[JvmSymbolDescriptor.KEY_RECEIVER_TYPE])
            .isEqualTo("kotlin/collections/List")
    }

    @Test
    fun `descriptor name is jvm_symbols`() {
        assertThat(JvmSymbolDescriptor.name).isEqualTo("jvm_symbols")
    }

    @Test
    fun `descriptor has 6 fields`() {
        assertThat(JvmSymbolDescriptor.fields).hasSize(6)
    }

    @Test
    fun `name field is prefix-searchable`() {
        val nameField = JvmSymbolDescriptor.fields.first { it.name == JvmSymbolDescriptor.KEY_NAME }
        assertThat(nameField.prefixSearchable).isTrue()
    }

    @Test
    fun `other fields are not prefix-searchable`() {
        val otherFields = JvmSymbolDescriptor.fields.filter { it.name != JvmSymbolDescriptor.KEY_NAME }
        assertThat(otherFields.none { it.prefixSearchable }).isTrue()
    }
}
