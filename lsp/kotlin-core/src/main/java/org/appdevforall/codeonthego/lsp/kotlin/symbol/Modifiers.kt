package org.appdevforall.codeonthego.lsp.kotlin.symbol

/**
 * Modality of a class or member declaration.
 *
 * Modality controls inheritance and override behavior:
 *
 * - [FINAL]: Cannot be overridden or subclassed (default for classes and members)
 * - [OPEN]: Can be overridden or subclassed
 * - [ABSTRACT]: Must be overridden, has no implementation
 * - [SEALED]: Can only be subclassed within the same module
 */
enum class Modality {
    FINAL,
    OPEN,
    ABSTRACT,
    SEALED;

    companion object {
        val DEFAULT: Modality = FINAL

        fun fromKeyword(keyword: String): Modality? = when (keyword) {
            "final" -> FINAL
            "open" -> OPEN
            "abstract" -> ABSTRACT
            "sealed" -> SEALED
            else -> null
        }
    }
}

/**
 * Kind of class-like declaration.
 */
enum class ClassKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ENUM_ENTRY,
    ANNOTATION_CLASS,
    DATA_CLASS,
    VALUE_CLASS,
    COMPANION_OBJECT;

    val isObject: Boolean get() = this == OBJECT || this == COMPANION_OBJECT || this == ENUM_ENTRY
    val isClass: Boolean get() = this != INTERFACE
    val isInterface: Boolean get() = this == INTERFACE
}

/**
 * Collection of modifiers that can be applied to Kotlin declarations.
 *
 * This immutable data class captures all modifier information for a declaration:
 * - Visibility ([visibility])
 * - Modality ([modality])
 * - Function modifiers (inline, suspend, operator, etc.)
 * - Property modifiers (const, lateinit)
 * - Class modifiers (data, value, inner, etc.)
 * - Parameter modifiers (vararg, crossinline, noinline)
 * - Platform modifiers (expect, actual)
 *
 * ## Usage
 *
 * ```kotlin
 * val modifiers = Modifiers(
 *     visibility = Visibility.PUBLIC,
 *     modality = Modality.OPEN,
 *     isSuspend = true
 * )
 *
 * if (modifiers.isSuspend) {
 *     // Handle suspend function
 * }
 * ```
 *
 * @see Visibility
 * @see Modality
 */
data class Modifiers(
    val visibility: Visibility = Visibility.DEFAULT,
    val modality: Modality = Modality.DEFAULT,

    val isInline: Boolean = false,
    val isSuspend: Boolean = false,
    val isTailrec: Boolean = false,
    val isOperator: Boolean = false,
    val isInfix: Boolean = false,
    val isExternal: Boolean = false,

    val isConst: Boolean = false,
    val isLateInit: Boolean = false,

    val isData: Boolean = false,
    val isValue: Boolean = false,
    val isInner: Boolean = false,
    val isCompanion: Boolean = false,
    val isAnnotation: Boolean = false,
    val isEnum: Boolean = false,
    val isFunInterface: Boolean = false,

    val isVararg: Boolean = false,
    val isCrossinline: Boolean = false,
    val isNoinline: Boolean = false,
    val isReified: Boolean = false,

    val isOverride: Boolean = false,

    val isExpect: Boolean = false,
    val isActual: Boolean = false
) {
    /**
     * Whether this declaration has explicit visibility.
     */
    val hasExplicitVisibility: Boolean
        get() = visibility != Visibility.DEFAULT

    /**
     * Whether this declaration is abstract (no implementation).
     */
    val isAbstract: Boolean
        get() = modality == Modality.ABSTRACT

    /**
     * Whether this declaration is open for override/subclassing.
     */
    val isOpen: Boolean
        get() = modality == Modality.OPEN || modality == Modality.ABSTRACT

    /**
     * Whether this declaration is final (cannot be overridden).
     */
    val isFinal: Boolean
        get() = modality == Modality.FINAL

    /**
     * Whether this declaration is sealed.
     */
    val isSealed: Boolean
        get() = modality == Modality.SEALED

    /**
     * Whether this is a private declaration.
     */
    val isPrivate: Boolean
        get() = visibility == Visibility.PRIVATE

    /**
     * Whether this is a protected declaration.
     */
    val isProtected: Boolean
        get() = visibility == Visibility.PROTECTED

    /**
     * Whether this is an internal declaration.
     */
    val isInternal: Boolean
        get() = visibility == Visibility.INTERNAL

    /**
     * Whether this is a public declaration.
     */
    val isPublic: Boolean
        get() = visibility == Visibility.PUBLIC

    /**
     * Determines the [ClassKind] based on these modifiers.
     *
     * @param isInterface Whether the declaration is an interface
     * @param isObject Whether the declaration is an object
     */
    fun toClassKind(isInterface: Boolean = false, isObject: Boolean = false): ClassKind {
        return when {
            isInterface && isFunInterface -> ClassKind.INTERFACE
            isInterface -> ClassKind.INTERFACE
            isCompanion -> ClassKind.COMPANION_OBJECT
            isObject -> ClassKind.OBJECT
            isEnum -> ClassKind.ENUM_CLASS
            isAnnotation -> ClassKind.ANNOTATION_CLASS
            isData -> ClassKind.DATA_CLASS
            isValue -> ClassKind.VALUE_CLASS
            else -> ClassKind.CLASS
        }
    }

    /**
     * Creates a copy with updated visibility.
     */
    fun withVisibility(visibility: Visibility): Modifiers = copy(visibility = visibility)

    /**
     * Creates a copy with updated modality.
     */
    fun withModality(modality: Modality): Modifiers = copy(modality = modality)

    /**
     * Merges with another [Modifiers] instance, preferring non-default values from [other].
     */
    fun merge(other: Modifiers): Modifiers = Modifiers(
        visibility = if (other.visibility != Visibility.DEFAULT) other.visibility else visibility,
        modality = if (other.modality != Modality.DEFAULT) other.modality else modality,
        isInline = isInline || other.isInline,
        isSuspend = isSuspend || other.isSuspend,
        isTailrec = isTailrec || other.isTailrec,
        isOperator = isOperator || other.isOperator,
        isInfix = isInfix || other.isInfix,
        isExternal = isExternal || other.isExternal,
        isConst = isConst || other.isConst,
        isLateInit = isLateInit || other.isLateInit,
        isData = isData || other.isData,
        isValue = isValue || other.isValue,
        isInner = isInner || other.isInner,
        isCompanion = isCompanion || other.isCompanion,
        isAnnotation = isAnnotation || other.isAnnotation,
        isEnum = isEnum || other.isEnum,
        isFunInterface = isFunInterface || other.isFunInterface,
        isVararg = isVararg || other.isVararg,
        isCrossinline = isCrossinline || other.isCrossinline,
        isNoinline = isNoinline || other.isNoinline,
        isReified = isReified || other.isReified,
        isOverride = isOverride || other.isOverride,
        isExpect = isExpect || other.isExpect,
        isActual = isActual || other.isActual
    )

    companion object {
        /**
         * Default modifiers (public, final, no special modifiers).
         */
        val EMPTY: Modifiers = Modifiers()

        /**
         * Modifiers for an interface (public, abstract).
         */
        val INTERFACE: Modifiers = Modifiers(modality = Modality.ABSTRACT)

        /**
         * Modifiers for abstract members.
         */
        val ABSTRACT: Modifiers = Modifiers(modality = Modality.ABSTRACT)

        /**
         * Modifiers for open members.
         */
        val OPEN: Modifiers = Modifiers(modality = Modality.OPEN)
    }
}
