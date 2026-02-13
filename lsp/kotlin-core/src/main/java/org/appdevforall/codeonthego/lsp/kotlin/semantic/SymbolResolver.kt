package org.appdevforall.codeonthego.lsp.kotlin.semantic

import android.util.Log
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbolKind
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ClassSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ImportInfo
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Modifiers
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.PropertySymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Symbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolLocation
import org.appdevforall.codeonthego.lsp.kotlin.symbol.SymbolTable
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeReference
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeAliasSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.TypeParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.types.ClassType
import org.appdevforall.codeonthego.lsp.kotlin.types.ErrorType
import org.appdevforall.codeonthego.lsp.kotlin.types.FunctionType
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType
import org.appdevforall.codeonthego.lsp.kotlin.types.PrimitiveType
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeParameter
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeChecker

private const val TAG = "SymbolResolver"

/**
 * Resolves name references to their corresponding symbols.
 *
 * SymbolResolver handles all forms of name resolution in Kotlin:
 * - Simple names via scope chain lookup
 * - Qualified names (package.Class.member)
 * - Member access on expressions (expr.member)
 * - Import-based resolution
 * - Extension function/property lookup
 *
 * ## Resolution Algorithm
 *
 * For simple names:
 * 1. Search current scope up through parent scopes
 * 2. Check imports (explicit and star imports)
 * 3. Check implicitly imported packages (kotlin.*, kotlin.collections.*, etc.)
 *
 * For member access:
 * 1. Infer receiver type
 * 2. Search receiver type's members
 * 3. Search extension functions/properties applicable to receiver type
 *
 * ## Usage
 *
 * ```kotlin
 * val resolver = SymbolResolver(context)
 * val symbol = resolver.resolveSimpleName("foo", scope)
 * val member = resolver.resolveMemberAccess(receiverType, "bar", scope)
 * ```
 */
class SymbolResolver(
    private val context: AnalysisContext
) {
    private val typeChecker: TypeChecker = context.typeChecker
    private val symbolTable: SymbolTable = context.symbolTable

    private val implicitImports = listOf(
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text"
    )

    private val stdlibIndex = context.stdlibIndex
    private val projectIndex = context.projectIndex

    /**
     * Result of symbol resolution.
     */
    sealed class ResolutionResult {
        /**
         * Successfully resolved to one or more symbols.
         * Multiple symbols indicate overloaded functions.
         */
        data class Resolved(val symbols: List<Symbol>) : ResolutionResult() {
            val symbol: Symbol get() = symbols.first()
            val isAmbiguous: Boolean get() = symbols.size > 1 && symbols.none { it is FunctionSymbol }
        }

        /**
         * Resolution failed - symbol not found.
         */
        data class Unresolved(val name: String) : ResolutionResult()

        /**
         * Resolution found multiple incompatible candidates.
         */
        data class Ambiguous(val candidates: List<Symbol>) : ResolutionResult()

        val isResolved: Boolean get() = this is Resolved
        val isUnresolved: Boolean get() = this is Unresolved
    }

    /**
     * Resolves a simple name in the given scope.
     *
     * @param name The name to resolve
     * @param scope The scope to start resolution from
     * @return Resolution result with found symbols or error
     */
    fun resolveSimpleName(name: String, scope: Scope): ResolutionResult {
        val scopeResult = scope.resolve(name)
        if (scopeResult.isNotEmpty()) {
            Log.d(TAG, "resolveSimpleName: '$name' found in scope: ${scopeResult.map { it::class.simpleName }}")
            return ResolutionResult.Resolved(scopeResult)
        }

        val importResult = resolveViaImports(name)
        if (importResult.isNotEmpty()) {
            Log.d(TAG, "resolveSimpleName: '$name' found via imports")
            return ResolutionResult.Resolved(importResult)
        }

        val implicitResult = resolveViaImplicitImports(name)
        if (implicitResult.isNotEmpty()) {
            Log.d(TAG, "resolveSimpleName: '$name' found via implicit imports")
            return ResolutionResult.Resolved(implicitResult)
        }

        val projectResult = resolveViaProjectIndex(name)
        if (projectResult.isNotEmpty()) {
            Log.d(TAG, "resolveSimpleName: '$name' found via project index")
            return ResolutionResult.Resolved(projectResult)
        }

        Log.d(TAG, "resolveSimpleName: '$name', checking stdlib (index=${stdlibIndex != null}, size=${stdlibIndex?.size ?: 0})")
        val stdlibSymbols = stdlibIndex?.findBySimpleName(name) ?: emptyList()
        Log.d(TAG, "resolveSimpleName: '$name' found ${stdlibSymbols.size} stdlib symbols")
        if (stdlibSymbols.isNotEmpty()) {
            return ResolutionResult.Resolved(stdlibSymbols.map { it.toSyntheticSymbol() })
        }

        val classpathIndex = projectIndex?.getClasspathIndex()
        Log.d(TAG, "resolveSimpleName: '$name', checking classpath (index=${classpathIndex != null}, size=${classpathIndex?.size ?: 0})")
        val classpathSymbols = classpathIndex?.findBySimpleName(name) ?: emptyList()
        Log.d(TAG, "resolveSimpleName: '$name' found ${classpathSymbols.size} classpath symbols")
        if (classpathSymbols.isNotEmpty()) {
            return ResolutionResult.Resolved(classpathSymbols.map { it.toSyntheticSymbol() })
        }

        Log.d(TAG, "resolveSimpleName: '$name' NOT FOUND anywhere")
        return ResolutionResult.Unresolved(name)
    }

    /**
     * Resolves a qualified name (e.g., "kotlin.String", "Foo.Bar.baz").
     *
     * @param qualifiedName The full qualified name
     * @param scope The scope for resolving the first component
     * @return Resolution result
     */
    fun resolveQualifiedName(qualifiedName: String, scope: Scope): ResolutionResult {
        val parts = qualifiedName.split('.')
        if (parts.isEmpty()) {
            return ResolutionResult.Unresolved(qualifiedName)
        }

        var currentResult = resolveSimpleName(parts.first(), scope)
        if (currentResult !is ResolutionResult.Resolved) {
            return ResolutionResult.Unresolved(qualifiedName)
        }

        for (i in 1 until parts.size) {
            val memberName = parts[i]
            val currentSymbol = (currentResult as ResolutionResult.Resolved).symbol

            val memberResult = when (currentSymbol) {
                is ClassSymbol -> resolveMemberInClass(currentSymbol, memberName)
                is PropertySymbol -> {
                    val propertyType = context.typeResolver.resolve(
                        currentSymbol.type ?: return ResolutionResult.Unresolved(qualifiedName),
                        scope
                    )
                    resolveMemberInType(propertyType, memberName, scope)
                }
                else -> return ResolutionResult.Unresolved(qualifiedName)
            }

            if (memberResult !is ResolutionResult.Resolved) {
                return ResolutionResult.Unresolved(qualifiedName)
            }
            currentResult = memberResult
        }

        return currentResult
    }

    /**
     * Resolves a member access expression (expr.member).
     *
     * @param receiverType The type of the receiver expression
     * @param memberName The name of the member being accessed
     * @param scope The scope for extension function lookup
     * @return Resolution result
     */
    fun resolveMemberAccess(
        receiverType: KotlinType,
        memberName: String,
        scope: Scope
    ): ResolutionResult {
        val members = resolveMemberInType(receiverType, memberName, scope)
        if (members is ResolutionResult.Resolved) {
            return members
        }

        val extensions = resolveExtension(receiverType, memberName, scope)
        if (extensions.isNotEmpty()) {
            return ResolutionResult.Resolved(extensions)
        }

        val qualifiedReceiverType = receiverType.render(qualified = true)
        val simpleReceiverType = receiverType.render(qualified = false)
        Log.d(TAG, "resolveMemberAccess: member='$memberName', qualifiedReceiver='$qualifiedReceiverType', simpleReceiver='$simpleReceiverType'")

        val stdlibExtensions = stdlibIndex?.findExtensions(qualifiedReceiverType)
            ?.filter { it.name == memberName }
            ?: emptyList()
        Log.d(TAG, "resolveMemberAccess: found ${stdlibExtensions.size} extensions for qualified type")

        if (stdlibExtensions.isNotEmpty()) {
            return ResolutionResult.Resolved(stdlibExtensions.map { it.toSyntheticSymbol() })
        }

        val simpleExtensions = stdlibIndex?.findExtensions(simpleReceiverType)
            ?.filter { it.name == memberName }
            ?: emptyList()
        Log.d(TAG, "resolveMemberAccess: found ${simpleExtensions.size} extensions for simple type")

        if (simpleExtensions.isNotEmpty()) {
            return ResolutionResult.Resolved(simpleExtensions.map { it.toSyntheticSymbol() })
        }

        val extensionByName = stdlibIndex?.findExtensionsByName(memberName)
            ?.firstOrNull()
        Log.d(TAG, "resolveMemberAccess: extensionByName for '$memberName' = ${extensionByName != null}")

        if (extensionByName != null) {
            return ResolutionResult.Resolved(listOf(extensionByName.toSyntheticSymbol()))
        }

        return ResolutionResult.Unresolved(memberName)
    }

    /**
     * Resolves a member within a type.
     */
    private fun resolveMemberInType(
        type: KotlinType,
        memberName: String,
        scope: Scope
    ): ResolutionResult {
        return when (type) {
            is ClassType -> {
                Log.d(TAG, "resolveMemberInType: looking up class '${type.fqName}' for member '$memberName'")
                val classSymbol = findClassSymbol(type.fqName, scope)
                if (classSymbol != null) {
                    Log.d(TAG, "resolveMemberInType: found classSymbol '${classSymbol.qualifiedName}', isSynthetic=${classSymbol.isSynthetic}")
                    resolveMemberInClass(classSymbol, memberName)
                } else {
                    Log.d(TAG, "resolveMemberInType: classSymbol not found for '${type.fqName}'")
                    if ('.' !in type.fqName) {
                        Log.d(TAG, "resolveMemberInType: trying direct lookup by fqName in classpath for simple name")
                        val result = resolveMemberInClassByFqName(type.fqName, memberName)
                        if (result.isResolved) {
                            return result
                        }
                    }
                    ResolutionResult.Unresolved(memberName)
                }
            }
            is PrimitiveType -> {
                val typeName = type.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                val boxedClass = findClassSymbol("kotlin.$typeName", scope)
                if (boxedClass != null) {
                    resolveMemberInClass(boxedClass, memberName)
                } else {
                    ResolutionResult.Unresolved(memberName)
                }
            }
            is FunctionType -> {
                resolveFunctionTypeMember(type, memberName, scope)
            }
            is TypeParameter -> {
                if (type.hasBounds) {
                    val boundType = type.effectiveBound
                    resolveMemberInType(boundType, memberName, scope)
                } else {
                    resolveMemberInType(ClassType.ANY_NULLABLE, memberName, scope)
                }
            }
            else -> ResolutionResult.Unresolved(memberName)
        }
    }

    /**
     * Resolves a member within a class symbol.
     */
    private fun resolveMemberInClass(classSymbol: ClassSymbol, memberName: String): ResolutionResult {
        Log.d(TAG, "resolveMemberInClass: class=${classSymbol.qualifiedName}, member=$memberName, isSynthetic=${classSymbol.isSynthetic}")

        val directMembers = classSymbol.findMember(memberName)
        if (directMembers.isNotEmpty()) {
            Log.d(TAG, "resolveMemberInClass: found direct member")
            return ResolutionResult.Resolved(directMembers)
        }

        if (classSymbol.isSynthetic) {
            val classpathIndex = projectIndex?.getClasspathIndex()
            if (classpathIndex != null) {
                val indexedMembers = classpathIndex.findMembers(classSymbol.qualifiedName)
                    .filter { it.name == memberName }
                if (indexedMembers.isNotEmpty()) {
                    Log.d(TAG, "resolveMemberInClass: found ${indexedMembers.size} members for '$memberName' in classpath")
                    return ResolutionResult.Resolved(indexedMembers.map { it.toSyntheticSymbol() })
                }

                val indexedClass = classpathIndex.findByFqName(classSymbol.qualifiedName)
                if (indexedClass != null && indexedClass.superTypes.isNotEmpty()) {
                    Log.d(TAG, "resolveMemberInClass: searching supertypes: ${indexedClass.superTypes}")
                    for (superTypeFqName in indexedClass.superTypes) {
                        val superResult = resolveMemberInClassByFqName(superTypeFqName, memberName)
                        if (superResult is ResolutionResult.Resolved) {
                            return superResult
                        }
                    }
                }
            }

            val stdlibMembers = stdlibIndex?.findMembers(classSymbol.qualifiedName)
                ?.filter { it.name == memberName }
            if (stdlibMembers != null && stdlibMembers.isNotEmpty()) {
                Log.d(TAG, "resolveMemberInClass: found ${stdlibMembers.size} members for '$memberName' in stdlib")
                return ResolutionResult.Resolved(stdlibMembers.map { it.toSyntheticSymbol() })
            }
        }

        Log.d(TAG, "resolveMemberInClass: checking ${classSymbol.superTypes.size} supertypes for local class")
        val classpathIndex = projectIndex?.getClasspathIndex()

        for (superTypeRef in classSymbol.superTypes) {
            val simpleName = superTypeRef.simpleName
            Log.d(TAG, "resolveMemberInClass: resolving supertype '$simpleName'")

            var superTypeFqName: String? = null

            val fqNameFromImport = resolveFqNameFromImports(simpleName)
            if (fqNameFromImport != null) {
                superTypeFqName = fqNameFromImport
                Log.d(TAG, "resolveMemberInClass: resolved via import to '$superTypeFqName'")
            }

            if (superTypeFqName == null) {
                val resolvedType = context.typeResolver.resolve(superTypeRef, classSymbol.containingScope)
                if (resolvedType is ClassType && '.' in resolvedType.fqName) {
                    val typeResolverFqName = resolvedType.fqName
                    Log.d(TAG, "resolveMemberInClass: TypeResolver resolved to '$typeResolverFqName'")

                    val existsInClasspath = classpathIndex?.findByFqName(typeResolverFqName) != null
                    val existsInStdlib = stdlibIndex?.findByFqName(typeResolverFqName) != null

                    if (existsInClasspath || existsInStdlib) {
                        superTypeFqName = typeResolverFqName
                        Log.d(TAG, "resolveMemberInClass: TypeResolver result validated (classpath=$existsInClasspath, stdlib=$existsInStdlib)")
                    } else {
                        Log.d(TAG, "resolveMemberInClass: TypeResolver result '$typeResolverFqName' not found in classpath/stdlib, ignoring")
                    }
                }
            }

            if (superTypeFqName != null && '.' in superTypeFqName) {
                val superResult = resolveMemberInClassByFqName(superTypeFqName, memberName)
                if (superResult is ResolutionResult.Resolved) {
                    Log.d(TAG, "resolveMemberInClass: found '$memberName' in supertype '$superTypeFqName'")
                    return superResult
                }
            } else {
                val superClass = findClassSymbol(simpleName, classSymbol.containingScope)
                if (superClass != null) {
                    val superMember = resolveMemberInClass(superClass, memberName)
                    if (superMember is ResolutionResult.Resolved) {
                        return superMember
                    }
                }
            }
        }

        Log.d(TAG, "resolveMemberInClass: '$memberName' not found in ${classSymbol.qualifiedName}")
        return ResolutionResult.Unresolved(memberName)
    }

    private fun resolveMemberInClassByFqName(classFqName: String, memberName: String): ResolutionResult {
        Log.d(TAG, "resolveMemberInClassByFqName: class='$classFqName', member='$memberName'")

        val classpathIndex = projectIndex?.getClasspathIndex()
        if (classpathIndex != null) {
            val members = classpathIndex.findMembers(classFqName)
                .filter { it.name == memberName }
            if (members.isNotEmpty()) {
                Log.d(TAG, "resolveMemberInClassByFqName: found ${members.size} members in classpath")
                return ResolutionResult.Resolved(members.map { it.toSyntheticSymbol() })
            }

            val indexedClass = classpathIndex.findByFqName(classFqName)
            if (indexedClass != null && indexedClass.superTypes.isNotEmpty()) {
                Log.d(TAG, "resolveMemberInClassByFqName: searching supertypes: ${indexedClass.superTypes}")
                for (superTypeFqName in indexedClass.superTypes) {
                    val superResult = resolveMemberInClassByFqName(superTypeFqName, memberName)
                    if (superResult is ResolutionResult.Resolved) {
                        return superResult
                    }
                }
            }
        }

        val stdlibMembers = stdlibIndex?.findMembers(classFqName)
            ?.filter { it.name == memberName }
        if (stdlibMembers != null && stdlibMembers.isNotEmpty()) {
            Log.d(TAG, "resolveMemberInClassByFqName: found ${stdlibMembers.size} members in stdlib")
            return ResolutionResult.Resolved(stdlibMembers.map { it.toSyntheticSymbol() })
        }

        val stdlibClass = stdlibIndex?.findByFqName(classFqName)
        if (stdlibClass != null && stdlibClass.superTypes.isNotEmpty()) {
            Log.d(TAG, "resolveMemberInClassByFqName: searching stdlib supertypes: ${stdlibClass.superTypes}")
            for (superTypeFqName in stdlibClass.superTypes) {
                val superResult = resolveMemberInClassByFqName(superTypeFqName, memberName)
                if (superResult is ResolutionResult.Resolved) {
                    return superResult
                }
            }
        }

        Log.d(TAG, "resolveMemberInClassByFqName: '$memberName' not found in '$classFqName'")
        return ResolutionResult.Unresolved(memberName)
    }

    /**
     * Resolves members on function types (invoke, etc.).
     */
    private fun resolveFunctionTypeMember(
        type: FunctionType,
        memberName: String,
        scope: Scope
    ): ResolutionResult {
        return when (memberName) {
            "invoke" -> {
                ResolutionResult.Resolved(listOf(createSyntheticInvokeFunction(type)))
            }
            else -> {
                resolveMemberInType(ClassType.ANY, memberName, scope)
            }
        }
    }

    /**
     * Resolves extension functions/properties for a receiver type.
     *
     * @param receiverType The receiver type
     * @param name The extension name
     * @param scope The scope to search for extensions
     * @return List of applicable extension symbols
     */
    fun resolveExtension(
        receiverType: KotlinType,
        name: String,
        scope: Scope
    ): List<Symbol> {
        val candidates = mutableListOf<Symbol>()

        var currentScope: Scope? = scope
        while (currentScope != null) {
            val localExtensions = currentScope.resolveLocal(name)
                .filter { isApplicableExtension(it, receiverType) }
            candidates.addAll(localExtensions)
            currentScope = currentScope.parent
        }

        val importedExtensions = resolveExtensionViaImports(name, receiverType)
        candidates.addAll(importedExtensions)

        return candidates.distinctBy { it.id }
    }

    /**
     * Checks if a symbol is an extension applicable to the receiver type.
     */
    private fun isApplicableExtension(symbol: Symbol, receiverType: KotlinType): Boolean {
        val extensionReceiverRef = when (symbol) {
            is FunctionSymbol -> symbol.receiverType
            is PropertySymbol -> symbol.receiverType
            else -> return false
        } ?: return false

        val extensionReceiverType = context.typeResolver.resolve(
            extensionReceiverRef,
            symbol.containingScope
        )

        return typeChecker.isSubtypeOf(receiverType, extensionReceiverType)
    }

    /**
     * Resolves a type name to a symbol.
     *
     * @param typeName The type name (simple or qualified)
     * @param scope The scope for resolution
     * @return The resolved class, type alias, or type parameter symbol
     */
    fun resolveType(typeName: String, scope: Scope): Symbol? {
        val result = if ('.' in typeName) {
            resolveQualifiedName(typeName, scope)
        } else {
            resolveSimpleName(typeName, scope)
        }

        return when (result) {
            is ResolutionResult.Resolved -> {
                result.symbols.firstOrNull { symbol ->
                    symbol is ClassSymbol || symbol is TypeAliasSymbol || symbol is TypeParameterSymbol
                }
            }
            else -> null
        }
    }

    /**
     * Resolves function overloads for a call with given argument types.
     *
     * @param name The function name
     * @param receiverType Optional receiver type for member/extension calls
     * @param argumentTypes Types of the call arguments
     * @param scope The scope for resolution
     * @return List of applicable function symbols
     */
    fun resolveFunctionCall(
        name: String,
        receiverType: KotlinType?,
        argumentTypes: List<KotlinType>,
        scope: Scope
    ): List<FunctionSymbol> {
        Log.d(TAG, "resolveFunctionCall: name='$name', receiverType=${receiverType?.render()}, argCount=${argumentTypes.size}")

        val candidates = if (receiverType != null) {
            Log.d(TAG, "resolveFunctionCall: has receiver, calling resolveMemberAccess")
            val memberResult = resolveMemberAccess(receiverType, name, scope)
            Log.d(TAG, "resolveFunctionCall: memberResult=$memberResult")
            when (memberResult) {
                is ResolutionResult.Resolved -> memberResult.symbols.filterIsInstance<FunctionSymbol>()
                else -> emptyList()
            }
        } else {
            Log.d(TAG, "resolveFunctionCall: no receiver, calling resolveSimpleName")
            val result = resolveSimpleName(name, scope)
            when (result) {
                is ResolutionResult.Resolved -> result.symbols.filterIsInstance<FunctionSymbol>()
                else -> emptyList()
            }
        }

        Log.d(TAG, "resolveFunctionCall: got ${candidates.size} raw candidates")
        val filtered = candidates.filter { isApplicableFunction(it, argumentTypes) }
        Log.d(TAG, "resolveFunctionCall: returning ${filtered.size} applicable candidates")
        return filtered
    }

    /**
     * Checks if a function is applicable with the given argument types.
     * Returns true for candidates that might match, allowing for better error messages
     * and handling cases where argument types couldn't be fully inferred.
     */
    private fun isApplicableFunction(
        function: FunctionSymbol,
        argumentTypes: List<KotlinType>
    ): Boolean {
        val hasErrorTypes = argumentTypes.any { it is ErrorType || it.hasError }

        if (hasErrorTypes) {
            return argumentTypes.size <= function.parameterCount || function.parameters.any { it.isVararg }
        }

        if (argumentTypes.isEmpty() && function.parameterCount > 0) {
            return true
        }

        if (argumentTypes.size < function.requiredParameterCount) {
            return false
        }
        if (argumentTypes.size > function.parameterCount && !function.parameters.any { it.isVararg }) {
            return false
        }

        for ((index, argType) in argumentTypes.withIndex()) {
            val param = function.parameters.getOrNull(index) ?: continue
            val paramTypeRef = param.type ?: continue
            val paramType = context.typeResolver.resolve(paramTypeRef, function.containingScope)

            if (!typeChecker.isSubtypeOf(argType, paramType)) {
                return false
            }
        }

        return true
    }

    /**
     * Resolves via explicit imports.
     */
    private fun resolveViaImports(name: String): List<Symbol> {
        for (import in symbolTable.imports) {
            if (import.isStar) {
                continue
            }

            val importedName = import.alias ?: import.fqName.substringAfterLast('.')
            if (importedName == name) {
                return resolveImportedFqName(import.fqName)
            }
        }

        for (import in symbolTable.imports) {
            if (!import.isStar) continue

            val fqName = "${import.fqName}.$name"
            val resolved = resolveImportedFqName(fqName)
            if (resolved.isNotEmpty()) {
                return resolved
            }
        }

        return emptyList()
    }

    /**
     * Resolves via implicit imports (kotlin.*, etc.).
     */
    private fun resolveViaImplicitImports(name: String): List<Symbol> {
        for (packageName in implicitImports) {
            val fqName = "$packageName.$name"
            val resolved = resolveImportedFqName(fqName)
            if (resolved.isNotEmpty()) {
                return resolved
            }
        }
        return emptyList()
    }

    /**
     * Resolves via ProjectIndex for cross-file symbol lookup.
     * Checks same-package symbols first (visible without import),
     * then imported project symbols, then star imports.
     */
    private fun resolveViaProjectIndex(name: String): List<Symbol> {
        val index = projectIndex ?: return emptyList()
        val currentPackage = context.packageName
        val currentFilePath = context.filePath

        val samePackageSymbols = index.findByPackage(currentPackage)
            .filter { it.name == name && it.filePath != currentFilePath }
            .map { it.toSyntheticSymbol() }
        if (samePackageSymbols.isNotEmpty()) {
            return samePackageSymbols
        }

        val importedProjectSymbols = resolveImportedProjectSymbols(name, index)
        if (importedProjectSymbols.isNotEmpty()) {
            return importedProjectSymbols
        }

        for (import in symbolTable.imports.filter { it.isStar }) {
            val starImportedSymbols = index.findByPackage(import.fqName)
                .filter { it.name == name }
                .map { it.toSyntheticSymbol() }
            if (starImportedSymbols.isNotEmpty()) {
                return starImportedSymbols
            }
        }

        return emptyList()
    }

    /**
     * Resolves symbols from imports that reference project files.
     */
    private fun resolveImportedProjectSymbols(name: String, index: ProjectIndex): List<Symbol> {
        for (import in symbolTable.imports) {
            if (import.isStar) {
                val packageSymbols = index.findByPackage(import.fqName)
                    .filter { it.name == name }
                    .map { it.toSyntheticSymbol() }
                if (packageSymbols.isNotEmpty()) {
                    return packageSymbols
                }
            } else {
                val importedName = import.alias ?: import.fqName.substringAfterLast('.')
                if (importedName == name) {
                    val symbol = index.findByFqName(import.fqName)
                    if (symbol != null) {
                        return listOf(symbol.toSyntheticSymbol())
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Resolves an imported fully qualified name.
     * Searches local file scope, StdlibIndex, and ProjectIndex in order.
     */
    private fun resolveImportedFqName(fqName: String): List<Symbol> {
        val simpleName = fqName.substringAfterLast('.')

        val localResult = symbolTable.fileScope.resolve(simpleName)
            .filter { it.qualifiedName == fqName }
        if (localResult.isNotEmpty()) {
            return localResult
        }

        val projectSymbol = projectIndex?.findByFqName(fqName)
        if (projectSymbol != null) {
            return listOf(projectSymbol.toSyntheticSymbol())
        }

        val stdlibSymbol = stdlibIndex?.findByFqName(fqName)
        if (stdlibSymbol != null) {
            return listOf(stdlibSymbol.toSyntheticSymbol())
        }

        return emptyList()
    }

    /**
     * Resolves extensions via imports.
     */
    private fun resolveExtensionViaImports(name: String, receiverType: KotlinType): List<Symbol> {
        val candidates = mutableListOf<Symbol>()

        for (import in symbolTable.imports) {
            if (import.isStar) continue
            val importedName = import.alias ?: import.fqName.substringAfterLast('.')
            if (importedName != name) continue

            val resolved = resolveImportedFqName(import.fqName)
            candidates.addAll(resolved.filter { isApplicableExtension(it, receiverType) })
        }

        return candidates
    }

    /**
     * Finds a class symbol by fully qualified name.
     */
    private fun findClassSymbol(fqName: String, scope: Scope?): ClassSymbol? {
        val simpleName = fqName.substringAfterLast('.')
        Log.d(TAG, "findClassSymbol: fqName='$fqName', simpleName='$simpleName'")

        val localResult = scope?.resolve(simpleName)
            ?.filterIsInstance<ClassSymbol>()
            ?.find { it.qualifiedName == fqName || it.name == simpleName }
        if (localResult != null) {
            Log.d(TAG, "findClassSymbol: found in local scope: ${localResult.qualifiedName}")
            return localResult
        }

        val fileResult = symbolTable.fileScope.resolve(simpleName)
            .filterIsInstance<ClassSymbol>()
            .find { it.qualifiedName == fqName || it.name == simpleName }
        if (fileResult != null) {
            Log.d(TAG, "findClassSymbol: found in file scope: ${fileResult.qualifiedName}")
            return fileResult
        }

        val projectResult = projectIndex?.findByFqName(fqName)
        if (projectResult != null && projectResult.kind.isClass) {
            Log.d(TAG, "findClassSymbol: found in project index")
            return projectResult.toSyntheticSymbol() as? ClassSymbol
        }

        val classpathResult = projectIndex?.getClasspathIndex()?.findByFqName(fqName)
        if (classpathResult != null && classpathResult.kind.name.contains("CLASS")) {
            Log.d(TAG, "findClassSymbol: found in classpath index")
            return classpathResult.toSyntheticSymbol() as? ClassSymbol
        }

        val stdlibResult = stdlibIndex?.findByFqName(fqName)
        if (stdlibResult != null && stdlibResult.kind.isClass) {
            Log.d(TAG, "findClassSymbol: found in stdlib index")
            return stdlibResult.toSyntheticSymbol() as? ClassSymbol
        }

        Log.d(TAG, "findClassSymbol: not found for '$fqName'")
        return null
    }

    /**
     * Creates a synthetic invoke function for function types.
     */
    private fun createSyntheticInvokeFunction(type: FunctionType): FunctionSymbol {
        return FunctionSymbol(
            name = "invoke",
            location = SymbolLocation.SYNTHETIC,
            modifiers = Modifiers(
                isOperator = true
            ),
            containingScope = null,
            parameters = type.parameterTypes.mapIndexed { index, paramType ->
                ParameterSymbol(
                    name = "p$index",
                    location = SymbolLocation.SYNTHETIC,
                    modifiers = Modifiers.EMPTY,
                    containingScope = null,
                    type = TypeReference(paramType.render())
                )
            },
            returnType = TypeReference(type.returnType.render()),
            receiverType = null
        )
    }

    /**
     * Resolves 'this' reference in a scope.
     *
     * @param scope The current scope
     * @param label Optional label for labeled 'this'
     * @return The 'this' type, or null if not in a valid context
     */
    fun resolveThis(scope: Scope, label: String? = null): KotlinType? {
        Log.d(TAG, "resolveThis: scope.kind=${scope.kind}, scope.owner=${scope.owner?.name}, label=$label")

        if (label != null) {
            var currentScope: Scope? = scope
            while (currentScope != null) {
                val owner = currentScope.owner
                if (owner is ClassSymbol && owner.name == label) {
                    return ClassType(owner.qualifiedName)
                }
                if (owner is FunctionSymbol && owner.name == label && owner.receiverType != null) {
                    return context.typeResolver.resolve(owner.receiverType, currentScope)
                }
                currentScope = currentScope.parent
            }
            return null
        }

        val callableScope = scope.findEnclosingCallable()
        Log.d(TAG, "resolveThis: callableScope=${callableScope?.kind}, callableScope.owner=${callableScope?.owner?.name}")
        if (callableScope != null) {
            val function = callableScope.owner as? FunctionSymbol
            Log.d(TAG, "resolveThis: function=${function?.name}, receiverType=${function?.receiverType?.render()}")
            if (function?.receiverType != null) {
                return context.typeResolver.resolve(function.receiverType, callableScope)
            }
        }

        val classScope = scope.findEnclosingClass()
        Log.d(TAG, "resolveThis: classScope=${classScope?.kind}, classScope.owner=${classScope?.owner?.name}")
        if (classScope != null) {
            val classSymbol = classScope.owner as? ClassSymbol
            if (classSymbol != null) {
                return ClassType(classSymbol.qualifiedName)
            }
        }

        Log.d(TAG, "resolveThis: returning null")
        return null
    }

    /**
     * Resolves 'super' reference in a scope.
     *
     * @param scope The current scope
     * @param label Optional label for labeled 'super'
     * @return The 'super' type, or null if not in a valid context
     */
    fun resolveSuper(scope: Scope, label: String? = null): KotlinType? {
        val classScope = scope.findEnclosingClass() ?: return null
        val classSymbol = classScope.owner as? ClassSymbol ?: return null

        Log.d(TAG, "resolveSuper: class=${classSymbol.name}, superTypes=${classSymbol.superTypes.map { it.render() }}")

        if (label != null) {
            for (superTypeRef in classSymbol.superTypes) {
                val simpleName = superTypeRef.simpleName
                if (simpleName == label) {
                    val fqNameFromImport = resolveFqNameFromImports(simpleName)
                    if (fqNameFromImport != null) {
                        return ClassType(fqNameFromImport)
                    }
                    return context.typeResolver.resolve(superTypeRef, classScope)
                }
            }
            return null
        }

        val superTypes = classSymbol.superTypes
        if (superTypes.isEmpty()) {
            Log.d(TAG, "resolveSuper: no superTypes, returning Any")
            return ClassType.ANY
        }

        for (superTypeRef in superTypes) {
            val resolvedSymbol = resolveType(superTypeRef.name, classScope)
            Log.d(TAG, "resolveSuper: checking ${superTypeRef.name} -> resolved=$resolvedSymbol, isInterface=${(resolvedSymbol as? ClassSymbol)?.isInterface}")
            if (resolvedSymbol is ClassSymbol && !resolvedSymbol.isInterface) {
                Log.d(TAG, "resolveSuper: found superclass via resolveType: ${resolvedSymbol.qualifiedName}")
                return ClassType(resolvedSymbol.qualifiedName)
            }
        }

        Log.d(TAG, "resolveSuper: no local superclass found, checking imports/classpath/stdlib for ${superTypes.map { it.render() }}")

        for (superTypeRef in superTypes) {
            val simpleName = superTypeRef.simpleName
            val fqName = superTypeRef.render()

            val fqNameFromImport = resolveFqNameFromImports(simpleName)
            if (fqNameFromImport != null) {
                Log.d(TAG, "resolveSuper: resolved via import: $simpleName -> $fqNameFromImport")
                return ClassType(fqNameFromImport)
            }

            val classpathIndex = projectIndex?.getClasspathIndex()
            if (classpathIndex != null) {
                val byFqName = classpathIndex.findByFqName(fqName)
                if (byFqName != null && byFqName.kind.name.contains("CLASS")) {
                    Log.d(TAG, "resolveSuper: found in classpath by fqName: $fqName")
                    return ClassType(fqName)
                }

                val bySimpleName = classpathIndex.findBySimpleName(simpleName)
                    .filter { it.kind.name.contains("CLASS") }
                if (bySimpleName.size == 1) {
                    val found = bySimpleName.first()
                    Log.d(TAG, "resolveSuper: unique match in classpath by simpleName: ${found.fqName}")
                    return ClassType(found.fqName)
                }
            }

            val stdlibIndex = projectIndex?.getStdlibIndex()
            if (stdlibIndex != null) {
                val byFqName = stdlibIndex.findByFqName(fqName)
                if (byFqName != null && byFqName.kind == IndexedSymbolKind.CLASS) {
                    Log.d(TAG, "resolveSuper: found in stdlib by fqName: $fqName")
                    return ClassType(fqName)
                }

                val bySimpleName = stdlibIndex.findBySimpleName(simpleName)
                    .filter { it.kind == IndexedSymbolKind.CLASS }
                if (bySimpleName.size == 1) {
                    val found = bySimpleName.first()
                    Log.d(TAG, "resolveSuper: unique match in stdlib by simpleName: ${found.fqName}")
                    return ClassType(found.fqName)
                }
            }
        }

        if (superTypes.isNotEmpty()) {
            val firstSuperType = superTypes.first()
            val simpleName = firstSuperType.simpleName
            Log.d(TAG, "resolveSuper: could not fully resolve superType, returning fallback ClassType for '$simpleName'")
            return ClassType(simpleName)
        }

        Log.d(TAG, "resolveSuper: no superTypes declared, returning null")
        return null
    }

    private fun resolveFqNameFromImports(simpleName: String): String? {
        val imports = context.symbolTable.imports

        for (importInfo in imports) {
            if (!importInfo.isStar && importInfo.simpleName == simpleName) {
                Log.d(TAG, "resolveFqNameFromImports: $simpleName -> ${importInfo.fqName} (explicit import)")
                return importInfo.fqName
            }
        }

        for (importInfo in imports) {
            if (importInfo.isStar) {
                val candidateFqName = "${importInfo.fqName}.$simpleName"
                val classpathIndex = projectIndex?.getClasspathIndex()
                val stdlibIndex = projectIndex?.getStdlibIndex()

                if (classpathIndex?.findByFqName(candidateFqName) != null) {
                    Log.d(TAG, "resolveFqNameFromImports: $simpleName -> $candidateFqName (star import, found in classpath)")
                    return candidateFqName
                }
                if (stdlibIndex?.findByFqName(candidateFqName) != null) {
                    Log.d(TAG, "resolveFqNameFromImports: $simpleName -> $candidateFqName (star import, found in stdlib)")
                    return candidateFqName
                }
            }
        }

        return null
    }

    /**
     * Checks if a type is from an external library (classpath/stdlib) where
     * we may not have full method information indexed.
     *
     * This is used to suppress false positive "unresolved reference" errors
     * for member calls on external library types like AppCompatActivity.
     */
    fun isExternalLibraryType(type: KotlinType): Boolean {
        if (type !is ClassType) return false

        val fqName = type.fqName
        if ('.' !in fqName) return false

        val classpathIndex = projectIndex?.getClasspathIndex()
        if (classpathIndex?.findByFqName(fqName) != null) {
            return true
        }

        if (stdlibIndex?.findByFqName(fqName) != null) {
            return true
        }

        return false
    }

    /**
     * Resolves a reference expression node.
     *
     * @param node The syntax node representing a reference
     * @param scope The current scope
     * @return Resolution result
     */
    fun resolveReference(node: SyntaxNode, scope: Scope): ResolutionResult {
        return when (node.kind) {
            SyntaxKind.SIMPLE_IDENTIFIER -> {
                val name = node.text
                resolveSimpleName(name, scope)
            }
            SyntaxKind.NAVIGATION_EXPRESSION -> {
                resolveNavigationExpression(node, scope)
            }
            SyntaxKind.CALL_EXPRESSION -> {
                resolveCallExpression(node, scope)
            }
            else -> ResolutionResult.Unresolved(node.text)
        }
    }

    /**
     * Resolves a navigation expression (a.b.c).
     */
    private fun resolveNavigationExpression(node: SyntaxNode, scope: Scope): ResolutionResult {
        val receiver = node.childByFieldName("receiver")
            ?: return ResolutionResult.Unresolved(node.text)
        val member = node.childByFieldName("suffix")
            ?: node.children.lastOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?: return ResolutionResult.Unresolved(node.text)

        val receiverResult = resolveReference(receiver, scope)
        if (receiverResult !is ResolutionResult.Resolved) {
            return receiverResult
        }

        val memberName = member.text
        val receiverSymbol = receiverResult.symbol

        return when (receiverSymbol) {
            is ClassSymbol -> {
                resolveMemberInClass(receiverSymbol, memberName)
            }
            is PropertySymbol -> {
                val propertyType = receiverSymbol.type?.let {
                    context.typeResolver.resolve(it, scope)
                } ?: return ResolutionResult.Unresolved(memberName)
                resolveMemberAccess(propertyType, memberName, scope)
            }
            is ParameterSymbol -> {
                val paramType = receiverSymbol.type?.let {
                    context.typeResolver.resolve(it, scope)
                } ?: return ResolutionResult.Unresolved(memberName)
                resolveMemberAccess(paramType, memberName, scope)
            }
            else -> ResolutionResult.Unresolved(memberName)
        }
    }

    /**
     * Resolves a call expression.
     */
    private fun resolveCallExpression(node: SyntaxNode, scope: Scope): ResolutionResult {
        val callee = node.childByFieldName("function")
            ?: node.children.firstOrNull { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            ?: node.children.firstOrNull { it.kind == SyntaxKind.NAVIGATION_EXPRESSION }
            ?: return ResolutionResult.Unresolved(node.text)

        return resolveReference(callee, scope)
    }

}
