package org.appdevforall.codeonthego.lsp.kotlin.semantic

import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassKind
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ImportInfo
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.types.ClassType
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType
import org.appdevforall.codeonthego.lsp.kotlin.types.PrimitiveType

class SemanticAnalyzer(
    private val context: AnalysisContext
) {
    private val typeInferrer = TypeInferrer(context)
    private val symbolResolver = SymbolResolver(context)

    fun analyze() {
        validateImports()
        analyzeNode(context.tree.root, context.fileScope)
    }

    private fun validateImports() {
        for (import in context.symbolTable.imports) {
            if (import.isStar) {
                validateStarImport(import)
            } else {
                validateExplicitImport(import)
            }
        }
    }

    private fun validateExplicitImport(import: ImportInfo) {
        val fqName = import.fqName
        if (canResolveImport(fqName)) {
            return
        }

        val packagePart = fqName.substringBeforeLast('.', "")
        val memberName = fqName.substringAfterLast('.')

        if (packagePart.isNotEmpty()) {
            val containerClass = context.projectIndex?.findByFqName(packagePart)
                ?: context.stdlibIndex?.findByFqName(packagePart)

            if (containerClass != null) {
                val members = when {
                    context.projectIndex?.getClasspathIndex() != null ->
                        context.projectIndex.getClasspathIndex()?.findMembers(packagePart) ?: emptyList()
                    else -> emptyList()
                }
                if (members.any { it.name == memberName }) {
                    return
                }
            }
        }

        context.diagnostics.error(
            DiagnosticCode.UNRESOLVED_REFERENCE,
            import.range,
            fqName,
            filePath = context.filePath
        )
    }

    private fun validateStarImport(import: ImportInfo) {
        val packageName = import.fqName
        val hasPackage = context.projectIndex?.findByPackage(packageName)?.isNotEmpty() == true ||
            context.stdlibIndex?.findByPackage(packageName)?.isNotEmpty() == true

        if (!hasPackage) {
            val hasClass = context.projectIndex?.findByFqName(packageName) != null ||
                context.stdlibIndex?.findByFqName(packageName) != null

            if (!hasClass) {
                context.diagnostics.warning(
                    DiagnosticCode.UNRESOLVED_REFERENCE,
                    import.range,
                    "$packageName.*",
                    filePath = context.filePath
                )
            }
        }
    }

    private fun canResolveImport(fqName: String): Boolean {
        if (context.stdlibIndex?.findByFqName(fqName) != null) return true
        if (context.projectIndex?.findByFqName(fqName) != null) return true
        if (context.projectIndex?.getClasspathIndex()?.findByFqName(fqName) != null) return true

        val simpleName = fqName.substringAfterLast('.')
        val fileSymbol = context.symbolTable.topLevelSymbols.find { it.name == simpleName }
        if (fileSymbol != null) {
            val filePackage = context.symbolTable.packageName
            val expectedFqName = if (filePackage.isNotEmpty()) "$filePackage.$simpleName" else simpleName
            if (expectedFqName == fqName) return true
        }

        return false
    }

    private fun analyzeNode(node: SyntaxNode, scope: Scope) {
        when (node.kind) {
            SyntaxKind.FUNCTION_DECLARATION -> analyzeFunctionDeclaration(node, scope)
            SyntaxKind.PROPERTY_DECLARATION -> analyzePropertyDeclaration(node, scope)
            SyntaxKind.CLASS_DECLARATION,
            SyntaxKind.OBJECT_DECLARATION,
            SyntaxKind.INTERFACE_DECLARATION -> analyzeClassLikeDeclaration(node, scope)
            else -> {
                for (child in node.namedChildren) {
                    analyzeNode(child, scope)
                }
            }
        }
    }

    private fun analyzeFunctionDeclaration(node: SyntaxNode, scope: Scope) {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
        val name = nameNode?.text ?: return

        var symbol = scope.resolveFirst(name) as? FunctionSymbol
        var functionScope = symbol?.bodyScope ?: scope

        if (symbol == null) {
            for (topLevelSymbol in context.symbolTable.topLevelSymbols) {
                if (topLevelSymbol is ClassSymbol) {
                    val classMemberScope = topLevelSymbol.memberScope ?: continue
                    val funcInClass = classMemberScope.resolveFirst(name) as? FunctionSymbol
                    if (funcInClass != null) {
                        symbol = funcInClass
                        functionScope = funcInClass.bodyScope ?: classMemberScope
                        break
                    }
                }
            }
        }

        android.util.Log.d("SemanticAnalyzer", "analyzeFunctionDeclaration: name=$name, symbol=${symbol?.name}, bodyScope=${symbol?.bodyScope}")
        android.util.Log.d("SemanticAnalyzer", "  functionScope symbols: ${functionScope.allSymbols.map { it.name }}")
        android.util.Log.d("SemanticAnalyzer", "  functionScope.owner: ${functionScope.owner?.name}")

        val body = node.childByFieldName("body")
            ?: node.findChild(SyntaxKind.FUNCTION_BODY)
            ?: node.findChild(SyntaxKind.CONTROL_STRUCTURE_BODY)

        if (body != null) {
            analyzeBlock(body, functionScope)
        }
    }

    private fun analyzePropertyDeclaration(node: SyntaxNode, scope: Scope) {
        val isDestructuring = node.findChild(SyntaxKind.MULTI_VARIABLE_DECLARATION) != null ||
            node.children.any { it.text.startsWith("(") && it.text.contains(",") }

        if (isDestructuring) {
            val initializer = node.childByFieldName("value")
                ?: findInitializerExpression(node, null, null)
            if (initializer != null) {
                typeInferrer.inferType(initializer, scope)
            }
            return
        }

        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
        val propertyName = nameNode?.text

        val typeAnnotation = node.childByFieldName("type")
            ?: node.findChild(SyntaxKind.USER_TYPE)
            ?: node.findChild(SyntaxKind.NULLABLE_TYPE)

        val initializer = node.childByFieldName("value")
            ?: node.findChild(SyntaxKind.DELEGATE)
            ?: findInitializerExpression(node, nameNode, typeAnnotation)

        val getter = node.findChild(SyntaxKind.GETTER)
        val setter = node.findChild(SyntaxKind.SETTER)

        if (typeAnnotation == null && initializer == null && getter == null && nameNode != null) {
            context.reportError(DiagnosticCode.VARIABLE_WITH_NO_TYPE_NO_INITIALIZER, nameNode)
        }

        if (initializer != null) {
            if (propertyName != null) {
                checkForSelfReference(initializer, propertyName, scope)
            }
            typeInferrer.inferType(initializer, scope)
        }

        getter?.let { analyzeAccessor(it, scope) }
        setter?.let { analyzeAccessor(it, scope) }
    }

    private fun findInitializerExpression(
        node: SyntaxNode,
        nameNode: SyntaxNode?,
        typeAnnotation: SyntaxNode?
    ): SyntaxNode? {
        val skipKinds = setOf(
            SyntaxKind.VAL,
            SyntaxKind.VAR,
            SyntaxKind.MODIFIERS,
            SyntaxKind.VISIBILITY_MODIFIER,
            SyntaxKind.GETTER,
            SyntaxKind.SETTER,
            SyntaxKind.TYPE_PARAMETERS,
            SyntaxKind.USER_TYPE,
            SyntaxKind.NULLABLE_TYPE
        )

        for (child in node.namedChildren) {
            if (nameNode != null && child.range == nameNode.range) continue
            if (typeAnnotation != null && child.range == typeAnnotation.range) continue
            if (child.kind in skipKinds) continue
            if (isExpression(child.kind)) {
                return child
            }
        }
        return null
    }

    private fun checkForSelfReference(node: SyntaxNode, propertyName: String, scope: Scope) {
        when (node.kind) {
            SyntaxKind.SIMPLE_IDENTIFIER -> {
                if (node.text == propertyName) {
                    context.reportError(
                        DiagnosticCode.UNINITIALIZED_VARIABLE,
                        node,
                        propertyName
                    )
                }
            }
            else -> {
                for (child in node.namedChildren) {
                    checkForSelfReference(child, propertyName, scope)
                }
            }
        }
    }

    private fun analyzeAccessor(node: SyntaxNode, scope: Scope) {
        val body = node.childByFieldName("body")
            ?: node.findChild(SyntaxKind.CONTROL_STRUCTURE_BODY)

        if (body != null) {
            analyzeBlock(body, scope)
        }
    }

    private fun analyzeClassLikeDeclaration(node: SyntaxNode, scope: Scope) {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.findChild(SyntaxKind.TYPE_IDENTIFIER)
        val name = nameNode?.text ?: return

        val symbol = scope.resolveFirst(name)
        val classSymbol = symbol as? ClassSymbol
        val classScope = classSymbol?.memberScope ?: scope

        val body = node.childByFieldName("body")
            ?: node.findChild(SyntaxKind.CLASS_BODY)

        if (body != null) {
            for (member in body.namedChildren) {
                analyzeNode(member, classScope)
            }
        }

        if (classSymbol != null && nameNode != null) {
            checkAbstractMemberImplementation(classSymbol, nameNode)
        }
    }

    private fun checkAbstractMemberImplementation(classSymbol: ClassSymbol, nameNode: SyntaxNode) {
        if (classSymbol.modifiers.isAbstract || classSymbol.isInterface) {
            return
        }

        val abstractMembers = collectAbstractMembers(classSymbol)
        val implementedMembers = collectImplementedMembers(classSymbol)

        for (abstractMember in abstractMembers) {
            val memberName = abstractMember.name
            val isImplemented = implementedMembers.any { it.name == memberName }
            if (!isImplemented) {
                val memberDescription = when (abstractMember) {
                    is FunctionSymbol -> "fun ${abstractMember.name}(...)"
                    is PropertySymbol -> if (abstractMember.isVar) "var ${abstractMember.name}" else "val ${abstractMember.name}"
                    else -> abstractMember.name
                }
                context.reportError(
                    DiagnosticCode.ABSTRACT_MEMBER_NOT_IMPLEMENTED,
                    nameNode,
                    classSymbol.name,
                    memberDescription
                )
            }
        }
    }

    private fun collectAbstractMembers(classSymbol: ClassSymbol): List<Symbol> {
        val abstractMembers = mutableMapOf<String, Symbol>()

        for (superTypeRef in classSymbol.superTypes) {
            val superClass = resolveClassSymbol(superTypeRef.name, classSymbol.containingScope ?: context.fileScope)
            if (superClass != null) {
                for (member in superClass.members) {
                    val isAbstract = when (member) {
                        is FunctionSymbol -> {
                            member.modifiers.isAbstract || (superClass.isInterface && !member.hasBody)
                        }
                        is PropertySymbol -> {
                            member.modifiers.isAbstract || (superClass.isInterface && !member.hasInitializer && member.getter == null)
                        }
                        else -> false
                    }
                    if (isAbstract && member.name !in abstractMembers) {
                        abstractMembers[member.name] = member
                    }
                }
            }
        }

        return abstractMembers.values.toList()
    }

    private fun collectImplementedMembers(classSymbol: ClassSymbol): List<Symbol> {
        return classSymbol.members.filter { member ->
            when (member) {
                is FunctionSymbol -> !member.modifiers.isAbstract
                is PropertySymbol -> !member.modifiers.isAbstract
                else -> true
            }
        }
    }

    private fun analyzeBlock(node: SyntaxNode, scope: Scope) {
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] analyzeBlock: node.kind=${node.kind}")
        val statements = when (node.kind) {
            SyntaxKind.FUNCTION_BODY -> {
                val block = node.findChild(SyntaxKind.STATEMENTS)
                block?.namedChildren ?: node.namedChildren
            }
            SyntaxKind.CONTROL_STRUCTURE_BODY -> {
                val block = node.findChild(SyntaxKind.STATEMENTS)
                block?.namedChildren ?: node.namedChildren
            }
            SyntaxKind.STATEMENTS -> node.namedChildren
            else -> node.namedChildren
        }

        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] analyzeBlock found ${statements.size} statements: ${statements.map { "${it.kind}:${it.text.take(20)}" }}")
        for (statement in statements) {
            analyzeStatement(statement, scope)
        }
    }

    private fun analyzeStatement(node: SyntaxNode, scope: Scope) {
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] analyzeStatement: kind=${node.kind}, text='${node.text.take(40)}'")
        when (node.kind) {
            SyntaxKind.PROPERTY_DECLARATION -> analyzePropertyDeclaration(node, scope)
            SyntaxKind.ASSIGNMENT -> {
                android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] Found ASSIGNMENT node!")
                analyzeAssignment(node, scope)
            }
            SyntaxKind.AUGMENTED_ASSIGNMENT -> analyzeAugmentedAssignment(node, scope)
            SyntaxKind.RETURN -> analyzeReturn(node, scope)
            SyntaxKind.IF_EXPRESSION -> analyzeIfExpression(node, scope)
            SyntaxKind.WHEN_EXPRESSION -> analyzeWhenExpression(node, scope)
            SyntaxKind.FOR_STATEMENT -> analyzeForStatement(node, scope)
            SyntaxKind.WHILE_STATEMENT -> analyzeWhileStatement(node, scope)
            SyntaxKind.DO_WHILE_STATEMENT -> analyzeDoWhileStatement(node, scope)
            SyntaxKind.TRY_EXPRESSION -> analyzeTryExpression(node, scope)
            else -> {
                if (isExpression(node.kind)) {
                    typeInferrer.inferType(node, scope)
                } else {
                    for (child in node.namedChildren) {
                        analyzeStatement(child, scope)
                    }
                }
            }
        }
    }

    private fun analyzeAssignment(node: SyntaxNode, scope: Scope) {
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] analyzeAssignment called: node.text='${node.text.take(50)}', namedChildren=${node.namedChildren.map { "${it.kind}:${it.text.take(20)}" }}")
        val target = node.childByFieldName("left") ?: node.namedChildren.getOrNull(0)
        val value = node.childByFieldName("right") ?: node.namedChildren.getOrNull(1)
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] target=${target?.kind}:${target?.text?.take(20)}, value=${value?.kind}:${value?.text?.take(20)}")

        var targetType: KotlinType? = null
        if (target != null) {
            targetType = typeInferrer.inferType(target, scope)
            checkValReassignment(target, scope)
        }
        if (value != null) {
            if (value.kind == SyntaxKind.JUMP_EXPRESSION) {
                context.reportError(DiagnosticCode.UNREACHABLE_CODE, node)
            } else {
                val valueType = typeInferrer.inferType(value, scope)
                if (targetType != null && !targetType.hasError && !valueType.hasError) {
                    checkAssignmentTypeCompatibility(targetType, valueType, value)
                }
            }
        }
    }

    private fun checkAssignmentTypeCompatibility(
        targetType: KotlinType,
        valueType: KotlinType,
        valueNode: SyntaxNode
    ) {
        if (!context.typeChecker.isAssignableTo(valueType, targetType)) {
            context.reportError(
                DiagnosticCode.TYPE_MISMATCH,
                valueNode,
                targetType.render(),
                valueType.render()
            )
        }
    }

    private fun analyzeAugmentedAssignment(node: SyntaxNode, scope: Scope) {
        val target = node.childByFieldName("left") ?: node.namedChildren.getOrNull(0)
        val value = node.childByFieldName("right") ?: node.namedChildren.getOrNull(1)

        var targetType: KotlinType? = null
        if (target != null) {
            targetType = typeInferrer.inferType(target, scope)
            checkValReassignment(target, scope)
        }
        if (value != null) {
            val valueType = typeInferrer.inferType(value, scope)
            if (targetType != null && !targetType.hasError && !valueType.hasError) {
                checkAssignmentTypeCompatibility(targetType, valueType, value)
            }
        }
    }

    private fun checkValReassignment(target: SyntaxNode, scope: Scope) {
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] checkValReassignment: target.kind=${target.kind}, target.text='${target.text.take(50)}'")
        val symbol = extractAssignableSymbol(target, scope)
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] extractAssignableSymbol returned: ${symbol?.name}, isVar=${symbol?.isVar}")
        if (symbol == null) {
            android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] symbol is null, skipping check")
            return
        }
        if (!symbol.isVar) {
            android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] REPORTING VAL_REASSIGNMENT for '${symbol.name}'")
            context.reportError(DiagnosticCode.VAL_REASSIGNMENT, target)
        } else {
            android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] symbol '${symbol.name}' is var, no error")
        }
    }

    private fun extractAssignableSymbol(node: SyntaxNode, scope: Scope): PropertySymbol? {
        android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] extractAssignableSymbol: node.kind=${node.kind}, text='${node.text.take(30)}'")
        return when (node.kind) {
            SyntaxKind.DIRECTLY_ASSIGNABLE_EXPRESSION -> {
                android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] DIRECTLY_ASSIGNABLE_EXPRESSION, children=${node.namedChildren.map { "${it.kind}:${it.text.take(20)}" }}")
                val inner = node.namedChildren.firstOrNull()
                if (inner != null) extractAssignableSymbol(inner, scope) else null
            }
            SyntaxKind.SIMPLE_IDENTIFIER -> {
                val resolved = scope.resolveFirst(node.text)
                android.util.Log.d("SemanticAnalyzer", "[VAL-CHECK] SIMPLE_IDENTIFIER '${node.text}' resolved to: $resolved (type=${resolved?.javaClass?.simpleName})")
                resolved as? PropertySymbol
            }
            SyntaxKind.NAVIGATION_EXPRESSION -> {
                val receiver = node.childByFieldName("receiver") ?: node.namedChildren.firstOrNull()
                val suffix = node.childByFieldName("suffix")
                    ?: node.findChild(SyntaxKind.NAVIGATION_SUFFIX)?.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                    ?: node.namedChildren.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER && it != receiver }

                if (receiver != null && suffix != null) {
                    val receiverType = typeInferrer.inferType(receiver, scope)
                    val result = symbolResolver.resolveMemberAccess(receiverType, suffix.text, scope)
                    if (result is SymbolResolver.ResolutionResult.Resolved) {
                        result.symbol as? PropertySymbol
                    } else null
                } else null
            }
            SyntaxKind.INDEXING_EXPRESSION -> {
                val receiver = node.namedChildren.firstOrNull()
                if (receiver != null) extractAssignableSymbol(receiver, scope) else null
            }
            SyntaxKind.PARENTHESIZED_EXPRESSION -> {
                val inner = node.namedChildren.firstOrNull()
                if (inner != null) extractAssignableSymbol(inner, scope) else null
            }
            else -> null
        }
    }

    private fun analyzeReturn(node: SyntaxNode, scope: Scope) {
        val expr = node.namedChildren.firstOrNull()
        if (expr != null) {
            typeInferrer.inferType(expr, scope)
        }
    }

    private fun analyzeIfExpression(node: SyntaxNode, scope: Scope) {
        val condition = node.childByFieldName("condition")
        val thenBranch = node.childByFieldName("consequence")
        val elseBranch = node.childByFieldName("alternative")

        if (condition != null) {
            val conditionType = typeInferrer.inferType(condition, scope)
            checkConditionType(conditionType, condition)
        }

        val smartCastInfo = extractSmartCastFromCondition(condition, scope)

        if (thenBranch != null) {
            if (smartCastInfo != null) {
                context.pushSmartCast(smartCastInfo.first, smartCastInfo.second)
                analyzeBlock(thenBranch, scope)
                context.popSmartCast(smartCastInfo.first)
            } else {
                analyzeBlock(thenBranch, scope)
            }
        }
        elseBranch?.let { analyzeBlock(it, scope) }
    }

    private fun extractSmartCastFromCondition(condition: SyntaxNode?, scope: Scope): Pair<Symbol, SmartCastInfo>? {
        if (condition == null) return null

        val checkExpr = when (condition.kind) {
            SyntaxKind.CHECK_EXPRESSION -> condition
            SyntaxKind.PARENTHESIZED_EXPRESSION -> {
                condition.namedChildren.firstOrNull()?.let { inner ->
                    if (inner.kind == SyntaxKind.CHECK_EXPRESSION) inner else null
                }
            }
            else -> condition.findChild(SyntaxKind.CHECK_EXPRESSION)
        } ?: return null

        val isOperator = checkExpr.children.find { it.text == "is" || it.text == "!is" }
        if (isOperator?.text != "is") return null

        val subjectNode = checkExpr.namedChildren.firstOrNull() ?: return null
        val typeNode = checkExpr.findChild(SyntaxKind.USER_TYPE)
            ?: checkExpr.findChild(SyntaxKind.NULLABLE_TYPE)
            ?: return null

        val symbol = extractSubjectSymbol(subjectNode, scope) ?: return null
        val originalType = typeInferrer.inferType(subjectNode, scope)
        val castType = extractTypeFromNode(typeNode, scope) ?: return null

        return symbol to SmartCastInfo(originalType, castType, condition)
    }

    private fun extractTypeFromNode(typeNode: SyntaxNode, scope: Scope): KotlinType? {
        val fullTypeName = extractFullTypeName(typeNode)

        if ('.' in fullTypeName) {
            val parts = fullTypeName.split('.')
            val outerClassName = parts.first()
            val innerNames = parts.drop(1)

            val outerClass = scope.resolve(outerClassName).filterIsInstance<ClassSymbol>().firstOrNull()
                ?: context.symbolTable.fileScope.resolve(outerClassName).filterIsInstance<ClassSymbol>().firstOrNull()

            if (outerClass != null) {
                var currentClass: ClassSymbol = outerClass
                for (innerName in innerNames) {
                    val innerClass = currentClass.members.filterIsInstance<ClassSymbol>()
                        .find { it.name == innerName }
                    if (innerClass != null) {
                        currentClass = innerClass
                    } else {
                        break
                    }
                }
                return ClassType(currentClass.qualifiedName)
            }
        }

        val classSymbol = scope.resolve(fullTypeName).filterIsInstance<ClassSymbol>().firstOrNull()
            ?: context.symbolTable.fileScope.resolve(fullTypeName).filterIsInstance<ClassSymbol>().firstOrNull()

        return if (classSymbol != null) {
            ClassType(classSymbol.qualifiedName)
        } else {
            ClassType(fullTypeName)
        }
    }

    private fun analyzeWhenExpression(node: SyntaxNode, scope: Scope) {
        val subject = node.childByFieldName("subject")
        val subjectType = subject?.let { typeInferrer.inferType(it, scope) }
        val subjectSymbol = extractSubjectSymbol(subject, scope)

        val entries = node.findChildren(SyntaxKind.WHEN_ENTRY)
        var hasElseBranch = false
        val coveredCases = mutableSetOf<String>()

        for (entry in entries) {
            val conditions = entry.findChildren(SyntaxKind.WHEN_CONDITION)

            if (conditions.isEmpty()) {
                hasElseBranch = true
            }

            var smartCastType: KotlinType? = null
            for (condition in conditions) {
                for (child in condition.namedChildren) {
                    typeInferrer.inferType(child, scope)

                    if (child.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                        coveredCases.add(child.text)
                    } else if (child.kind == SyntaxKind.NAVIGATION_EXPRESSION) {
                        coveredCases.add(child.text)
                    } else if (child.kind == SyntaxKind.TYPE_TEST) {
                        val castType = extractTypeTestType(child, scope)
                        if (castType != null) {
                            smartCastType = castType
                            val typeName = child.findChild(SyntaxKind.USER_TYPE)?.text
                                ?: child.findChild(SyntaxKind.SIMPLE_IDENTIFIER)?.text
                            if (typeName != null) {
                                coveredCases.add(typeName)
                            }
                        }
                    }
                }
            }

            val body = entry.findChild(SyntaxKind.CONTROL_STRUCTURE_BODY)
            if (body != null) {
                if (subjectSymbol != null && subjectType != null && smartCastType != null) {
                    context.pushSmartCast(subjectSymbol, SmartCastInfo(subjectType, smartCastType, entry), body)
                    analyzeBlock(body, scope)
                    context.popSmartCast(subjectSymbol)
                } else {
                    analyzeBlock(body, scope)
                }
            }
        }

        val isUsedAsExpression = isWhenUsedAsExpression(node)
        if (isUsedAsExpression && !hasElseBranch && subjectType != null) {
            checkWhenExhaustiveness(node, subjectType, coveredCases, scope)
        }
    }

    private fun isWhenUsedAsExpression(node: SyntaxNode): Boolean {
        val parent = node.parent ?: return false
        return when (parent.kind) {
            SyntaxKind.PROPERTY_DECLARATION,
            SyntaxKind.ASSIGNMENT,
            SyntaxKind.VALUE_ARGUMENT,
            SyntaxKind.RETURN -> true
            else -> false
        }
    }

    private fun checkWhenExhaustiveness(
        node: SyntaxNode,
        subjectType: KotlinType,
        coveredCases: Set<String>,
        scope: Scope
    ) {
        if (subjectType !is ClassType) return

        val classSymbol = resolveClassSymbol(subjectType.fqName, scope) ?: return

        when {
            classSymbol.kind == ClassKind.ENUM_CLASS -> {
                val enumEntries = classSymbol.members
                    .filterIsInstance<ClassSymbol>()
                    .filter { it.kind == ClassKind.ENUM_ENTRY }
                    .map { it.name }
                    .toSet()

                val missingCases = enumEntries - coveredCases
                if (missingCases.isNotEmpty()) {
                    val missing = missingCases.joinToString(", ") { "'$it'" }
                    context.reportError(DiagnosticCode.MISSING_WHEN_BRANCH, node, missing)
                }
            }
            classSymbol.modifiers.isSealed -> {
                val subclasses = findSealedSubclasses(classSymbol, scope)
                val subclassNames = subclasses.map { it.name }.toSet()
                val missingCases = subclassNames - coveredCases
                if (missingCases.isNotEmpty()) {
                    val missing = missingCases.joinToString(", ") { "'$it'" }
                    context.reportError(DiagnosticCode.MISSING_WHEN_BRANCH, node, missing)
                }
            }
            subjectType.fqName == "kotlin.Boolean" -> {
                val booleanCases = setOf("true", "false")
                val missingCases = booleanCases - coveredCases
                if (missingCases.isNotEmpty()) {
                    val missing = missingCases.joinToString(", ") { "'$it'" }
                    context.reportError(DiagnosticCode.MISSING_WHEN_BRANCH, node, missing)
                }
            }
        }
    }

    private fun resolveClassSymbol(fqName: String, scope: Scope): ClassSymbol? {
        val simpleName = fqName.substringAfterLast('.')
        return scope.resolve(simpleName).filterIsInstance<ClassSymbol>().firstOrNull()
            ?: context.symbolTable.fileScope.resolve(simpleName).filterIsInstance<ClassSymbol>().firstOrNull()
    }

    private fun findSealedSubclasses(sealedClass: ClassSymbol, scope: Scope): List<ClassSymbol> {
        val result = mutableListOf<ClassSymbol>()

        for (symbol in context.symbolTable.topLevelSymbols) {
            if (symbol is ClassSymbol) {
                val implementsSealedClass = symbol.superTypes.any {
                    it.name == sealedClass.name || it.name == sealedClass.qualifiedName
                }
                if (implementsSealedClass) {
                    result.add(symbol)
                }
            }
        }

        context.projectIndex?.getAllClasses()
            ?.filter { indexed ->
                indexed.superTypes.any { st ->
                    st == sealedClass.qualifiedName || st == sealedClass.name
                }
            }
            ?.mapNotNull { it.toSyntheticSymbol() as? ClassSymbol }
            ?.forEach { if (it.name !in result.map { r -> r.name }) result.add(it) }

        return result
    }

    private fun analyzeForStatement(node: SyntaxNode, scope: Scope) {
        val iterable = node.childByFieldName("iterable")
        val body = node.childByFieldName("body")

        iterable?.let { typeInferrer.inferType(it, scope) }
        body?.let { analyzeBlock(it, scope) }
    }

    private fun analyzeWhileStatement(node: SyntaxNode, scope: Scope) {
        val condition = node.childByFieldName("condition")
        val body = node.childByFieldName("body")

        if (condition != null) {
            val conditionType = typeInferrer.inferType(condition, scope)
            checkConditionType(conditionType, condition)
        }
        body?.let { analyzeBlock(it, scope) }
    }

    private fun analyzeDoWhileStatement(node: SyntaxNode, scope: Scope) {
        val body = node.childByFieldName("body")
        val condition = node.childByFieldName("condition")

        body?.let { analyzeBlock(it, scope) }
        if (condition != null) {
            val conditionType = typeInferrer.inferType(condition, scope)
            checkConditionType(conditionType, condition)
        }
    }

    private fun checkConditionType(type: KotlinType, node: SyntaxNode) {
        if (type.hasError) return
        if (!isBoolean(type)) {
            context.reportError(DiagnosticCode.CONDITION_TYPE_MISMATCH, node, type.render())
        }
    }

    private fun isBoolean(type: KotlinType): Boolean {
        return type == PrimitiveType.BOOLEAN ||
            (type is ClassType && type.fqName == "kotlin.Boolean")
    }

    private fun analyzeTryExpression(node: SyntaxNode, scope: Scope) {
        val tryBlock = node.childByFieldName("body")
        tryBlock?.let { analyzeBlock(it, scope) }

        for (catchClause in node.findChildren(SyntaxKind.CATCH_BLOCK)) {
            val catchBody = catchClause.childByFieldName("body")
            catchBody?.let { analyzeBlock(it, scope) }
        }

        val finallyClause = node.findChild(SyntaxKind.FINALLY_BLOCK)
        val finallyBody = finallyClause?.childByFieldName("body")
        finallyBody?.let { analyzeBlock(it, scope) }
    }

    private fun isExpression(kind: SyntaxKind): Boolean {
        return kind in setOf(
            SyntaxKind.CALL_EXPRESSION,
            SyntaxKind.NAVIGATION_EXPRESSION,
            SyntaxKind.SIMPLE_IDENTIFIER,
            SyntaxKind.STRING_LITERAL,
            SyntaxKind.INTEGER_LITERAL,
            SyntaxKind.REAL_LITERAL,
            SyntaxKind.BOOLEAN_LITERAL,
            SyntaxKind.NULL_LITERAL,
            SyntaxKind.BINARY_EXPRESSION,
            SyntaxKind.PREFIX_EXPRESSION,
            SyntaxKind.POSTFIX_EXPRESSION,
            SyntaxKind.PARENTHESIZED_EXPRESSION,
            SyntaxKind.INDEXING_EXPRESSION,
            SyntaxKind.AS_EXPRESSION,
            SyntaxKind.TYPE_TEST,
            SyntaxKind.LAMBDA_LITERAL,
            SyntaxKind.OBJECT_LITERAL,
            SyntaxKind.COLLECTION_LITERAL,
            SyntaxKind.THIS_EXPRESSION,
            SyntaxKind.SUPER_EXPRESSION,
            SyntaxKind.RANGE_EXPRESSION,
            SyntaxKind.INFIX_EXPRESSION,
            SyntaxKind.ELVIS_EXPRESSION,
            SyntaxKind.CHECK_EXPRESSION,
            SyntaxKind.COMPARISON_EXPRESSION,
            SyntaxKind.EQUALITY_EXPRESSION,
            SyntaxKind.CONJUNCTION_EXPRESSION,
            SyntaxKind.DISJUNCTION_EXPRESSION,
            SyntaxKind.ADDITIVE_EXPRESSION,
            SyntaxKind.MULTIPLICATIVE_EXPRESSION
        )
    }

    private fun extractSubjectSymbol(subject: SyntaxNode?, scope: Scope): Symbol? {
        if (subject == null) return null

        return when (subject.kind) {
            SyntaxKind.SIMPLE_IDENTIFIER -> {
                scope.resolveFirst(subject.text)
            }
            SyntaxKind.PARENTHESIZED_EXPRESSION -> {
                val inner = subject.namedChildren.firstOrNull()
                extractSubjectSymbol(inner, scope)
            }
            else -> {
                val innerIdentifier = subject.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                if (innerIdentifier != null) {
                    scope.resolveFirst(innerIdentifier.text)
                } else {
                    null
                }
            }
        }
    }

    private fun extractTypeTestType(typeTest: SyntaxNode, scope: Scope): KotlinType? {
        val typeNode = typeTest.findChild(SyntaxKind.USER_TYPE)
            ?: typeTest.findChild(SyntaxKind.NULLABLE_TYPE)
            ?: return null

        val fullTypeName = extractFullTypeName(typeNode)

        if ('.' in fullTypeName) {
            val parts = fullTypeName.split('.')
            val outerClassName = parts.first()
            val innerNames = parts.drop(1)

            val outerClass = scope.resolve(outerClassName).filterIsInstance<ClassSymbol>().firstOrNull()
                ?: context.symbolTable.fileScope.resolve(outerClassName).filterIsInstance<ClassSymbol>().firstOrNull()

            if (outerClass != null) {
                var currentClass: ClassSymbol = outerClass
                for (innerName in innerNames) {
                    val innerClass = currentClass.members.filterIsInstance<ClassSymbol>()
                        .find { it.name == innerName }
                    if (innerClass != null) {
                        currentClass = innerClass
                    } else {
                        break
                    }
                }
                return ClassType(currentClass.qualifiedName)
            }
        }

        val classSymbol = scope.resolve(fullTypeName).filterIsInstance<ClassSymbol>().firstOrNull()
            ?: context.symbolTable.fileScope.resolve(fullTypeName).filterIsInstance<ClassSymbol>().firstOrNull()

        return if (classSymbol != null) {
            ClassType(classSymbol.qualifiedName)
        } else {
            ClassType(fullTypeName)
        }
    }

    private fun extractFullTypeName(typeNode: SyntaxNode): String {
        val identifiers = mutableListOf<String>()

        fun collectIdentifiers(node: SyntaxNode) {
            when (node.kind) {
                SyntaxKind.SIMPLE_IDENTIFIER -> identifiers.add(node.text)
                SyntaxKind.USER_TYPE, SyntaxKind.SIMPLE_USER_TYPE -> {
                    for (child in node.namedChildren) {
                        collectIdentifiers(child)
                    }
                }
                else -> {
                    for (child in node.namedChildren) {
                        collectIdentifiers(child)
                    }
                }
            }
        }

        collectIdentifiers(typeNode)
        return if (identifiers.isNotEmpty()) identifiers.joinToString(".") else typeNode.text
    }
}
