package org.appdevforall.codeonthego.lsp.kotlin.parser

/**
 * Visitor interface for traversing Kotlin syntax trees.
 *
 * Implements the Visitor design pattern for syntax tree traversal.
 * Each visit method corresponds to a category of syntax nodes.
 *
 * ## Design Philosophy
 *
 * This visitor groups related node types into categories rather than
 * having a method per [SyntaxKind]. This provides:
 * 1. A manageable API (not 200+ methods)
 * 2. Logical groupings that match how you typically process code
 * 3. Flexibility to handle new node types in catch-all methods
 *
 * ## Usage
 *
 * Extend [SyntaxVisitorBase] for a default implementation that visits all children:
 *
 * ```kotlin
 * class FunctionFinder : SyntaxVisitorBase<List<String>>() {
 *     private val functions = mutableListOf<String>()
 *
 *     override fun visitFunctionDeclaration(node: SyntaxNode): List<String> {
 *         val name = node.childByFieldName("name")?.text
 *         if (name != null) functions.add(name)
 *         visitChildren(node)
 *         return functions
 *     }
 *
 *     override fun defaultResult(): List<String> = functions
 * }
 *
 * val finder = FunctionFinder()
 * val names = finder.visit(tree.root)
 * ```
 *
 * ## Traversal Control
 *
 * To control traversal, override visit methods and choose whether to call
 * [visitChildren]. Not calling it skips the subtree.
 *
 * @param R The return type of visit methods
 */
interface SyntaxVisitor<R> {

    /**
     * Main entry point for visiting a node.
     *
     * Dispatches to the appropriate visit method based on node kind.
     *
     * @param node The node to visit
     * @return Result of visiting the node
     */
    fun visit(node: SyntaxNode): R

    /**
     * Visits all children of a node in order.
     *
     * @param node The parent node whose children to visit
     * @return Result after visiting all children
     */
    fun visitChildren(node: SyntaxNode): R

    /**
     * The default result when no specific handling is needed.
     */
    fun defaultResult(): R

    /**
     * Combines results from visiting multiple nodes.
     *
     * @param aggregate The accumulated result so far
     * @param nextResult The result from the latest visit
     * @return Combined result
     */
    fun aggregateResult(aggregate: R, nextResult: R): R

    /**
     * Called for the root source_file node.
     */
    fun visitSourceFile(node: SyntaxNode): R

    /**
     * Called for package declarations.
     */
    fun visitPackageHeader(node: SyntaxNode): R

    /**
     * Called for import statements.
     */
    fun visitImport(node: SyntaxNode): R

    /**
     * Called for class declarations (class, interface, object, enum).
     */
    fun visitClassDeclaration(node: SyntaxNode): R

    /**
     * Called for object declarations.
     */
    fun visitObjectDeclaration(node: SyntaxNode): R

    /**
     * Called for function declarations.
     */
    fun visitFunctionDeclaration(node: SyntaxNode): R

    /**
     * Called for property declarations (val/var).
     */
    fun visitPropertyDeclaration(node: SyntaxNode): R

    /**
     * Called for type alias declarations.
     */
    fun visitTypeAlias(node: SyntaxNode): R

    /**
     * Called for parameters (function, constructor, lambda).
     */
    fun visitParameter(node: SyntaxNode): R

    /**
     * Called for type parameter declarations (<T>).
     */
    fun visitTypeParameter(node: SyntaxNode): R

    /**
     * Called for type references (user types, function types, etc.).
     */
    fun visitType(node: SyntaxNode): R

    /**
     * Called for block statements ({ ... }).
     */
    fun visitBlock(node: SyntaxNode): R

    /**
     * Called for if expressions.
     */
    fun visitIfExpression(node: SyntaxNode): R

    /**
     * Called for when expressions.
     */
    fun visitWhenExpression(node: SyntaxNode): R

    /**
     * Called for try expressions.
     */
    fun visitTryExpression(node: SyntaxNode): R

    /**
     * Called for loop statements (for, while, do-while).
     */
    fun visitLoop(node: SyntaxNode): R

    /**
     * Called for function/method calls.
     */
    fun visitCallExpression(node: SyntaxNode): R

    /**
     * Called for navigation expressions (member access: a.b).
     */
    fun visitNavigationExpression(node: SyntaxNode): R

    /**
     * Called for indexing expressions (a[b]).
     */
    fun visitIndexingExpression(node: SyntaxNode): R

    /**
     * Called for binary expressions (a + b, a == b, etc.).
     */
    fun visitBinaryExpression(node: SyntaxNode): R

    /**
     * Called for unary expressions (prefix and postfix).
     */
    fun visitUnaryExpression(node: SyntaxNode): R

    /**
     * Called for lambda expressions.
     */
    fun visitLambdaExpression(node: SyntaxNode): R

    /**
     * Called for object literals (anonymous objects).
     */
    fun visitObjectLiteral(node: SyntaxNode): R

    /**
     * Called for this/super expressions.
     */
    fun visitThisOrSuperExpression(node: SyntaxNode): R

    /**
     * Called for jump expressions (return, break, continue, throw).
     */
    fun visitJumpExpression(node: SyntaxNode): R

    /**
     * Called for literal values (numbers, strings, booleans, null).
     */
    fun visitLiteral(node: SyntaxNode): R

    /**
     * Called for identifiers (simple_identifier, type_identifier).
     */
    fun visitIdentifier(node: SyntaxNode): R

    /**
     * Called for string templates and interpolations.
     */
    fun visitStringTemplate(node: SyntaxNode): R

    /**
     * Called for annotations.
     */
    fun visitAnnotation(node: SyntaxNode): R

    /**
     * Called for modifiers (visibility, inheritance, etc.).
     */
    fun visitModifier(node: SyntaxNode): R

    /**
     * Called for any node not handled by specific methods.
     */
    fun visitOther(node: SyntaxNode): R
}

/**
 * Base implementation of [SyntaxVisitor] with default behavior.
 *
 * By default, all visit methods call [visitChildren] to continue traversal.
 * Override specific methods to customize handling for those node types.
 *
 * @param R The return type of visit methods
 */
abstract class SyntaxVisitorBase<R> : SyntaxVisitor<R> {

    override fun visit(node: SyntaxNode): R {
        return when (node.kind) {
            SyntaxKind.SOURCE_FILE -> visitSourceFile(node)
            SyntaxKind.PACKAGE_HEADER -> visitPackageHeader(node)

            SyntaxKind.IMPORT_LIST,
            SyntaxKind.IMPORT_HEADER -> visitImport(node)

            SyntaxKind.CLASS_DECLARATION,
            SyntaxKind.INTERFACE_DECLARATION -> visitClassDeclaration(node)

            SyntaxKind.OBJECT_DECLARATION,
            SyntaxKind.COMPANION_OBJECT -> visitObjectDeclaration(node)

            SyntaxKind.FUNCTION_DECLARATION,
            SyntaxKind.ANONYMOUS_FUNCTION,
            SyntaxKind.SECONDARY_CONSTRUCTOR -> visitFunctionDeclaration(node)

            SyntaxKind.PROPERTY_DECLARATION -> visitPropertyDeclaration(node)
            SyntaxKind.TYPE_ALIAS -> visitTypeAlias(node)

            SyntaxKind.PARAMETER,
            SyntaxKind.CLASS_PARAMETER,
            SyntaxKind.PARAMETER_WITH_OPTIONAL_TYPE -> visitParameter(node)

            SyntaxKind.TYPE_PARAMETER -> visitTypeParameter(node)

            SyntaxKind.USER_TYPE,
            SyntaxKind.SIMPLE_USER_TYPE,
            SyntaxKind.NULLABLE_TYPE,
            SyntaxKind.FUNCTION_TYPE,
            SyntaxKind.PARENTHESIZED_TYPE,
            SyntaxKind.DYNAMIC_TYPE,
            SyntaxKind.RECEIVER_TYPE -> visitType(node)

            SyntaxKind.CLASS_BODY,
            SyntaxKind.CONTROL_STRUCTURE_BODY,
            SyntaxKind.STATEMENTS -> visitBlock(node)

            SyntaxKind.IF_EXPRESSION -> visitIfExpression(node)
            SyntaxKind.WHEN_EXPRESSION -> visitWhenExpression(node)
            SyntaxKind.TRY_EXPRESSION -> visitTryExpression(node)

            SyntaxKind.FOR_STATEMENT,
            SyntaxKind.WHILE_STATEMENT,
            SyntaxKind.DO_WHILE_STATEMENT -> visitLoop(node)

            SyntaxKind.CALL_EXPRESSION -> visitCallExpression(node)
            SyntaxKind.NAVIGATION_EXPRESSION -> visitNavigationExpression(node)
            SyntaxKind.INDEXING_EXPRESSION -> visitIndexingExpression(node)

            SyntaxKind.BINARY_EXPRESSION,
            SyntaxKind.INFIX_EXPRESSION,
            SyntaxKind.COMPARISON_EXPRESSION,
            SyntaxKind.EQUALITY_EXPRESSION,
            SyntaxKind.CONJUNCTION_EXPRESSION,
            SyntaxKind.DISJUNCTION_EXPRESSION,
            SyntaxKind.ADDITIVE_EXPRESSION,
            SyntaxKind.MULTIPLICATIVE_EXPRESSION,
            SyntaxKind.RANGE_EXPRESSION,
            SyntaxKind.ELVIS_EXPRESSION,
            SyntaxKind.CHECK_EXPRESSION,
            SyntaxKind.AS_EXPRESSION,
            SyntaxKind.ASSIGNMENT,
            SyntaxKind.AUGMENTED_ASSIGNMENT -> visitBinaryExpression(node)

            SyntaxKind.PREFIX_EXPRESSION,
            SyntaxKind.POSTFIX_EXPRESSION,
            SyntaxKind.SPREAD_EXPRESSION -> visitUnaryExpression(node)

            SyntaxKind.LAMBDA_LITERAL,
            SyntaxKind.ANNOTATED_LAMBDA -> visitLambdaExpression(node)

            SyntaxKind.OBJECT_LITERAL -> visitObjectLiteral(node)

            SyntaxKind.THIS_EXPRESSION,
            SyntaxKind.SUPER_EXPRESSION -> visitThisOrSuperExpression(node)

            SyntaxKind.JUMP_EXPRESSION -> visitJumpExpression(node)

            SyntaxKind.INTEGER_LITERAL,
            SyntaxKind.LONG_LITERAL,
            SyntaxKind.HEX_LITERAL,
            SyntaxKind.BIN_LITERAL,
            SyntaxKind.REAL_LITERAL,
            SyntaxKind.BOOLEAN_LITERAL,
            SyntaxKind.CHARACTER_LITERAL,
            SyntaxKind.NULL_LITERAL -> visitLiteral(node)

            SyntaxKind.SIMPLE_IDENTIFIER,
            SyntaxKind.TYPE_IDENTIFIER,
            SyntaxKind.IDENTIFIER -> visitIdentifier(node)

            SyntaxKind.STRING_LITERAL,
            SyntaxKind.LINE_STRING_LITERAL,
            SyntaxKind.MULTI_LINE_STRING_LITERAL,
            SyntaxKind.INTERPOLATION,
            SyntaxKind.LINE_STRING_EXPRESSION,
            SyntaxKind.MULTI_LINE_STRING_EXPRESSION -> visitStringTemplate(node)

            SyntaxKind.ANNOTATION,
            SyntaxKind.SINGLE_ANNOTATION,
            SyntaxKind.MULTI_ANNOTATION,
            SyntaxKind.FILE_ANNOTATION -> visitAnnotation(node)

            SyntaxKind.MODIFIERS,
            SyntaxKind.MODIFIER,
            SyntaxKind.VISIBILITY_MODIFIER,
            SyntaxKind.INHERITANCE_MODIFIER,
            SyntaxKind.CLASS_MODIFIER,
            SyntaxKind.MEMBER_MODIFIER,
            SyntaxKind.FUNCTION_MODIFIER,
            SyntaxKind.PROPERTY_MODIFIER,
            SyntaxKind.PARAMETER_MODIFIER,
            SyntaxKind.PLATFORM_MODIFIER,
            SyntaxKind.VARIANCE_MODIFIER,
            SyntaxKind.TYPE_PARAMETER_MODIFIER -> visitModifier(node)

            else -> visitOther(node)
        }
    }

    override fun visitChildren(node: SyntaxNode): R {
        var result = defaultResult()
        for (child in node.namedChildren) {
            val childResult = visit(child)
            result = aggregateResult(result, childResult)
        }
        return result
    }

    override fun aggregateResult(aggregate: R, nextResult: R): R = nextResult

    override fun visitSourceFile(node: SyntaxNode): R = visitChildren(node)
    override fun visitPackageHeader(node: SyntaxNode): R = visitChildren(node)
    override fun visitImport(node: SyntaxNode): R = visitChildren(node)
    override fun visitClassDeclaration(node: SyntaxNode): R = visitChildren(node)
    override fun visitObjectDeclaration(node: SyntaxNode): R = visitChildren(node)
    override fun visitFunctionDeclaration(node: SyntaxNode): R = visitChildren(node)
    override fun visitPropertyDeclaration(node: SyntaxNode): R = visitChildren(node)
    override fun visitTypeAlias(node: SyntaxNode): R = visitChildren(node)
    override fun visitParameter(node: SyntaxNode): R = visitChildren(node)
    override fun visitTypeParameter(node: SyntaxNode): R = visitChildren(node)
    override fun visitType(node: SyntaxNode): R = visitChildren(node)
    override fun visitBlock(node: SyntaxNode): R = visitChildren(node)
    override fun visitIfExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitWhenExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitTryExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitLoop(node: SyntaxNode): R = visitChildren(node)
    override fun visitCallExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitNavigationExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitIndexingExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitBinaryExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitUnaryExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitLambdaExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitObjectLiteral(node: SyntaxNode): R = visitChildren(node)
    override fun visitThisOrSuperExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitJumpExpression(node: SyntaxNode): R = visitChildren(node)
    override fun visitLiteral(node: SyntaxNode): R = defaultResult()
    override fun visitIdentifier(node: SyntaxNode): R = defaultResult()
    override fun visitStringTemplate(node: SyntaxNode): R = visitChildren(node)
    override fun visitAnnotation(node: SyntaxNode): R = visitChildren(node)
    override fun visitModifier(node: SyntaxNode): R = defaultResult()
    override fun visitOther(node: SyntaxNode): R = visitChildren(node)
}

/**
 * A visitor that collects nodes matching a predicate.
 *
 * Useful for finding all nodes of a specific type:
 *
 * ```kotlin
 * val collector = NodeCollector { it.kind == SyntaxKind.FUNCTION_DECLARATION }
 * val functions = collector.visit(tree.root)
 * ```
 */
class NodeCollector(
    private val predicate: (SyntaxNode) -> Boolean
) : SyntaxVisitorBase<List<SyntaxNode>>() {

    private val collected = mutableListOf<SyntaxNode>()

    override fun defaultResult(): List<SyntaxNode> = collected

    override fun visit(node: SyntaxNode): List<SyntaxNode> {
        if (predicate(node)) {
            collected.add(node)
        }
        visitChildren(node)
        return collected
    }

    override fun aggregateResult(aggregate: List<SyntaxNode>, nextResult: List<SyntaxNode>): List<SyntaxNode> {
        return aggregate
    }
}

/**
 * A visitor that counts nodes matching a predicate.
 *
 * ```kotlin
 * val counter = NodeCounter { SyntaxKind.isExpression(it.kind) }
 * val expressionCount = counter.visit(tree.root)
 * ```
 */
class NodeCounter(
    private val predicate: (SyntaxNode) -> Boolean
) : SyntaxVisitorBase<Int>() {

    private var count = 0

    override fun defaultResult(): Int = count

    override fun visit(node: SyntaxNode): Int {
        if (predicate(node)) {
            count++
        }
        visitChildren(node)
        return count
    }

    override fun aggregateResult(aggregate: Int, nextResult: Int): Int = aggregate
}

/**
 * A simple visitor that performs an action on each node without returning a value.
 *
 * ```kotlin
 * val printer = NodeWalker { node ->
 *     println("${node.kind} at ${node.range}")
 * }
 * printer.visit(tree.root)
 * ```
 */
class NodeWalker(
    private val action: (SyntaxNode) -> Unit
) : SyntaxVisitorBase<Unit>() {

    override fun defaultResult() = Unit

    override fun visit(node: SyntaxNode) {
        action(node)
        visitChildren(node)
    }

    override fun aggregateResult(aggregate: Unit, nextResult: Unit) = Unit
}
