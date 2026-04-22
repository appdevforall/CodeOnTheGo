package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class JvmSymbolTest {

    private fun classSymbol(
        name: String = "com/example/Foo",
        containingClass: String = "",
        visibility: JvmVisibility = JvmVisibility.PUBLIC,
        language: JvmSourceLanguage = JvmSourceLanguage.KOTLIN,
    ) = JvmSymbol(
        key = name,
        sourceId = "test.jar",
        name = name,
        shortName = name.substringAfterLast('/').substringAfterLast('$'),
        packageName = name.substringBeforeLast('/').replace('/', '.'),
        kind = JvmSymbolKind.CLASS,
        language = language,
        visibility = visibility,
        data = JvmClassInfo(containingClassName = containingClass),
    )

    private fun funSymbol(
        name: String = "com/example/Foo.bar",
        shortName: String = "bar",
        kind: JvmSymbolKind = JvmSymbolKind.FUNCTION,
        receiverType: String = "",
        containingClass: String = "",
    ) = JvmSymbol(
        key = "$name()",
        sourceId = "test.jar",
        name = name,
        shortName = shortName,
        packageName = "com.example",
        kind = kind,
        language = JvmSourceLanguage.KOTLIN,
        data = JvmFunctionInfo(
            containingClassName = containingClass,
            kotlin = if (receiverType.isNotEmpty()) KotlinFunctionInfo(receiverTypeName = receiverType) else null,
        ),
    )

    @Test
    fun `isTopLevel is true when containingClassName is empty`() {
        assertThat(classSymbol(containingClass = "").isTopLevel).isTrue()
    }

    @Test
    fun `isTopLevel is false when containingClassName is non-empty`() {
        assertThat(classSymbol(containingClass = "com/example/Outer").isTopLevel).isFalse()
    }


    @Test
    fun `isExtension is true for EXTENSION_FUNCTION`() {
        assertThat(funSymbol(kind = JvmSymbolKind.EXTENSION_FUNCTION).isExtension).isTrue()
    }

    @Test
    fun `isExtension is true for EXTENSION_PROPERTY`() {
        val s = JvmSymbol(
            key = "ext", sourceId = "s", name = "ext", shortName = "ext",
            packageName = "p", kind = JvmSymbolKind.EXTENSION_PROPERTY,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFieldInfo(),
        )
        assertThat(s.isExtension).isTrue()
    }

    @Test
    fun `isExtension is false for regular FUNCTION`() {
        assertThat(funSymbol(kind = JvmSymbolKind.FUNCTION).isExtension).isFalse()
    }

    @Test
    fun `isExtension is false for CLASS`() {
        assertThat(classSymbol().isExtension).isFalse()
    }

    @Test
    fun `fqName converts slashes to dots`() {
        assertThat(classSymbol(name = "com/example/Foo").fqName).isEqualTo("com.example.Foo")
    }

    @Test
    fun `fqName converts dollars to dots for nested classes`() {
        assertThat(classSymbol(name = "com/example/Outer\$Inner").fqName)
            .isEqualTo("com.example.Outer.Inner")
    }

    @Test
    fun `containingClassFqName on JvmClassInfo converts correctly`() {
        val info = JvmClassInfo(containingClassName = "com/example/Outer\$Inner")
        assertThat(info.containingClassFqName).isEqualTo("com.example.Outer.Inner")
    }

    @Test
    fun `receiverTypeName extracted from EXTENSION_FUNCTION kotlin metadata`() {
        val s = funSymbol(kind = JvmSymbolKind.EXTENSION_FUNCTION, receiverType = "kotlin/collections/List")
        assertThat(s.receiverTypeName).isEqualTo("kotlin/collections/List")
    }

    @Test
    fun `receiverTypeName is null for non-extension function`() {
        assertThat(funSymbol(kind = JvmSymbolKind.FUNCTION).receiverTypeName).isNull()
    }

    @Test
    fun `receiverTypeName is null when kotlin metadata receiver is empty`() {
        val s = funSymbol(kind = JvmSymbolKind.EXTENSION_FUNCTION, receiverType = "")
        assertThat(s.receiverTypeName).isNull()
    }

    @Test
    fun `receiverTypeName extracted from EXTENSION_PROPERTY field info`() {
        val s = JvmSymbol(
            key = "k", sourceId = "s", name = "n", shortName = "n", packageName = "p",
            kind = JvmSymbolKind.EXTENSION_PROPERTY,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFieldInfo(kotlin = KotlinPropertyInfo(receiverTypeName = "kotlin/String")),
        )
        assertThat(s.receiverTypeName).isEqualTo("kotlin/String")
    }

    @Test
    fun `returnTypeDisplay for function`() {
        val s = JvmSymbol(
            key = "f()", sourceId = "s", name = "f", shortName = "f", packageName = "p",
            kind = JvmSymbolKind.FUNCTION,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(returnTypeDisplayName = "String"),
        )
        assertThat(s.returnTypeDisplay).isEqualTo("String")
    }

    @Test
    fun `returnTypeDisplay for field`() {
        val s = JvmSymbol(
            key = "f", sourceId = "s", name = "f", shortName = "f", packageName = "p",
            kind = JvmSymbolKind.FIELD,
            language = JvmSourceLanguage.JAVA,
            data = JvmFieldInfo(typeDisplayName = "int"),
        )
        assertThat(s.returnTypeDisplay).isEqualTo("int")
    }

    @Test
    fun `returnTypeDisplay is empty for class`() {
        assertThat(classSymbol().returnTypeDisplay).isEmpty()
    }

    @Test
    fun `signatureDisplay for function`() {
        val s = JvmSymbol(
            key = "f()", sourceId = "s", name = "f", shortName = "f", packageName = "p",
            kind = JvmSymbolKind.FUNCTION,
            language = JvmSourceLanguage.KOTLIN,
            data = JvmFunctionInfo(signatureDisplay = "fun f(x: Int): String"),
        )
        assertThat(s.signatureDisplay).isEqualTo("fun f(x: Int): String")
    }

    @Test
    fun `signatureDisplay is empty for class`() {
        assertThat(classSymbol().signatureDisplay).isEmpty()
    }

    @Test
    fun `isCallable is true for callable kinds`() {
        assertThat(JvmSymbolKind.FUNCTION.isCallable).isTrue()
        assertThat(JvmSymbolKind.EXTENSION_FUNCTION.isCallable).isTrue()
        assertThat(JvmSymbolKind.CONSTRUCTOR.isCallable).isTrue()
        assertThat(JvmSymbolKind.PROPERTY.isCallable).isTrue()
        assertThat(JvmSymbolKind.EXTENSION_PROPERTY.isCallable).isTrue()
        assertThat(JvmSymbolKind.FIELD.isCallable).isTrue()
    }

    @Test
    fun `isCallable is false for classifier kinds`() {
        assertThat(JvmSymbolKind.CLASS.isCallable).isFalse()
        assertThat(JvmSymbolKind.INTERFACE.isCallable).isFalse()
        assertThat(JvmSymbolKind.ENUM.isCallable).isFalse()
        assertThat(JvmSymbolKind.OBJECT.isCallable).isFalse()
        assertThat(JvmSymbolKind.TYPE_ALIAS.isCallable).isFalse()
    }

    @Test
    fun `isClassifier is true for classifier kinds`() {
        assertThat(JvmSymbolKind.CLASS.isClassifier).isTrue()
        assertThat(JvmSymbolKind.INTERFACE.isClassifier).isTrue()
        assertThat(JvmSymbolKind.ENUM.isClassifier).isTrue()
        assertThat(JvmSymbolKind.DATA_CLASS.isClassifier).isTrue()
        assertThat(JvmSymbolKind.VALUE_CLASS.isClassifier).isTrue()
        assertThat(JvmSymbolKind.OBJECT.isClassifier).isTrue()
        assertThat(JvmSymbolKind.COMPANION_OBJECT.isClassifier).isTrue()
        assertThat(JvmSymbolKind.SEALED_CLASS.isClassifier).isTrue()
        assertThat(JvmSymbolKind.SEALED_INTERFACE.isClassifier).isTrue()
        assertThat(JvmSymbolKind.ANNOTATION_CLASS.isClassifier).isTrue()
        assertThat(JvmSymbolKind.TYPE_ALIAS.isClassifier).isTrue()
    }

    @Test
    fun `isClassifier is false for callable kinds`() {
        assertThat(JvmSymbolKind.FUNCTION.isClassifier).isFalse()
        assertThat(JvmSymbolKind.PROPERTY.isClassifier).isFalse()
        assertThat(JvmSymbolKind.FIELD.isClassifier).isFalse()
    }

    @Test
    fun `isAccessibleOutsideClass for PUBLIC, PROTECTED, INTERNAL`() {
        assertThat(JvmVisibility.PUBLIC.isAccessibleOutsideClass).isTrue()
        assertThat(JvmVisibility.PROTECTED.isAccessibleOutsideClass).isTrue()
        assertThat(JvmVisibility.INTERNAL.isAccessibleOutsideClass).isTrue()
    }

    @Test
    fun `isAccessibleOutsideClass is false for PRIVATE and PACKAGE_PRIVATE`() {
        assertThat(JvmVisibility.PRIVATE.isAccessibleOutsideClass).isFalse()
        assertThat(JvmVisibility.PACKAGE_PRIVATE.isAccessibleOutsideClass).isFalse()
    }

    @Test
    fun `JvmFunctionInfo returnTypeFqName converts slashes and dollars`() {
        val info = JvmFunctionInfo(returnTypeName = "com/example/Foo\$Bar")
        assertThat(info.returnTypeFqName).isEqualTo("com.example.Foo.Bar")
    }

    @Test
    fun `JvmFieldInfo typeFqName converts slashes and dollars`() {
        val info = JvmFieldInfo(typeName = "java/util/List")
        assertThat(info.typeFqName).isEqualTo("java.util.List")
    }

    @Test
    fun `JvmParameterInfo typeFqName converts slashes and dollars`() {
        val param = JvmParameterInfo(name = "p", typeName = "kotlin/String", typeDisplayName = "String")
        assertThat(param.typeFqName).isEqualTo("kotlin.String")
    }

    @Test
    fun `JvmTypeAliasInfo expandedTypeFqName converts correctly`() {
        val info = JvmTypeAliasInfo(expandedTypeName = "java/util/ArrayList")
        assertThat(info.expandedTypeFqName).isEqualTo("java.util.ArrayList")
    }

    @Test
    fun `KotlinFunctionInfo receiverTypeFqName converts correctly`() {
        val info = KotlinFunctionInfo(receiverTypeName = "kotlin/collections/List")
        assertThat(info.receiverTypeFqName).isEqualTo("kotlin.collections.List")
    }

    @Test
    fun `KotlinPropertyInfo receiverTypeFqName converts correctly`() {
        val info = KotlinPropertyInfo(receiverTypeName = "kotlin/String")
        assertThat(info.receiverTypeFqName).isEqualTo("kotlin.String")
    }
}
