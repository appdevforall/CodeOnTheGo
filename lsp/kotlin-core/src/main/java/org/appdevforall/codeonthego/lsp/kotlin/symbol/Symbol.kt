package org.appdevforall.codeonthego.lsp.kotlin.symbol

import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange

/**
 * Base class for all Kotlin symbols.
 *
 * A symbol represents a named entity in Kotlin source code. Symbols form
 * a hierarchy that mirrors Kotlin's declaration kinds:
 *
 * - [ClassSymbol]: Classes, interfaces, objects, enums
 * - [FunctionSymbol]: Functions and methods
 * - [PropertySymbol]: Properties and variables
 * - [ParameterSymbol]: Function and constructor parameters
 * - [TypeParameterSymbol]: Generic type parameters
 * - [TypeAliasSymbol]: Type aliases
 * - [PackageSymbol]: Package declarations
 *
 * ## Symbol Properties
 *
 * All symbols have:
 * - [name]: The declared name
 * - [location]: Where the symbol is declared
 * - [modifiers]: Access modifiers and other flags
 * - [containingScope]: The scope where the symbol is defined
 *
 * ## Example
 *
 * ```kotlin
 * class Foo {
 *     fun bar(x: Int): String = x.toString()
 * }
 * ```
 *
 * This creates:
 * - ClassSymbol("Foo")
 *   - FunctionSymbol("bar")
 *     - ParameterSymbol("x")
 *
 * @see Scope
 * @see SymbolLocation
 * @see Modifiers
 */
sealed class Symbol {
    /**
     * The name of this symbol as declared in source code.
     */
    abstract val name: String

    /**
     * The source location where this symbol is declared.
     */
    abstract val location: SymbolLocation

    /**
     * The modifiers applied to this symbol.
     */
    abstract val modifiers: Modifiers

    /**
     * The scope containing this symbol.
     */
    abstract val containingScope: Scope?

    /**
     * A unique identifier for this symbol within its context.
     */
    open val id: String get() = name

    /**
     * The fully qualified name of this symbol.
     */
    open val qualifiedName: String
        get() = buildString {
            containingScope?.owner?.qualifiedName?.let {
                append(it)
                append('.')
            }
            append(name)
        }

    /**
     * The visibility of this symbol.
     */
    val visibility: Visibility get() = modifiers.visibility

    /**
     * Whether this symbol is public.
     */
    val isPublic: Boolean get() = modifiers.isPublic

    /**
     * Whether this symbol is private.
     */
    val isPrivate: Boolean get() = modifiers.isPrivate

    /**
     * Whether this symbol is protected.
     */
    val isProtected: Boolean get() = modifiers.isProtected

    /**
     * Whether this symbol is internal.
     */
    val isInternal: Boolean get() = modifiers.isInternal

    /**
     * Whether this symbol is synthetic (no source location).
     */
    val isSynthetic: Boolean get() = location.isSynthetic

    /**
     * Accepts a symbol visitor.
     */
    abstract fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R
}

/**
 * Represents a class, interface, object, or enum declaration.
 *
 * ## Class Kinds
 *
 * - Regular class: `class Foo`
 * - Interface: `interface Foo`
 * - Object: `object Foo`
 * - Companion object: `companion object`
 * - Enum class: `enum class Foo`
 * - Annotation class: `annotation class Foo`
 * - Data class: `data class Foo`
 * - Value class: `value class Foo`
 *
 * @property kind The specific kind of class-like declaration
 * @property typeParameters Generic type parameters
 * @property superTypes Direct supertypes (extended class, implemented interfaces)
 * @property memberScope The scope containing this class's members
 */
data class ClassSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val kind: ClassKind,
    val typeParameters: List<TypeParameterSymbol> = emptyList(),
    val superTypes: List<TypeReference> = emptyList(),
    val memberScope: Scope? = null,
    val primaryConstructor: FunctionSymbol? = null,
    val companionObject: ClassSymbol? = null
) : Symbol() {
    /**
     * Whether this is an interface.
     */
    val isInterface: Boolean get() = kind == ClassKind.INTERFACE

    /**
     * Whether this is an object (including companion objects).
     */
    val isObject: Boolean get() = kind.isObject

    /**
     * Whether this is a companion object.
     */
    val isCompanion: Boolean get() = kind == ClassKind.COMPANION_OBJECT

    /**
     * Whether this is an enum class.
     */
    val isEnum: Boolean get() = kind == ClassKind.ENUM_CLASS

    /**
     * Whether this is a data class.
     */
    val isData: Boolean get() = kind == ClassKind.DATA_CLASS

    /**
     * Whether this is a value class (inline class).
     */
    val isValue: Boolean get() = kind == ClassKind.VALUE_CLASS

    /**
     * Whether this is an annotation class.
     */
    val isAnnotation: Boolean get() = kind == ClassKind.ANNOTATION_CLASS

    /**
     * All member symbols.
     */
    val members: List<Symbol> get() = memberScope?.allSymbols ?: emptyList()

    /**
     * Member functions (including inherited).
     */
    val functions: List<FunctionSymbol> get() = memberScope?.findAll<FunctionSymbol>() ?: emptyList()

    /**
     * Member properties.
     */
    val properties: List<PropertySymbol> get() = memberScope?.findAll<PropertySymbol>() ?: emptyList()

    /**
     * Nested classes.
     */
    val nestedClasses: List<ClassSymbol> get() = memberScope?.findAll<ClassSymbol>() ?: emptyList()

    /**
     * Secondary constructors.
     */
    val secondaryConstructors: List<FunctionSymbol>
        get() = functions.filter { it.isConstructor && it != primaryConstructor }

    /**
     * All constructors (primary + secondary).
     */
    val constructors: List<FunctionSymbol>
        get() = listOfNotNull(primaryConstructor) + secondaryConstructors

    /**
     * Finds a member by name.
     */
    fun findMember(name: String): List<Symbol> = memberScope?.resolveLocal(name) ?: emptyList()

    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitClass(this, data)

    override fun toString(): String = "${kind.name.lowercase()} $qualifiedName"
}

/**
 * Represents a function or method declaration.
 *
 * This includes:
 * - Top-level functions
 * - Member methods
 * - Extension functions
 * - Constructors
 * - Property accessors
 *
 * @property parameters Function parameters
 * @property typeParameters Generic type parameters
 * @property returnType The declared or inferred return type
 * @property receiverType Receiver type for extension functions
 * @property bodyScope The scope containing the function body
 */
data class FunctionSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val parameters: List<ParameterSymbol> = emptyList(),
    val typeParameters: List<TypeParameterSymbol> = emptyList(),
    val returnType: TypeReference? = null,
    val receiverType: TypeReference? = null,
    val bodyScope: Scope? = null,
    val isConstructor: Boolean = false,
    val isPrimaryConstructor: Boolean = false
) : Symbol() {
    /**
     * Whether this is an extension function.
     */
    val isExtension: Boolean get() = receiverType != null

    /**
     * Whether this is a suspend function.
     */
    val isSuspend: Boolean get() = modifiers.isSuspend

    /**
     * Whether this is an inline function.
     */
    val isInline: Boolean get() = modifiers.isInline

    /**
     * Whether this is an operator function.
     */
    val isOperator: Boolean get() = modifiers.isOperator

    /**
     * Whether this is an infix function.
     */
    val isInfix: Boolean get() = modifiers.isInfix

    /**
     * Whether this is a tailrec function.
     */
    val isTailrec: Boolean get() = modifiers.isTailrec

    /**
     * Whether this function overrides another.
     */
    val isOverride: Boolean get() = modifiers.isOverride

    /**
     * Whether this function has a body (not abstract/external).
     */
    val hasBody: Boolean get() = !modifiers.isAbstract && !modifiers.isExternal

    /**
     * The number of parameters.
     */
    val parameterCount: Int get() = parameters.size

    /**
     * Required parameters (no default value).
     */
    val requiredParameters: List<ParameterSymbol>
        get() = parameters.filter { !it.hasDefaultValue }

    /**
     * The number of required parameters.
     */
    val requiredParameterCount: Int get() = requiredParameters.size

    /**
     * Parameters with default values.
     */
    val optionalParameters: List<ParameterSymbol>
        get() = parameters.filter { it.hasDefaultValue }

    /**
     * Finds a parameter by name.
     */
    fun findParameter(name: String): ParameterSymbol? = parameters.find { it.name == name }

    /**
     * Unique signature for overload resolution.
     */
    override val id: String
        get() = buildString {
            append(name)
            append('(')
            parameters.joinTo(this) { it.type?.render() ?: "_" }
            append(')')
        }

    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitFunction(this, data)

    override fun toString(): String = buildString {
        if (isSuspend) append("suspend ")
        append("fun ")
        receiverType?.let { append(it.render()).append('.') }
        append(name)
        if (typeParameters.isNotEmpty()) {
            append('<')
            typeParameters.joinTo(this) { it.name }
            append('>')
        }
        append('(')
        parameters.joinTo(this) { "${it.name}: ${it.type?.render() ?: "?"}" }
        append(')')
        returnType?.let { append(": ").append(it.render()) }
    }
}

/**
 * Represents a property or variable declaration.
 *
 * This includes:
 * - Top-level properties
 * - Member properties
 * - Local variables (val/var)
 * - Extension properties
 *
 * @property type The declared or inferred type
 * @property receiverType Receiver type for extension properties
 * @property getter Custom getter function
 * @property setter Custom setter function
 * @property isVar Whether this is a mutable property (var)
 */
data class PropertySymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val type: TypeReference? = null,
    val receiverType: TypeReference? = null,
    val getter: FunctionSymbol? = null,
    val setter: FunctionSymbol? = null,
    val isVar: Boolean = false,
    val hasInitializer: Boolean = false,
    val isDelegated: Boolean = false
) : Symbol() {
    /**
     * Whether this is a val (immutable).
     */
    val isVal: Boolean get() = !isVar

    /**
     * Whether this is an extension property.
     */
    val isExtension: Boolean get() = receiverType != null

    /**
     * Whether this is a compile-time constant.
     */
    val isConst: Boolean get() = modifiers.isConst

    /**
     * Whether this is a lateinit property.
     */
    val isLateInit: Boolean get() = modifiers.isLateInit

    /**
     * Whether this property overrides another.
     */
    val isOverride: Boolean get() = modifiers.isOverride

    /**
     * Whether this has a custom getter.
     */
    val hasCustomGetter: Boolean get() = getter != null

    /**
     * Whether this has a custom setter.
     */
    val hasCustomSetter: Boolean get() = setter != null

    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitProperty(this, data)

    override fun toString(): String = buildString {
        append(if (isVar) "var " else "val ")
        receiverType?.let { append(it.render()).append('.') }
        append(name)
        type?.let { append(": ").append(it.render()) }
    }
}

/**
 * Represents a function or constructor parameter.
 *
 * @property type The parameter type
 * @property hasDefaultValue Whether the parameter has a default value
 * @property isVararg Whether this is a vararg parameter
 */
data class ParameterSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val type: TypeReference? = null,
    val hasDefaultValue: Boolean = false,
    val isVararg: Boolean = false,
    val isCrossinline: Boolean = false,
    val isNoinline: Boolean = false
) : Symbol() {
    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitParameter(this, data)

    override fun toString(): String = buildString {
        if (isVararg) append("vararg ")
        append(name)
        type?.let { append(": ").append(it.render()) }
    }
}

/**
 * Represents a generic type parameter.
 *
 * @property bounds Upper bounds for this type parameter
 * @property variance Variance modifier (in/out/invariant)
 * @property isReified Whether this is a reified type parameter
 */
data class TypeParameterSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val bounds: List<TypeReference> = emptyList(),
    val variance: Variance = Variance.INVARIANT,
    val isReified: Boolean = false
) : Symbol() {
    /**
     * Whether this type parameter has explicit bounds.
     */
    val hasBounds: Boolean get() = bounds.isNotEmpty()

    /**
     * The effective upper bound (first bound or Any?).
     */
    val effectiveBound: TypeReference?
        get() = bounds.firstOrNull()

    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitTypeParameter(this, data)

    override fun toString(): String = buildString {
        when (variance) {
            Variance.IN -> append("in ")
            Variance.OUT -> append("out ")
            Variance.INVARIANT -> {}
        }
        if (isReified) append("reified ")
        append(name)
        if (bounds.isNotEmpty()) {
            append(" : ")
            bounds.joinTo(this) { it.render() }
        }
    }
}

/**
 * Variance modifier for type parameters.
 */
enum class Variance {
    INVARIANT,
    IN,
    OUT;

    companion object {
        fun fromKeyword(keyword: String): Variance = when (keyword) {
            "in" -> IN
            "out" -> OUT
            else -> INVARIANT
        }
    }
}

/**
 * Represents a type alias declaration.
 *
 * @property typeParameters Generic type parameters
 * @property underlyingType The aliased type
 */
data class TypeAliasSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers,
    override val containingScope: Scope?,
    val typeParameters: List<TypeParameterSymbol> = emptyList(),
    val underlyingType: TypeReference? = null
) : Symbol() {
    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitTypeAlias(this, data)

    override fun toString(): String = buildString {
        append("typealias ")
        append(name)
        if (typeParameters.isNotEmpty()) {
            append('<')
            typeParameters.joinTo(this) { it.name }
            append('>')
        }
        underlyingType?.let { append(" = ").append(it.render()) }
    }
}

/**
 * Represents a package declaration.
 *
 * @property packageName The full package name (e.g., "kotlin.collections")
 */
data class PackageSymbol(
    override val name: String,
    override val location: SymbolLocation,
    override val modifiers: Modifiers = Modifiers.EMPTY,
    override val containingScope: Scope? = null,
    val packageName: String = name
) : Symbol() {
    /**
     * Package name segments.
     */
    val segments: List<String> get() = packageName.split('.')

    override val qualifiedName: String get() = packageName

    override fun <R, D> accept(visitor: SymbolVisitor<R, D>, data: D): R = visitor.visitPackage(this, data)

    override fun toString(): String = "package $packageName"
}

/**
 * A reference to a type (unresolved).
 *
 * Type references are created during parsing before full type resolution.
 * They hold the syntactic representation of a type.
 *
 * @property name The type name (may be qualified)
 * @property typeArguments Type arguments for generic types
 * @property isNullable Whether this is a nullable type (T?)
 * @property range Source range of the type reference
 */
data class FunctionTypeInfo(
    val receiverType: TypeReference?,
    val parameterTypes: List<TypeReference>,
    val returnType: TypeReference
)

data class TypeReference(
    val name: String,
    val typeArguments: List<TypeReference> = emptyList(),
    val isNullable: Boolean = false,
    val range: TextRange = TextRange.EMPTY,
    val functionTypeInfo: FunctionTypeInfo? = null
) {
    val simpleName: String get() = name.substringAfterLast('.')

    val isGeneric: Boolean get() = typeArguments.isNotEmpty()

    val isFunctionType: Boolean get() = functionTypeInfo != null

    fun nullable(): TypeReference = copy(isNullable = true)

    fun nonNullable(): TypeReference = copy(isNullable = false)

    fun render(): String = buildString {
        if (functionTypeInfo != null) {
            if (functionTypeInfo.receiverType != null) {
                append(functionTypeInfo.receiverType.render())
                append(".")
            }
            append("(")
            functionTypeInfo.parameterTypes.joinTo(this) { it.render() }
            append(") -> ")
            append(functionTypeInfo.returnType.render())
        } else {
            append(name)
            if (typeArguments.isNotEmpty()) {
                append('<')
                typeArguments.joinTo(this) { it.render() }
                append('>')
            }
        }
        if (isNullable) append('?')
    }

    override fun toString(): String = render()

    companion object {
        val UNIT = TypeReference("Unit")
        val NOTHING = TypeReference("Nothing")
        val ANY = TypeReference("Any")
        val ANY_NULLABLE = TypeReference("Any", isNullable = true)
        val STRING = TypeReference("String")
        val INT = TypeReference("Int")
        val BOOLEAN = TypeReference("Boolean")

        fun simple(name: String, nullable: Boolean = false): TypeReference {
            return TypeReference(name, isNullable = nullable)
        }

        fun generic(name: String, vararg args: TypeReference): TypeReference {
            return TypeReference(name, args.toList())
        }

        fun functionType(
            receiverType: TypeReference?,
            parameterTypes: List<TypeReference>,
            returnType: TypeReference,
            isNullable: Boolean = false,
            range: TextRange = TextRange.EMPTY
        ): TypeReference {
            return TypeReference(
                name = "Function",
                isNullable = isNullable,
                range = range,
                functionTypeInfo = FunctionTypeInfo(receiverType, parameterTypes, returnType)
            )
        }
    }
}
