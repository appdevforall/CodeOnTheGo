package org.appdevforall.codeonthego.lsp.kotlin.symbol

/**
 * Kind of lexical scope in Kotlin.
 *
 * Scopes determine visibility and shadowing rules for symbol resolution.
 * Each scope kind has specific behavior for:
 * - What symbols can be defined
 * - How symbols are resolved
 * - Whether symbols can escape the scope
 *
 * ## Scope Hierarchy
 *
 * Scopes form a parent chain from innermost to outermost:
 * ```
 * FILE -> CLASS -> FUNCTION -> BLOCK -> LAMBDA
 * ```
 *
 * Resolution proceeds from innermost scope outward until a match is found.
 *
 * ## Shadowing Rules
 *
 * - Inner scopes can shadow outer scope symbols
 * - Overloading is allowed (multiple functions with same name)
 * - Properties and functions can share the same name
 */
enum class ScopeKind {
    /**
     * Package/file scope.
     *
     * Contains:
     * - Top-level declarations (classes, functions, properties)
     * - Imported symbols
     *
     * This is the outermost scope for a file.
     */
    FILE,

    /**
     * Package scope (for cross-file resolution).
     *
     * Contains all top-level declarations in a package across all files.
     */
    PACKAGE,

    /**
     * Class/interface/object body scope.
     *
     * Contains:
     * - Member functions
     * - Member properties
     * - Nested classes
     * - Companion object (as a single symbol)
     *
     * Has implicit `this` reference to the containing class instance.
     */
    CLASS,

    /**
     * Enum class body scope.
     *
     * Contains:
     * - Enum entries
     * - Member functions and properties
     */
    ENUM,

    /**
     * Companion object scope.
     *
     * Contains static-like members that can be accessed
     * without an instance of the containing class.
     */
    COMPANION,

    /**
     * Function/method body scope.
     *
     * Contains:
     * - Parameters
     * - Local variables
     * - Local functions
     *
     * Parameters are visible throughout the function body.
     */
    FUNCTION,

    /**
     * Constructor scope.
     *
     * Contains:
     * - Primary constructor parameters (also visible as properties)
     * - Secondary constructor parameters
     */
    CONSTRUCTOR,

    /**
     * Property accessor scope (getter/setter).
     *
     * Contains:
     * - Implicit `value` parameter (setter only)
     * - Implicit `field` reference
     */
    ACCESSOR,

    /**
     * Lambda expression scope.
     *
     * Contains:
     * - Lambda parameters
     * - Implicit `it` parameter (single-param lambdas)
     *
     * Captures symbols from enclosing scopes.
     */
    LAMBDA,

    /**
     * Block scope (if, when, for, while, try bodies).
     *
     * Contains:
     * - Variables declared with val/var
     * - For loop variables
     * - When subject (`when (val x = ...)`)
     *
     * Block-scoped symbols are not visible outside.
     */
    BLOCK,

    /**
     * Catch block scope.
     *
     * Contains:
     * - Exception parameter
     */
    CATCH,

    /**
     * For loop scope.
     *
     * Contains:
     * - Loop variable
     */
    FOR_LOOP,

    /**
     * When entry scope.
     *
     * Contains:
     * - Smart cast information
     * - Destructured variables
     */
    WHEN_ENTRY,

    /**
     * Type parameter scope.
     *
     * Contains:
     * - Type parameters of a generic declaration
     */
    TYPE_PARAMETER;

    /**
     * Whether this scope can contain type declarations.
     */
    val canContainTypes: Boolean
        get() = this == FILE || this == PACKAGE || this == CLASS || this == COMPANION

    /**
     * Whether this scope can contain functions.
     */
    val canContainFunctions: Boolean
        get() = this != TYPE_PARAMETER && this != CATCH && this != FOR_LOOP

    /**
     * Whether this scope can contain properties.
     */
    val canContainProperties: Boolean
        get() = this == FILE || this == CLASS || this == COMPANION

    /**
     * Whether this scope can contain local variables.
     */
    val canContainLocals: Boolean
        get() = this == FUNCTION || this == LAMBDA || this == BLOCK ||
                this == CONSTRUCTOR || this == ACCESSOR || this == CATCH ||
                this == FOR_LOOP || this == WHEN_ENTRY

    /**
     * Whether this is a class-like scope (class, interface, object, enum).
     */
    val isClassScope: Boolean
        get() = this == CLASS || this == ENUM || this == COMPANION

    /**
     * Whether this is a callable scope (function, lambda, constructor).
     */
    val isCallableScope: Boolean
        get() = this == FUNCTION || this == LAMBDA || this == CONSTRUCTOR || this == ACCESSOR
}
