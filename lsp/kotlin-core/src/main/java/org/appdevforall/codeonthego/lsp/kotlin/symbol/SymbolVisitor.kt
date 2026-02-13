package org.appdevforall.codeonthego.lsp.kotlin.symbol

/**
 * Visitor pattern interface for traversing symbol hierarchies.
 *
 * Implement this interface to process different kinds of symbols uniformly.
 * The visitor pattern allows adding new operations without modifying symbol classes.
 *
 * ## Type Parameters
 *
 * - [R]: The return type of visit methods
 * - [D]: Additional data passed to visit methods
 *
 * ## Example
 *
 * ```kotlin
 * class SymbolPrinter : SymbolVisitor<Unit, Int> {
 *     override fun visitClass(symbol: ClassSymbol, indent: Int) {
 *         println("  ".repeat(indent) + symbol.name)
 *         symbol.members.forEach { it.accept(this, indent + 1) }
 *     }
 *
 *     override fun visitFunction(symbol: FunctionSymbol, indent: Int) {
 *         println("  ".repeat(indent) + symbol.name + "()")
 *     }
 *     // ... other visit methods
 * }
 *
 * symbol.accept(SymbolPrinter(), 0)
 * ```
 *
 * @see Symbol
 * @see SymbolVisitorBase
 */
interface SymbolVisitor<R, D> {
    fun visitClass(symbol: ClassSymbol, data: D): R
    fun visitFunction(symbol: FunctionSymbol, data: D): R
    fun visitProperty(symbol: PropertySymbol, data: D): R
    fun visitParameter(symbol: ParameterSymbol, data: D): R
    fun visitTypeParameter(symbol: TypeParameterSymbol, data: D): R
    fun visitTypeAlias(symbol: TypeAliasSymbol, data: D): R
    fun visitPackage(symbol: PackageSymbol, data: D): R
}

/**
 * Base implementation of [SymbolVisitor] with default behavior.
 *
 * All visit methods delegate to [visitSymbol] by default.
 * Override specific methods to handle particular symbol types.
 *
 * @see SymbolVisitor
 */
abstract class SymbolVisitorBase<R, D> : SymbolVisitor<R, D> {
    /**
     * Default handler for all symbols.
     * Override this to provide a catch-all implementation.
     */
    abstract fun visitSymbol(symbol: Symbol, data: D): R

    override fun visitClass(symbol: ClassSymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitFunction(symbol: FunctionSymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitProperty(symbol: PropertySymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitParameter(symbol: ParameterSymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitTypeParameter(symbol: TypeParameterSymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitTypeAlias(symbol: TypeAliasSymbol, data: D): R = visitSymbol(symbol, data)
    override fun visitPackage(symbol: PackageSymbol, data: D): R = visitSymbol(symbol, data)
}

/**
 * Visitor that returns Unit and takes no data.
 *
 * Useful for side-effecting operations like printing or collecting.
 */
abstract class SymbolVoidVisitor : SymbolVisitor<Unit, Unit> {
    abstract fun visitSymbol(symbol: Symbol)

    override fun visitClass(symbol: ClassSymbol, data: Unit) = visitSymbol(symbol)
    override fun visitFunction(symbol: FunctionSymbol, data: Unit) = visitSymbol(symbol)
    override fun visitProperty(symbol: PropertySymbol, data: Unit) = visitSymbol(symbol)
    override fun visitParameter(symbol: ParameterSymbol, data: Unit) = visitSymbol(symbol)
    override fun visitTypeParameter(symbol: TypeParameterSymbol, data: Unit) = visitSymbol(symbol)
    override fun visitTypeAlias(symbol: TypeAliasSymbol, data: Unit) = visitSymbol(symbol)
    override fun visitPackage(symbol: PackageSymbol, data: Unit) = visitSymbol(symbol)
}

/**
 * Collects symbols into a list using the visitor pattern.
 */
class SymbolCollector<D>(
    private val predicate: (Symbol, D) -> Boolean = { _, _ -> true }
) : SymbolVisitorBase<List<Symbol>, D>() {
    private val collected = mutableListOf<Symbol>()

    override fun visitSymbol(symbol: Symbol, data: D): List<Symbol> {
        if (predicate(symbol, data)) {
            collected.add(symbol)
        }
        return collected
    }

    fun result(): List<Symbol> = collected.toList()

    companion object {
        fun <D> collectAll(symbols: Iterable<Symbol>, data: D): List<Symbol> {
            val collector = SymbolCollector<D>()
            symbols.forEach { it.accept(collector, data) }
            return collector.result()
        }
    }
}

/**
 * Extension function to visit a symbol without data.
 */
fun <R> Symbol.accept(visitor: SymbolVisitor<R, Unit>): R = accept(visitor, Unit)

/**
 * Extension function to traverse all symbols in a scope.
 */
fun <R, D> Scope.visitAll(visitor: SymbolVisitor<R, D>, data: D): List<R> {
    return allSymbols.map { it.accept(visitor, data) }
}

/**
 * Extension function to traverse symbols matching a predicate.
 */
inline fun Scope.forEachSymbol(action: (Symbol) -> Unit) {
    allSymbols.forEach(action)
}

/**
 * Extension to find symbols of a specific type.
 */
inline fun <reified T : Symbol> Scope.symbolsOfType(): List<T> {
    return allSymbols.filterIsInstance<T>()
}
