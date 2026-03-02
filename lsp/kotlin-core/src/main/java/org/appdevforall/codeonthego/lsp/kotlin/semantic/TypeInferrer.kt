package org.appdevforall.codeonthego.lsp.kotlin.semantic

import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.types.ClassType
import org.appdevforall.codeonthego.lsp.kotlin.types.ErrorType
import org.appdevforall.codeonthego.lsp.kotlin.types.FunctionType
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType
import org.appdevforall.codeonthego.lsp.kotlin.types.PrimitiveKind
import org.appdevforall.codeonthego.lsp.kotlin.types.PrimitiveType
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeChecker
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeParameter
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeSubstitution

/**
 * Infers types for expressions in Kotlin source code.
 *
 * TypeInferrer performs bidirectional type inference:
 * - Bottom-up: Infers types from literals and resolved references
 * - Top-down: Uses expected type context for lambdas and generic inference
 *
 * ## Supported Expressions
 *
 * - Literals: integers, floats, strings, characters, booleans, null
 * - References: variables, properties, parameters
 * - Function calls: with overload resolution
 * - Member access: properties and methods
 * - Operators: arithmetic, comparison, logical
 * - Control flow: if, when, try-catch
 * - Lambdas: with expected type context
 * - Object creation: constructor calls
 * - Type operations: as, is, as?
 *
 * ## Usage
 *
 * ```kotlin
 * val inferrer = TypeInferrer(context)
 * val type = inferrer.inferType(expressionNode, scope)
 * ```
 *
 * @property context The analysis context with shared state
 */
class TypeInferrer(
    private val context: AnalysisContext
) {
    private val typeChecker: TypeChecker = context.typeChecker
    private val symbolResolver: SymbolResolver by lazy { SymbolResolver(context) }
    private val overloadResolver: OverloadResolver by lazy { OverloadResolver(context) }

    /**
     * Infers the type of an expression node.
     *
     * @param node The expression syntax node
     * @param scope The current scope for resolution
     * @param expectedType Optional expected type for contextual inference
     * @return The inferred type, or ErrorType if inference fails
     */
    fun inferType(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType? = null
    ): KotlinType {
        val cached = context.getType(node)
        if (cached != null) {
            return cached
        }

        val inferred = inferTypeImpl(node, scope, expectedType)
        context.recordType(node, inferred)
        return inferred
    }

    private fun inferTypeImpl(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        return when (node.kind) {
            SyntaxKind.INTEGER_LITERAL -> inferIntegerLiteral(node, expectedType)
            SyntaxKind.LONG_LITERAL -> PrimitiveType.LONG
            SyntaxKind.REAL_LITERAL -> inferRealLiteral(node, expectedType)
            SyntaxKind.STRING_LITERAL,
            SyntaxKind.LINE_STRING_LITERAL,
            SyntaxKind.MULTI_LINE_STRING_LITERAL -> ClassType.STRING
            SyntaxKind.CHARACTER_LITERAL -> PrimitiveType.CHAR
            SyntaxKind.BOOLEAN_LITERAL -> PrimitiveType.BOOLEAN
            SyntaxKind.NULL_LITERAL -> inferNullLiteral(expectedType)

            SyntaxKind.SIMPLE_IDENTIFIER -> inferSimpleIdentifier(node, scope)
            SyntaxKind.NAVIGATION_EXPRESSION -> inferNavigationExpression(node, scope)
            SyntaxKind.CALL_EXPRESSION -> inferCallExpression(node, scope, expectedType)
            SyntaxKind.INDEXING_EXPRESSION -> inferIndexingExpression(node, scope)

            SyntaxKind.PARENTHESIZED_EXPRESSION -> {
                val inner = node.namedChildren.firstOrNull()
                if (inner != null) inferType(inner, scope, expectedType)
                else ErrorType.unresolved("Empty parenthesized expression")
            }

            SyntaxKind.PREFIX_EXPRESSION -> inferPrefixExpression(node, scope)
            SyntaxKind.POSTFIX_EXPRESSION -> inferPostfixExpression(node, scope)
            SyntaxKind.ADDITIVE_EXPRESSION,
            SyntaxKind.MULTIPLICATIVE_EXPRESSION -> inferArithmeticExpression(node, scope)
            SyntaxKind.COMPARISON_EXPRESSION -> inferBinaryBooleanExpression(node, scope, "comparison")
            SyntaxKind.EQUALITY_EXPRESSION -> inferBinaryBooleanExpression(node, scope, "equality")
            SyntaxKind.CONJUNCTION_EXPRESSION,
            SyntaxKind.DISJUNCTION_EXPRESSION -> inferBinaryBooleanExpression(node, scope, "logical")
            SyntaxKind.INFIX_EXPRESSION -> inferInfixExpression(node, scope)
            SyntaxKind.ELVIS_EXPRESSION -> inferElvisExpression(node, scope)
            SyntaxKind.RANGE_EXPRESSION -> inferRangeExpression(node, scope)
            SyntaxKind.AS_EXPRESSION -> inferAsExpression(node, scope)
            SyntaxKind.CHECK_EXPRESSION -> PrimitiveType.BOOLEAN
            SyntaxKind.SPREAD_EXPRESSION -> inferSpreadExpression(node, scope)

            SyntaxKind.IF_EXPRESSION -> inferIfExpression(node, scope, expectedType)
            SyntaxKind.WHEN_EXPRESSION -> inferWhenExpression(node, scope, expectedType)
            SyntaxKind.TRY_EXPRESSION -> inferTryExpression(node, scope, expectedType)

            SyntaxKind.LAMBDA_LITERAL -> inferLambdaExpression(node, scope, expectedType)
            SyntaxKind.ANONYMOUS_FUNCTION -> inferAnonymousFunction(node, scope)
            SyntaxKind.OBJECT_LITERAL -> inferObjectLiteral(node, scope)

            SyntaxKind.THIS_EXPRESSION -> inferThisExpression(node, scope)
            SyntaxKind.SUPER_EXPRESSION -> inferSuperExpression(node, scope)
            SyntaxKind.JUMP_EXPRESSION -> inferJumpExpression(node, scope)

            SyntaxKind.COLLECTION_LITERAL -> inferCollectionLiteral(node, scope, expectedType)

            SyntaxKind.ASSIGNMENT -> ClassType.UNIT
            SyntaxKind.AUGMENTED_ASSIGNMENT -> ClassType.UNIT

            else -> {
                val firstChild = node.namedChildren.firstOrNull()
                if (firstChild != null) {
                    inferType(firstChild, scope, expectedType)
                } else {
                    ErrorType.unresolved("Unknown expression: ${node.kind}")
                }
            }
        }
    }

    private fun inferIntegerLiteral(node: SyntaxNode, expectedType: KotlinType?): KotlinType {
        val text = node.text.replace("_", "")

        if (text.endsWith("L", ignoreCase = true)) {
            return PrimitiveType.LONG
        }

        return when (expectedType) {
            PrimitiveType.BYTE -> PrimitiveType.BYTE
            PrimitiveType.SHORT -> PrimitiveType.SHORT
            PrimitiveType.LONG -> PrimitiveType.LONG
            else -> PrimitiveType.INT
        }
    }

    private fun inferRealLiteral(node: SyntaxNode, expectedType: KotlinType?): KotlinType {
        val text = node.text.replace("_", "")

        if (text.endsWith("f", ignoreCase = true)) {
            return PrimitiveType.FLOAT
        }

        return when (expectedType) {
            PrimitiveType.FLOAT -> PrimitiveType.FLOAT
            else -> PrimitiveType.DOUBLE
        }
    }

    private fun inferNullLiteral(expectedType: KotlinType?): KotlinType {
        return expectedType?.nullable() ?: ClassType.NOTHING.nullable()
    }

    private fun inferSimpleIdentifier(node: SyntaxNode, scope: Scope): KotlinType {
        val name = node.text
        val result = symbolResolver.resolveSimpleName(name, scope)

        return when (result) {
            is SymbolResolver.ResolutionResult.Resolved -> {
                context.recordReference(node, result.symbol)
                val baseType = symbolToType(result.symbol, scope)
                context.getSmartCastType(result.symbol) ?: baseType
            }
            is SymbolResolver.ResolutionResult.Unresolved -> {
                context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, node, name)
                ErrorType.unresolved(name)
            }
            is SymbolResolver.ResolutionResult.Ambiguous -> {
                ErrorType.unresolved("Ambiguous: $name")
            }
        }
    }

    private fun inferNavigationExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val receiver = node.childByFieldName("receiver")
            ?: node.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No receiver in navigation")

        val suffix = node.childByFieldName("suffix")
            ?: node.findChild(SyntaxKind.NAVIGATION_SUFFIX)?.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.namedChildren.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER && it != receiver }
            ?: return ErrorType.unresolved("No suffix in navigation")

        val receiverType = inferType(receiver, scope)
        if (receiverType.hasError) {
            return receiverType
        }

        val navigationOperator = node.children.find { it.kind == SyntaxKind.NAVIGATION_SUFFIX }
            ?: node.children.find { it.text == "." || it.text == "?." }
        val isSafeCall = navigationOperator?.text == "?."

        val effectiveReceiverType = if (isSafeCall && receiverType.isNullable) {
            receiverType.nonNullable()
        } else {
            receiverType
        }

        if (!isSafeCall && receiverType.isNullable) {
            context.reportError(DiagnosticCode.UNSAFE_CALL, node, receiverType.render())
        }

        val memberName = suffix.text
        val result = symbolResolver.resolveMemberAccess(effectiveReceiverType, memberName, scope)

        return when (result) {
            is SymbolResolver.ResolutionResult.Resolved -> {
                context.recordReference(suffix, result.symbol)
                val memberType = symbolToType(result.symbol, scope)
                if (isSafeCall && receiverType.isNullable) memberType.nullable() else memberType
            }
            is SymbolResolver.ResolutionResult.Unresolved -> {
                if (!symbolResolver.isExternalLibraryType(effectiveReceiverType)) {
                    context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, suffix, memberName)
                }
                ErrorType.unresolved(memberName)
            }
            is SymbolResolver.ResolutionResult.Ambiguous -> {
                ErrorType.unresolved("Ambiguous: $memberName")
            }
        }
    }

    private fun inferCallExpression(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val callee = node.childByFieldName("function")
            ?: node.namedChildren.firstOrNull {
                it.kind == SyntaxKind.SIMPLE_IDENTIFIER ||
                it.kind == SyntaxKind.NAVIGATION_EXPRESSION
            }
            ?: return ErrorType.unresolved("No callee in call expression")

        val arguments = node.childByFieldName("arguments")
            ?: node.namedChildren.find { it.kind == SyntaxKind.CALL_SUFFIX }

        val argumentTypes = arguments?.namedChildren
            ?.filter { it.kind == SyntaxKind.VALUE_ARGUMENT }
            ?.map { arg ->
                val expr = arg.namedChildren.find { it.kind != SyntaxKind.SIMPLE_IDENTIFIER }
                    ?: arg.namedChildren.firstOrNull()
                if (expr != null) {
                    inferType(expr, scope)
                } else {
                    ErrorType.UNRESOLVED
                }
            }
            ?: emptyList()

        return when (callee.kind) {
            SyntaxKind.SIMPLE_IDENTIFIER -> {
                inferSimpleCallExpression(callee, argumentTypes, scope, expectedType)
            }
            SyntaxKind.NAVIGATION_EXPRESSION -> {
                inferMemberCallExpression(callee, argumentTypes, scope, expectedType)
            }
            else -> {
                val calleeType = inferType(callee, scope)
                if (calleeType is FunctionType) {
                    calleeType.returnType
                } else {
                    ErrorType.unresolved("Cannot call ${callee.text}")
                }
            }
        }
    }

    private fun inferSimpleCallExpression(
        callee: SyntaxNode,
        argumentTypes: List<KotlinType>,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val name = callee.text

        val implicitReceiverType = scope.findEnclosingClass()?.let { classScope ->
            val classSymbol = classScope.owner as? ClassSymbol
            classSymbol?.let { ClassType(it.qualifiedName) }
        }

        var candidates = if (implicitReceiverType != null) {
            symbolResolver.resolveFunctionCall(name, implicitReceiverType, argumentTypes, scope)
        } else {
            emptyList()
        }

        if (candidates.isEmpty()) {
            candidates = symbolResolver.resolveFunctionCall(name, null, argumentTypes, scope)
        }

        if (candidates.isEmpty()) {
            val resolved = symbolResolver.resolveSimpleName(name, scope)
            if (resolved is SymbolResolver.ResolutionResult.Resolved) {
                val symbol = resolved.symbol
                if (symbol is ParameterSymbol && symbol.type != null) {
                    val paramType = context.typeResolver.resolve(symbol.type, scope)
                    if (paramType is FunctionType) {
                        context.recordReference(callee, symbol)
                        return paramType.returnType
                    }
                }
                if (symbol is PropertySymbol && symbol.type != null) {
                    val propType = context.typeResolver.resolve(symbol.type, scope)
                    if (propType is FunctionType) {
                        context.recordReference(callee, symbol)
                        return propType.returnType
                    }
                }
                if (symbol is ClassSymbol) {
                    context.recordReference(callee, symbol)
                    return ClassType(symbol.qualifiedName)
                }
            }

            context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, callee, name)
            return ErrorType.unresolved(name)
        }

        val selected = selectBestOverload(candidates, argumentTypes, expectedType)
        context.recordReference(callee, selected)
        return resolveReturnType(selected, argumentTypes, scope)
    }

    private fun inferMemberCallExpression(
        callee: SyntaxNode,
        argumentTypes: List<KotlinType>,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val receiver = callee.childByFieldName("receiver")
            ?: callee.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No receiver")

        val suffix = callee.childByFieldName("suffix")
            ?: callee.findChild(SyntaxKind.NAVIGATION_SUFFIX)?.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: callee.namedChildren.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER && it != receiver }
            ?: return ErrorType.unresolved("No method name")

        val receiverType = inferType(receiver, scope)
        if (receiverType.hasError) return receiverType

        val methodName = suffix.text
        val candidates = symbolResolver.resolveFunctionCall(methodName, receiverType, argumentTypes, scope)

        if (candidates.isEmpty()) {
            if (!symbolResolver.isExternalLibraryType(receiverType)) {
                context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, suffix, methodName)
            }
            return ErrorType.unresolved(methodName)
        }

        val selected = selectBestOverload(candidates, argumentTypes, expectedType)
        context.recordReference(suffix, selected)
        return resolveReturnType(selected, argumentTypes, scope)
    }

    private fun selectBestOverload(
        candidates: List<FunctionSymbol>,
        argumentTypes: List<KotlinType>,
        expectedType: KotlinType?
    ): FunctionSymbol {
        if (candidates.size == 1) return candidates.first()

        val callArguments = argumentTypes.map { OverloadResolver.CallArgument(it) }
        val result = overloadResolver.resolve(candidates, callArguments, expectedType)

        return when (result) {
            is OverloadResolver.OverloadResolutionResult.Success -> result.selected
            is OverloadResolver.OverloadResolutionResult.Ambiguity -> result.candidates.first()
            is OverloadResolver.OverloadResolutionResult.NoApplicable -> candidates.first()
        }
    }

    private fun resolveReturnType(
        function: FunctionSymbol,
        argumentTypes: List<KotlinType>,
        scope: Scope
    ): KotlinType {
        val returnTypeRef = function.returnType ?: return ClassType.UNIT
        val resolveScope = function.bodyScope ?: function.containingScope ?: scope
        val returnType = context.typeResolver.resolve(returnTypeRef, resolveScope)

        if (function.typeParameters.isEmpty()) {
            return returnType
        }

        val substitution = inferTypeArguments(function, argumentTypes, resolveScope)
        return if (substitution.isEmpty) returnType else returnType.substitute(substitution)
    }

    private fun inferTypeArguments(
        function: FunctionSymbol,
        argumentTypes: List<KotlinType>,
        scope: Scope
    ): TypeSubstitution {
        val typeParams = function.typeParameters
        if (typeParams.isEmpty()) return TypeSubstitution.EMPTY

        val bindings = mutableMapOf<TypeParameter, KotlinType>()

        function.parameters.forEachIndexed { index, param ->
            val argType = argumentTypes.getOrNull(index) ?: return@forEachIndexed
            val paramTypeRef = param.type ?: return@forEachIndexed
            val paramType = context.typeResolver.resolve(paramTypeRef, function.containingScope ?: scope)

            extractTypeBindings(paramType, argType, typeParams, bindings)
        }

        return if (bindings.isEmpty()) TypeSubstitution.EMPTY else TypeSubstitution.of(*bindings.toList().toTypedArray())
    }

    private fun extractTypeBindings(
        paramType: KotlinType,
        argType: KotlinType,
        typeParams: List<TypeParameterSymbol>,
        bindings: MutableMap<TypeParameter, KotlinType>
    ) {
        when (paramType) {
            is TypeParameter -> {
                val matching = typeParams.find { it.name == paramType.name }
                if (matching != null) {
                    bindings[paramType] = argType
                }
            }
            is ClassType -> {
                if (paramType.typeArguments.isNotEmpty() && argType is ClassType && argType.typeArguments.isNotEmpty()) {
                    paramType.typeArguments.zip(argType.typeArguments).forEach { (paramArg, argArg) ->
                        if (paramArg.type != null && argArg.type != null) {
                            extractTypeBindings(paramArg.type, argArg.type, typeParams, bindings)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun inferIndexingExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val receiver = node.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No receiver in indexing")

        val receiverType = inferType(receiver, scope)
        if (receiverType.hasError) return receiverType

        return when {
            receiverType is ClassType && receiverType.fqName == "kotlin.Array" -> {
                receiverType.typeArguments.firstOrNull()?.type ?: ClassType.ANY
            }
            receiverType is ClassType && receiverType.fqName.startsWith("kotlin.collections.") -> {
                when {
                    "List" in receiverType.fqName || "Set" in receiverType.fqName -> {
                        receiverType.typeArguments.firstOrNull()?.type ?: ClassType.ANY
                    }
                    "Map" in receiverType.fqName -> {
                        receiverType.typeArguments.getOrNull(1)?.type?.nullable() ?: ClassType.ANY_NULLABLE
                    }
                    else -> ClassType.ANY
                }
            }
            receiverType is ClassType -> {
                val getResult = symbolResolver.resolveMemberAccess(receiverType, "get", scope)
                if (getResult is SymbolResolver.ResolutionResult.Resolved) {
                    val getFunction = getResult.symbols.filterIsInstance<FunctionSymbol>().firstOrNull()
                    if (getFunction != null) {
                        return resolveReturnType(getFunction, emptyList(), scope)
                    }
                }
                ClassType.ANY
            }
            else -> ClassType.ANY
        }
    }

    private fun inferPrefixExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val operator = node.children.firstOrNull { !it.isNamed }?.text
        val operand = node.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No operand in prefix expression")

        val operandType = inferType(operand, scope)

        return when (operator) {
            "!", "not" -> PrimitiveType.BOOLEAN
            "-", "+" -> operandType
            "++", "--" -> operandType
            else -> operandType
        }
    }

    private fun inferPostfixExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val operand = node.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No operand in postfix expression")

        val operator = node.children.lastOrNull { !it.isNamed }?.text

        val operandType = inferType(operand, scope)

        return when (operator) {
            "!!" -> operandType.nonNullable()
            "++", "--" -> operandType
            else -> operandType
        }
    }

    private fun inferArithmeticExpression(node: SyntaxNode, scope: Scope): KotlinType {
        if (node.hasError || node.children.any { it.isError || it.isMissing }) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete expression '${node.text.trim()}'"
            )
            return ErrorType.unresolved("Invalid arithmetic expression")
        }

        val children = node.namedChildren
        if (children.size < 2) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete expression '${node.text.trim()}'"
            )
            return ErrorType.unresolved("Invalid arithmetic expression")
        }

        if (hasInvalidAdjacentTokens(node)) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Invalid expression '${node.text.trim()}'"
            )
            return ErrorType.unresolved("Invalid expression")
        }

        val leftType = inferType(children.first(), scope)
        val rightType = inferType(children.last(), scope)

        if (leftType is ClassType && leftType.fqName == "kotlin.String") {
            return ClassType.STRING
        }

        return typeChecker.commonSupertype(leftType, rightType)
    }

    private fun hasInvalidAdjacentTokens(node: SyntaxNode): Boolean {
        val children = node.namedChildren
        if (children.size < 2) return false
        for (i in 0 until children.size - 1) {
            val current = children[i]
            val next = children[i + 1]
            if (SyntaxKind.isExpression(current.kind) && SyntaxKind.isExpression(next.kind)) {
                return true
            }
        }
        return false
    }

    private fun inferBinaryBooleanExpression(
        node: SyntaxNode,
        scope: Scope,
        expressionType: String
    ): KotlinType {
        if (node.hasError || node.children.any { it.isError || it.isMissing }) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete $expressionType expression '${node.text.trim()}'"
            )
            return PrimitiveType.BOOLEAN
        }

        val children = node.namedChildren
        if (children.size < 2) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete $expressionType expression '${node.text.trim()}'"
            )
            return PrimitiveType.BOOLEAN
        }
        inferType(children.first(), scope)
        inferType(children.last(), scope)
        return PrimitiveType.BOOLEAN
    }

    private fun inferInfixExpression(node: SyntaxNode, scope: Scope): KotlinType {
        if (node.hasError || node.children.any { it.isError || it.isMissing }) {
            context.reportError(DiagnosticCode.SYNTAX_ERROR, node, "Incomplete expression '${node.text.trim()}'")
            return ErrorType.unresolved("Invalid infix expression")
        }

        val children = node.namedChildren
        if (children.size < 2) {
            context.reportError(DiagnosticCode.SYNTAX_ERROR, node, "Invalid expression '${node.text}'")
            return ErrorType.unresolved("Invalid infix expression")
        }

        if (hasInvalidAdjacentTokens(node)) {
            context.reportError(DiagnosticCode.SYNTAX_ERROR, node, "Invalid expression '${node.text.trim()}'")
            return ErrorType.unresolved("Invalid expression")
        }

        val leftType = inferType(children.first(), scope)
        val operatorNode = node.children.find { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
        val operatorName = operatorNode?.text ?: return ErrorType.unresolved("No infix operator")

        val result = symbolResolver.resolveMemberAccess(leftType, operatorName, scope)
        return when (result) {
            is SymbolResolver.ResolutionResult.Resolved -> {
                val function = result.symbols.filterIsInstance<FunctionSymbol>().firstOrNull()
                if (function != null) {
                    resolveReturnType(function, emptyList(), scope)
                } else {
                    if (!symbolResolver.isExternalLibraryType(leftType)) {
                        context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, operatorNode!!, operatorName)
                    }
                    ErrorType.unresolved("Unresolved infix operator: $operatorName")
                }
            }
            else -> {
                if (!leftType.hasError && !symbolResolver.isExternalLibraryType(leftType)) {
                    context.reportError(DiagnosticCode.UNRESOLVED_REFERENCE, operatorNode, operatorName)
                }
                ErrorType.unresolved("Unresolved infix operator: $operatorName")
            }
        }
    }

    private fun inferElvisExpression(node: SyntaxNode, scope: Scope): KotlinType {
        if (node.hasError || node.children.any { it.isError || it.isMissing }) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete elvis expression '${node.text.trim()}'"
            )
            return ErrorType.unresolved("Invalid elvis expression")
        }

        val children = node.namedChildren
        if (children.size < 2) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete elvis expression '${node.text.trim()}'"
            )
            return ErrorType.unresolved("Invalid elvis expression")
        }

        val leftType = inferType(children.first(), scope)
        val rightType = inferType(children.last(), scope)

        val leftNonNull = leftType.nonNullable()
        return typeChecker.commonSupertype(leftNonNull, rightType)
    }

    private fun inferRangeExpression(node: SyntaxNode, scope: Scope): KotlinType {
        if (node.hasError || node.children.any { it.isError || it.isMissing }) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete range expression '${node.text.trim()}'"
            )
            return ClassType("kotlin.ranges.IntRange")
        }

        val children = node.namedChildren
        if (children.size < 2) {
            context.reportError(
                DiagnosticCode.SYNTAX_ERROR,
                node,
                "Incomplete range expression '${node.text.trim()}'"
            )
            return ClassType("kotlin.ranges.IntRange")
        }

        val elementType = inferType(children.first(), scope)

        return when (elementType) {
            PrimitiveType.INT -> ClassType("kotlin.ranges.IntRange")
            PrimitiveType.LONG -> ClassType("kotlin.ranges.LongRange")
            PrimitiveType.CHAR -> ClassType("kotlin.ranges.CharRange")
            else -> ClassType("kotlin.ranges.ClosedRange").withArguments(elementType)
        }
    }

    private fun inferAsExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val typeNode = node.namedChildren.lastOrNull { it.kind == SyntaxKind.USER_TYPE }
            ?: return ErrorType.unresolved("No target type in as expression")

        val targetTypeRef = parseTypeReference(typeNode)
        val targetType = context.typeResolver.resolve(targetTypeRef, scope)

        val operator = node.children.find { it.text == "as?" }
        return if (operator != null) {
            targetType.nullable()
        } else {
            targetType
        }
    }

    private fun inferSpreadExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val operand = node.namedChildren.firstOrNull()
            ?: return ErrorType.unresolved("No operand in spread expression")

        return inferType(operand, scope)
    }

    private fun inferIfExpression(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val thenBranch = node.childByFieldName("consequence")
            ?: node.namedChildren.getOrNull(1)
        val elseBranch = node.childByFieldName("alternative")
            ?: node.namedChildren.getOrNull(2)

        if (thenBranch == null) {
            return ClassType.UNIT
        }

        val thenType = inferBranchType(thenBranch, scope, expectedType)

        if (elseBranch == null) {
            return ClassType.UNIT
        }

        val elseType = inferBranchType(elseBranch, scope, expectedType)

        return typeChecker.commonSupertype(thenType, elseType)
    }

    private fun inferWhenExpression(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val entries = node.namedChildren.filter { it.kind == SyntaxKind.WHEN_ENTRY }

        if (entries.isEmpty()) {
            return ClassType.UNIT
        }

        val branchTypes = entries.mapNotNull { entry ->
            val body = entry.namedChildren.lastOrNull()
            body?.let { inferBranchType(it, scope, expectedType) }
        }

        if (branchTypes.isEmpty()) {
            return ClassType.UNIT
        }

        return typeChecker.commonSupertype(branchTypes)
    }

    private fun inferTryExpression(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val tryBlock = node.namedChildren.firstOrNull { it.kind == SyntaxKind.STATEMENTS }
            ?: node.namedChildren.firstOrNull()
        val catchClauses = node.namedChildren.filter { it.kind == SyntaxKind.CATCH_BLOCK }
        val finallyClause = node.namedChildren.find { it.kind == SyntaxKind.FINALLY_BLOCK }

        val tryType = tryBlock?.let { inferBranchType(it, scope, expectedType) } ?: ClassType.UNIT

        if (catchClauses.isEmpty() && finallyClause == null) {
            return tryType
        }

        val catchTypes = catchClauses.mapNotNull { catch ->
            val body = catch.namedChildren.lastOrNull()
            body?.let { inferBranchType(it, scope, expectedType) }
        }

        if (catchTypes.isEmpty()) {
            return tryType
        }

        return typeChecker.commonSupertype(listOf(tryType) + catchTypes)
    }

    private fun inferBranchType(
        branch: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        return when (branch.kind) {
            SyntaxKind.STATEMENTS -> {
                val lastStatement = branch.namedChildren.lastOrNull()
                lastStatement?.let { inferType(it, scope, expectedType) } ?: ClassType.UNIT
            }
            SyntaxKind.CONTROL_STRUCTURE_BODY -> {
                val body = branch.namedChildren.firstOrNull()
                body?.let { inferBranchType(it, scope, expectedType) } ?: ClassType.UNIT
            }
            else -> inferType(branch, scope, expectedType)
        }
    }

    private fun inferLambdaExpression(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val parameters = node.namedChildren.find { it.kind == SyntaxKind.LAMBDA_PARAMETERS }
        val body = node.namedChildren.find { it.kind == SyntaxKind.STATEMENTS }
            ?: node.namedChildren.lastOrNull()

        val expectedFunctionType = expectedType as? FunctionType

        val paramTypes = if (parameters != null) {
            parameters.namedChildren.mapIndexed { index, param ->
                val typeAnnotation = param.namedChildren.find { it.kind == SyntaxKind.USER_TYPE }
                if (typeAnnotation != null) {
                    context.typeResolver.resolve(parseTypeReference(typeAnnotation), scope)
                } else {
                    expectedFunctionType?.parameterTypes?.getOrNull(index) ?: ClassType.ANY
                }
            }
        } else if (expectedFunctionType != null && expectedFunctionType.arity == 1) {
            listOf(expectedFunctionType.parameterTypes.first())
        } else {
            emptyList()
        }

        val returnType = if (body != null) {
            inferBranchType(body, scope, expectedFunctionType?.returnType)
        } else {
            ClassType.UNIT
        }

        return FunctionType(
            parameterTypes = paramTypes,
            returnType = returnType,
            receiverType = expectedFunctionType?.receiverType
        )
    }

    private fun inferAnonymousFunction(node: SyntaxNode, scope: Scope): KotlinType {
        val parameters = node.namedChildren.filter { it.kind == SyntaxKind.PARAMETER }
        val returnTypeNode = node.namedChildren.find { it.kind == SyntaxKind.USER_TYPE }

        val paramTypes = parameters.map { param ->
            val typeAnnotation = param.namedChildren.find { it.kind == SyntaxKind.USER_TYPE }
            if (typeAnnotation != null) {
                context.typeResolver.resolve(parseTypeReference(typeAnnotation), scope)
            } else {
                ClassType.ANY
            }
        }

        val returnType = if (returnTypeNode != null) {
            context.typeResolver.resolve(parseTypeReference(returnTypeNode), scope)
        } else {
            ClassType.UNIT
        }

        return FunctionType(parameterTypes = paramTypes, returnType = returnType)
    }

    private fun inferObjectLiteral(node: SyntaxNode, scope: Scope): KotlinType {
        val superTypes = node.namedChildren.find { it.kind == SyntaxKind.DELEGATION_SPECIFIERS }
        val firstSuperType = superTypes?.namedChildren?.firstOrNull()

        if (firstSuperType != null) {
            val typeNode = firstSuperType.namedChildren.find { it.kind == SyntaxKind.USER_TYPE }
            if (typeNode != null) {
                return context.typeResolver.resolve(parseTypeReference(typeNode), scope)
            }
        }

        return ClassType.ANY
    }

    private fun inferCallableReference(node: SyntaxNode, scope: Scope): KotlinType {
        val receiverNode = node.namedChildren.firstOrNull()
        val member = node.namedChildren.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?: return ErrorType.unresolved("No member in callable reference")

        val memberName = member.text

        if (receiverNode != null && receiverNode.kind == SyntaxKind.USER_TYPE) {
            val receiverType = context.typeResolver.resolve(parseTypeReference(receiverNode), scope)
            val result = symbolResolver.resolveMemberAccess(receiverType, memberName, scope)

            return when (result) {
                is SymbolResolver.ResolutionResult.Resolved -> {
                    when (val symbol = result.symbol) {
                        is FunctionSymbol -> symbolToFunctionType(symbol, scope)
                        is PropertySymbol -> {
                            val propType = symbol.type?.let {
                                context.typeResolver.resolve(it, scope)
                            } ?: ClassType.ANY
                            ClassType("kotlin.reflect.KProperty1").withArguments(receiverType, propType)
                        }
                        else -> ErrorType.unresolved(memberName)
                    }
                }
                else -> ErrorType.unresolved(memberName)
            }
        }

        val result = symbolResolver.resolveSimpleName(memberName, scope)
        return when (result) {
            is SymbolResolver.ResolutionResult.Resolved -> {
                when (val symbol = result.symbol) {
                    is FunctionSymbol -> symbolToFunctionType(symbol, scope)
                    is PropertySymbol -> {
                        val propType = symbol.type?.let {
                            context.typeResolver.resolve(it, scope)
                        } ?: ClassType.ANY
                        ClassType("kotlin.reflect.KProperty0").withArguments(propType)
                    }
                    else -> ErrorType.unresolved(memberName)
                }
            }
            else -> ErrorType.unresolved(memberName)
        }
    }

    private fun inferThisExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val label = node.namedChildren
            .find { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?.text

        return symbolResolver.resolveThis(scope, label)
            ?: run {
                context.reportError(DiagnosticCode.THIS_NOT_AVAILABLE, node)
                ErrorType.unresolved("this")
            }
    }

    private fun inferSuperExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val label = node.namedChildren
            .find { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?.text

        return symbolResolver.resolveSuper(scope, label)
            ?: run {
                context.reportError(DiagnosticCode.SUPER_NOT_AVAILABLE, node)
                ErrorType.unresolved("super")
            }
    }

    private fun inferJumpExpression(node: SyntaxNode, scope: Scope): KotlinType {
        val keyword = node.children.firstOrNull { !it.isNamed }?.text
        return when (keyword) {
            "return" -> {
                val value = node.namedChildren.firstOrNull()
                if (value != null) {
                    inferType(value, scope)
                }
                ClassType.NOTHING
            }
            "throw" -> ClassType.NOTHING
            "break", "continue" -> ClassType.NOTHING
            else -> ClassType.NOTHING
        }
    }

    private fun inferCollectionLiteral(
        node: SyntaxNode,
        scope: Scope,
        expectedType: KotlinType?
    ): KotlinType {
        val elements = node.namedChildren

        if (elements.isEmpty()) {
            return when {
                expectedType is ClassType && "List" in expectedType.fqName -> expectedType
                expectedType is ClassType && "Array" in expectedType.fqName -> expectedType
                else -> ClassType.LIST.withArguments(ClassType.NOTHING)
            }
        }

        val elementTypes = elements.map { inferType(it, scope) }
        val elementType = typeChecker.commonSupertype(elementTypes)

        return ClassType.ARRAY.withArguments(elementType)
    }

    private fun symbolToType(symbol: Symbol, scope: Scope): KotlinType {
        context.getSymbolType(symbol)?.let { return it }

        return when (symbol) {
            is PropertySymbol -> {
                symbol.type?.let { context.typeResolver.resolve(it, scope) }
                    ?: inferPropertyTypeFromInitializer(symbol, scope)
            }
            is ParameterSymbol -> {
                val baseType = symbol.type?.let { context.typeResolver.resolve(it, scope) }
                    ?: return ErrorType.unresolved(symbol.name)
                if (symbol.isVararg) {
                    varargToArrayType(baseType)
                } else {
                    baseType
                }
            }
            is FunctionSymbol -> symbolToFunctionType(symbol, scope)
            is ClassSymbol -> ClassType(symbol.qualifiedName)
            else -> ErrorType.unresolved(symbol.name)
        }
    }

    private fun inferPropertyTypeFromInitializer(symbol: PropertySymbol, scope: Scope): KotlinType {
        if (!symbol.hasInitializer) {
            return ErrorType.unresolved(symbol.name)
        }

        if (!context.startComputingType(symbol)) {
            return ErrorType.unresolved("Circular: ${symbol.name}")
        }

        try {
            val propertyNode = context.tree.nodeAtPosition(symbol.location.startPosition)
                ?: return ErrorType.unresolved(symbol.name)

            val declNode = if (propertyNode.kind == SyntaxKind.PROPERTY_DECLARATION) {
                propertyNode
            } else {
                propertyNode.parent?.takeIf { it.kind == SyntaxKind.PROPERTY_DECLARATION }
                    ?: findAncestor(propertyNode, SyntaxKind.PROPERTY_DECLARATION)
            }
                ?: return ErrorType.unresolved(symbol.name)

            val initializer = declNode.childByFieldName("value")
                ?: return ErrorType.unresolved(symbol.name)

            val inferredType = inferType(initializer, symbol.containingScope ?: scope)
            context.recordSymbolType(symbol, inferredType)
            return inferredType
        } finally {
            context.finishComputingType(symbol)
        }
    }

    private fun findAncestor(node: SyntaxNode, kind: SyntaxKind): SyntaxNode? {
        var current = node.parent
        while (current != null) {
            if (current.kind == kind) return current
            current = current.parent
        }
        return null
    }

    private fun varargToArrayType(elementType: KotlinType): KotlinType {
        return when (elementType) {
            is PrimitiveType -> when (elementType.kind) {
                PrimitiveKind.INT -> ClassType("kotlin.IntArray")
                PrimitiveKind.LONG -> ClassType("kotlin.LongArray")
                PrimitiveKind.SHORT -> ClassType("kotlin.ShortArray")
                PrimitiveKind.BYTE -> ClassType("kotlin.ByteArray")
                PrimitiveKind.FLOAT -> ClassType("kotlin.FloatArray")
                PrimitiveKind.DOUBLE -> ClassType("kotlin.DoubleArray")
                PrimitiveKind.CHAR -> ClassType("kotlin.CharArray")
                PrimitiveKind.BOOLEAN -> ClassType("kotlin.BooleanArray")
                else -> ClassType.ARRAY.withArguments(elementType)
            }
            is ClassType -> when (elementType.fqName) {
                "kotlin.Int" -> ClassType("kotlin.IntArray")
                "kotlin.Long" -> ClassType("kotlin.LongArray")
                "kotlin.Short" -> ClassType("kotlin.ShortArray")
                "kotlin.Byte" -> ClassType("kotlin.ByteArray")
                "kotlin.Float" -> ClassType("kotlin.FloatArray")
                "kotlin.Double" -> ClassType("kotlin.DoubleArray")
                "kotlin.Char" -> ClassType("kotlin.CharArray")
                "kotlin.Boolean" -> ClassType("kotlin.BooleanArray")
                else -> ClassType.ARRAY.withArguments(elementType)
            }
            else -> ClassType.ARRAY.withArguments(elementType)
        }
    }

    private fun symbolToFunctionType(function: FunctionSymbol, scope: Scope): FunctionType {
        val paramTypes = function.parameters.map { param ->
            param.type?.let { context.typeResolver.resolve(it, scope) } ?: ClassType.ANY
        }

        val returnType = function.returnType?.let {
            context.typeResolver.resolve(it, function.containingScope ?: scope)
        } ?: ClassType.UNIT

        val receiverType = function.receiverType?.let {
            context.typeResolver.resolve(it, function.containingScope ?: scope)
        }

        return FunctionType(
            parameterTypes = paramTypes,
            returnType = returnType,
            receiverType = receiverType,
            isSuspend = function.isSuspend
        )
    }

    private fun parseTypeReference(node: SyntaxNode): TypeReference {
        val nameNode = node.namedChildren.find { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?: node.namedChildren.firstOrNull()

        val name = nameNode?.text ?: node.text

        val typeArgs = node.namedChildren
            .find { it.kind == SyntaxKind.TYPE_ARGUMENTS }
            ?.namedChildren
            ?.filter { it.kind == SyntaxKind.TYPE_PROJECTION }
            ?.mapNotNull { projection ->
                projection.namedChildren.find { it.kind == SyntaxKind.USER_TYPE }
            }
            ?.map { parseTypeReference(it) }
            ?: emptyList()

        val isNullable = node.parent?.children?.any { it.text == "?" } == true

        return TypeReference(name, typeArgs, isNullable, node.range)
    }
}
