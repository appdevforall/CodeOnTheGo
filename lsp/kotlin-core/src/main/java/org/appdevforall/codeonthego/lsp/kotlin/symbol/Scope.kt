package org.appdevforall.codeonthego.lsp.kotlin.symbol

import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange

/**
 * A lexical scope containing symbol definitions.
 *
 * Scopes form a tree structure mirroring the syntactic nesting of Kotlin code.
 * Each scope contains symbols defined at that level and has a reference to its
 * parent scope for resolution.
 *
 * ## Resolution Algorithm
 *
 * Symbol resolution proceeds from the innermost scope outward:
 * 1. Search the current scope
 * 2. If not found, search the parent scope
 * 3. Continue until found or reaching the root scope
 *
 * ## Overloading
 *
 * Functions can be overloaded (multiple functions with the same name).
 * When resolving a function name, all overloads in the same scope are returned.
 *
 * ## Example
 *
 * ```kotlin
 * // File scope
 * class Foo {           // Class scope (parent = file)
 *     fun bar() {       // Function scope (parent = class)
 *         val x = 1     // Block scope (parent = function)
 *     }
 * }
 * ```
 *
 * @property kind The kind of scope (file, class, function, etc.)
 * @property parent The enclosing scope, or null for the root scope
 * @property owner The symbol that owns this scope (e.g., class symbol for class scope)
 * @property range The source range covered by this scope
 */
class Scope(
    val kind: ScopeKind,
    val parent: Scope? = null,
    owner: Symbol? = null,
    val range: TextRange = TextRange.EMPTY
) {
    var owner: Symbol? = owner
        internal set
    private val symbols: MutableMap<String, MutableList<Symbol>> = mutableMapOf()
    private val childScopes: MutableList<Scope> = mutableListOf()

    /**
     * The depth of this scope in the scope tree.
     * Root scope has depth 0.
     */
    val depth: Int by lazy {
        var d = 0
        var current = parent
        while (current != null) {
            d++
            current = current.parent
        }
        d
    }

    /**
     * All symbols defined directly in this scope.
     */
    val allSymbols: List<Symbol>
        get() = symbols.values.flatten()

    /**
     * All symbol names defined in this scope.
     */
    val symbolNames: Set<String>
        get() = symbols.keys.toSet()

    /**
     * Number of symbols in this scope.
     */
    val symbolCount: Int
        get() = symbols.values.sumOf { it.size }

    /**
     * Whether this scope is empty.
     */
    val isEmpty: Boolean
        get() = symbols.isEmpty()

    /**
     * Child scopes nested within this scope.
     */
    val children: List<Scope>
        get() = childScopes.toList()

    /**
     * Defines a symbol in this scope.
     *
     * Symbols with the same name are added to the overload list only if
     * they can be overloaded (both must be functions). Non-function symbols
     * with duplicate names are silently ignored.
     *
     * @param symbol The symbol to define
     * @return true if the symbol was added, false if it was a non-overloadable duplicate
     */
    fun define(symbol: Symbol): Boolean {
        val existing = symbols[symbol.name]
        if (existing != null) {
            if (!canOverload(existing.first(), symbol)) {
                return false
            }
            existing.add(symbol)
        } else {
            symbols[symbol.name] = mutableListOf(symbol)
        }
        return true
    }

    /**
     * Removes a symbol from this scope.
     *
     * @param symbol The symbol to remove
     * @return true if the symbol was found and removed
     */
    fun undefine(symbol: Symbol): Boolean {
        val list = symbols[symbol.name] ?: return false
        val removed = list.remove(symbol)
        if (list.isEmpty()) {
            symbols.remove(symbol.name)
        }
        return removed
    }

    /**
     * Resolves a name in this scope only, without checking parent scopes.
     *
     * @param name The symbol name to find
     * @return List of symbols with that name, or empty if not found
     */
    fun resolveLocal(name: String): List<Symbol> {
        return symbols[name]?.toList() ?: emptyList()
    }

    /**
     * Resolves a name by searching this scope and all parent scopes.
     *
     * @param name The symbol name to find
     * @return List of symbols with that name from the first scope containing it
     */
    fun resolve(name: String): List<Symbol> {
        val local = resolveLocal(name)
        if (local.isNotEmpty()) return local

        if (kind.isClassScope) {
            (owner as? ClassSymbol)?.memberScope?.resolveLocal(name)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        return parent?.resolve(name) ?: emptyList()
    }

    /**
     * Resolves a name and returns the first match.
     *
     * @param name The symbol name to find
     * @return The first symbol with that name, or null if not found
     */
    fun resolveFirst(name: String): Symbol? = resolve(name).firstOrNull()

    /**
     * Collects all symbols matching a predicate from this scope and parents.
     *
     * @param predicate The condition symbols must match
     * @return All matching symbols in resolution order
     */
    fun collectAll(predicate: (Symbol) -> Boolean): List<Symbol> {
        val result = mutableListOf<Symbol>()
        var current: Scope? = this
        while (current != null) {
            result.addAll(current.allSymbols.filter(predicate))
            if (current.kind.isClassScope) {
                (current.owner as? ClassSymbol)?.memberScope?.allSymbols
                    ?.filter(predicate)
                    ?.let { result.addAll(it) }
            }
            current = current.parent
        }
        return result
    }

    /**
     * Finds all symbols of a specific type in this scope.
     */
    inline fun <reified T : Symbol> findAll(): List<T> {
        return allSymbols.filterIsInstance<T>()
    }

    /**
     * Finds all symbols of a specific type by name.
     */
    inline fun <reified T : Symbol> find(name: String): List<T> {
        return resolve(name).filterIsInstance<T>()
    }

    /**
     * Creates a child scope.
     *
     * @param kind The kind of child scope
     * @param owner The symbol owning the child scope
     * @param range The source range of the child scope
     * @return The new child scope
     */
    fun createChild(
        kind: ScopeKind,
        owner: Symbol? = null,
        range: TextRange = TextRange.EMPTY
    ): Scope {
        val child = Scope(kind, parent = this, owner = owner, range = range)
        childScopes.add(child)
        return child
    }

    /**
     * Gets the sequence of ancestor scopes from this scope to the root.
     */
    fun ancestors(): Sequence<Scope> = sequence {
        var current = parent
        while (current != null) {
            yield(current)
            current = current.parent
        }
    }

    /**
     * Gets the root (file) scope.
     */
    fun root(): Scope {
        var current = this
        while (current.parent != null) {
            current = current.parent!!
        }
        return current
    }

    /**
     * Finds the nearest enclosing scope of a specific kind.
     */
    fun findEnclosing(kind: ScopeKind): Scope? {
        if (this.kind == kind) return this
        return parent?.findEnclosing(kind)
    }

    /**
     * Finds the nearest enclosing class scope.
     */
    fun findEnclosingClass(): Scope? {
        return ancestors().find { it.kind.isClassScope }
    }

    /**
     * Finds the nearest enclosing callable scope (function, lambda, constructor).
     * Checks the current scope first, then ancestors.
     */
    fun findEnclosingCallable(): Scope? {
        if (this.kind.isCallableScope) return this
        return ancestors().find { it.kind.isCallableScope }
    }

    /**
     * Checks if this scope is nested within another scope.
     */
    fun isNestedIn(other: Scope): Boolean {
        return ancestors().any { it === other }
    }

    /**
     * Gets the enclosing class symbol, if any.
     */
    fun getEnclosingClass(): ClassSymbol? {
        return findEnclosingClass()?.owner as? ClassSymbol
    }

    /**
     * Gets the enclosing function symbol, if any.
     */
    fun getEnclosingFunction(): FunctionSymbol? {
        return findEnclosingCallable()?.owner as? FunctionSymbol
    }

    private fun canOverload(existing: Symbol, new: Symbol): Boolean {
        return existing is FunctionSymbol && new is FunctionSymbol
    }

    override fun toString(): String {
        val ownerName = owner?.name ?: "<anonymous>"
        return "Scope($kind, owner=$ownerName, symbols=$symbolCount)"
    }
}
