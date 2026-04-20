package org.appdevforall.codeonthego.indexing.jvm

import org.appdevforall.codeonthego.indexing.api.Indexable

enum class JvmSymbolKind {
    CLASS, INTERFACE, ENUM, ENUM_ENTRY, ANNOTATION_CLASS,
    OBJECT, COMPANION_OBJECT, DATA_CLASS, VALUE_CLASS,
    SEALED_CLASS, SEALED_INTERFACE,
    FUNCTION, EXTENSION_FUNCTION, CONSTRUCTOR,
    PROPERTY, EXTENSION_PROPERTY, FIELD,
    TYPE_ALIAS;

    val isCallable: Boolean
        get() = this in CALLABLE_KINDS

    val isClassifier: Boolean
        get() = this in CLASSIFIER_KINDS

    val isExtension: Boolean
        get() = this == EXTENSION_FUNCTION || this == EXTENSION_PROPERTY

    companion object {
        val CALLABLE_KINDS = setOf(
            FUNCTION, EXTENSION_FUNCTION, CONSTRUCTOR,
            PROPERTY, EXTENSION_PROPERTY, FIELD,
        )
        val CLASSIFIER_KINDS = setOf(
            CLASS, INTERFACE, ENUM, ANNOTATION_CLASS,
            OBJECT, COMPANION_OBJECT, DATA_CLASS,
            VALUE_CLASS, SEALED_CLASS, SEALED_INTERFACE,
            TYPE_ALIAS,
        )
    }
}

enum class JvmSourceLanguage { JAVA, KOTLIN }

enum class JvmVisibility {
    PUBLIC, PROTECTED, INTERNAL, PRIVATE, PACKAGE_PRIVATE;

    val isAccessibleOutsideClass: Boolean
        get() = this == PUBLIC || this == PROTECTED || this == INTERNAL
}

/**
 * A symbol from a JVM class file (JAR/AAR).
 *
 * Common identity fields live here. Type-specific details live in
 * [data], which is one of:
 * - [JvmClassInfo] for classes, interfaces, enums, objects, etc.
 * - [JvmFunctionInfo] for functions, extension functions, constructors
 * - [JvmFieldInfo] for Java fields and Kotlin properties
 * - [JvmEnumEntryInfo] for enum constants
 * - [JvmTypeAliasInfo] for Kotlin type aliases
 */
data class JvmSymbol(
    override val key: String,
    override val sourceId: String,

    val fqName: String,
    val shortName: String,
    val packageName: String,
    val kind: JvmSymbolKind,
    val language: JvmSourceLanguage,
    val visibility: JvmVisibility = JvmVisibility.PUBLIC,
    val isDeprecated: Boolean = false,

    val data: JvmSymbolInfo,
) : Indexable {

    val isTopLevel: Boolean
        get() = data.containingClassFqName.isEmpty()

    val isExtension: Boolean
        get() = kind.isExtension

    val receiverTypeFqName: String?
        get() = when (val d = data) {
            is JvmFunctionInfo -> d.kotlin?.receiverTypeFqName?.takeIf { it.isNotEmpty() }
            is JvmFieldInfo -> d.kotlin?.receiverTypeFqName?.takeIf { it.isNotEmpty() }
            else -> null
        }

    val containingClassFqName: String
        get() = data.containingClassFqName

    val returnTypeDisplay: String
        get() = when (val d = data) {
            is JvmFunctionInfo -> d.returnTypeDisplay
            is JvmFieldInfo -> d.typeDisplay
            else -> ""
        }

    val signatureDisplay: String
        get() = when (val d = data) {
            is JvmFunctionInfo -> d.signatureDisplay
            else -> ""
        }
}

/**
 * Base for all type-specific symbol data.
 * Every variant provides [containingClassFqName] (empty for top-level).
 */
sealed interface JvmSymbolInfo {
    val containingClassFqName: String
}

data class JvmClassInfo(
    override val containingClassFqName: String = "",
    val supertypeFqNames: List<String> = emptyList(),
    val typeParameters: List<String> = emptyList(),
    val isAbstract: Boolean = false,
    val isFinal: Boolean = false,
    val isInner: Boolean = false,
    val isStatic: Boolean = false,
    val kotlin: KotlinClassInfo? = null,
) : JvmSymbolInfo

data class KotlinClassInfo(
    val isData: Boolean = false,
    val isValue: Boolean = false,
    val isSealed: Boolean = false,
    val isFunInterface: Boolean = false,
    val isExpect: Boolean = false,
    val isActual: Boolean = false,
    val isExternal: Boolean = false,
    val sealedSubclasses: List<String> = emptyList(),
    val companionObjectName: String = "",
)

data class JvmFunctionInfo(
    override val containingClassFqName: String = "",
    val returnTypeFqName: String = "",
    val returnTypeDisplay: String = "",
    val parameterCount: Int = 0,
    val parameters: List<JvmParameterInfo> = emptyList(),
    val signatureDisplay: String = "",
    val typeParameters: List<String> = emptyList(),
    val isStatic: Boolean = false,
    val isAbstract: Boolean = false,
    val isFinal: Boolean = false,
    val kotlin: KotlinFunctionInfo? = null,
) : JvmSymbolInfo

data class JvmParameterInfo(
    val name: String,
    val typeFqName: String,
    val typeDisplay: String,
    val hasDefaultValue: Boolean = false,
    val isCrossinline: Boolean = false,
    val isNoinline: Boolean = false,
    val isVararg: Boolean = false,
)

data class KotlinFunctionInfo(
    val receiverTypeFqName: String = "",
    val receiverTypeDisplay: String = "",
    val isSuspend: Boolean = false,
    val isInline: Boolean = false,
    val isInfix: Boolean = false,
    val isOperator: Boolean = false,
    val isTailrec: Boolean = false,
    val isExternal: Boolean = false,
    val isExpect: Boolean = false,
    val isActual: Boolean = false,
    val isReturnTypeNullable: Boolean = false,
)

data class JvmFieldInfo(
    override val containingClassFqName: String = "",
    val typeFqName: String = "",
    val typeDisplay: String = "",
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
    val constantValue: String = "",
    val kotlin: KotlinPropertyInfo? = null,
) : JvmSymbolInfo

data class KotlinPropertyInfo(
    val receiverTypeFqName: String = "",
    val receiverTypeDisplay: String = "",
    val isConst: Boolean = false,
    val isLateinit: Boolean = false,
    val hasGetter: Boolean = false,
    val hasSetter: Boolean = false,
    val isDelegated: Boolean = false,
    val isExpect: Boolean = false,
    val isActual: Boolean = false,
    val isExternal: Boolean = false,
    val isTypeNullable: Boolean = false,
)

data class JvmEnumEntryInfo(
    override val containingClassFqName: String = "",
    val ordinal: Int = 0,
) : JvmSymbolInfo

data class JvmTypeAliasInfo(
    override val containingClassFqName: String = "",
    val expandedTypeFqName: String = "",
    val expandedTypeDisplay: String = "",
    val typeParameters: List<String> = emptyList(),
) : JvmSymbolInfo
