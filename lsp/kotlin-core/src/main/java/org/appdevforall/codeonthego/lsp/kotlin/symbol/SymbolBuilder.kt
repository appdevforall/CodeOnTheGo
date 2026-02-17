package org.appdevforall.codeonthego.lsp.kotlin.symbol

import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxKind
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxNode
import org.appdevforall.codeonthego.lsp.kotlin.parser.SyntaxTree

/**
 * Builds symbol tables from parsed syntax trees.
 *
 * SymbolBuilder traverses a [SyntaxTree] and extracts all declarations,
 * creating [Symbol] objects and organizing them into [Scope] hierarchies.
 *
 * ## Two-Pass Resolution
 *
 * Symbol building uses two passes:
 * 1. **Declaration Pass**: Register all declared names in their scopes
 * 2. **Resolution Pass**: Resolve type references and build relationships
 *
 * This two-pass approach handles forward references correctly.
 *
 * ## Usage
 *
 * ```kotlin
 * val parser = KotlinParser()
 * val result = parser.parse(source)
 * val table = SymbolBuilder.build(result.tree, filePath)
 * ```
 *
 * @see SymbolTable
 * @see Symbol
 * @see Scope
 */
class SymbolBuilder private constructor(
    private val tree: SyntaxTree,
    private val filePath: String
) {
    private val fileScope = Scope(ScopeKind.FILE, range = tree.root.range)
    private var currentScope = fileScope
    private var packageName = ""

    private val symbolTable: SymbolTable by lazy {
        SymbolTable(filePath, packageName, fileScope, tree)
    }

    /**
     * Builds the symbol table from the syntax tree.
     */
    private fun build(): SymbolTable {
        val root = tree.root
        android.util.Log.d("SymbolBuilder", "build(): rootKind=${root.kind}, hasError=${root.hasError}, childCount=${root.childCount}, namedChildCount=${root.namedChildCount}")
        if (root.hasError) {
            android.util.Log.d("SymbolBuilder", "Tree has errors, S-expr (first 500 chars): ${root.toSexp().take(500)}")
        }
        when (root.kind) {
            SyntaxKind.SOURCE_FILE -> processSourceFile(root)
            else -> {
                android.util.Log.d("SymbolBuilder", "  root is not SOURCE_FILE, processing children directly")
                for (child in root.namedChildren) {
                    when (child.kind) {
                        SyntaxKind.PACKAGE_HEADER -> processPackageHeader(child)
                        SyntaxKind.IMPORT_LIST -> processImportList(child)
                        SyntaxKind.CLASS_DECLARATION -> processClassDeclaration(child)
                        SyntaxKind.OBJECT_DECLARATION -> processObjectDeclaration(child)
                        SyntaxKind.INTERFACE_DECLARATION -> processInterfaceDeclaration(child)
                        SyntaxKind.FUNCTION_DECLARATION -> processFunctionDeclaration(child)
                        SyntaxKind.PROPERTY_DECLARATION -> processPropertyDeclaration(child)
                        SyntaxKind.TYPE_ALIAS -> processTypeAlias(child)
                        else -> {}
                    }
                }
            }
        }
        return symbolTable
    }

    private fun processSourceFile(node: SyntaxNode) {
        android.util.Log.d("SymbolBuilder", "processSourceFile: namedChildCount=${node.namedChildren.size}")
        for (child in node.namedChildren) {
            android.util.Log.d("SymbolBuilder", "  SOURCE_FILE child: kind=${child.kind}, type=${child.type}, text='${child.text.take(40)}', hasError=${child.hasError}")
            when (child.kind) {
                SyntaxKind.PACKAGE_HEADER -> processPackageHeader(child)
                SyntaxKind.IMPORT_LIST -> processImportList(child)
                SyntaxKind.CLASS_DECLARATION -> processClassDeclaration(child)
                SyntaxKind.OBJECT_DECLARATION -> processObjectDeclaration(child)
                SyntaxKind.INTERFACE_DECLARATION -> processInterfaceDeclaration(child)
                SyntaxKind.FUNCTION_DECLARATION -> processFunctionDeclaration(child)
                SyntaxKind.PROPERTY_DECLARATION -> processPropertyDeclaration(child)
                SyntaxKind.TYPE_ALIAS -> processTypeAlias(child)
                SyntaxKind.ERROR -> {
                    android.util.Log.d("SymbolBuilder", "    ERROR node, attempting to process children")
                    processErrorNodeChildren(child)
                }
                else -> {
                    android.util.Log.d("SymbolBuilder", "    unhandled kind: ${child.kind}")
                }
            }
        }
    }

    private fun processErrorNodeChildren(node: SyntaxNode) {
        if (tryRecoverClassFromErrorNode(node)) {
            return
        }

        for (child in node.namedChildren) {
            android.util.Log.d("SymbolBuilder", "    ERROR child: kind=${child.kind}, text='${child.text.take(40)}'")
            when (child.kind) {
                SyntaxKind.CLASS_DECLARATION -> processClassDeclaration(child)
                SyntaxKind.OBJECT_DECLARATION -> processObjectDeclaration(child)
                SyntaxKind.INTERFACE_DECLARATION -> processInterfaceDeclaration(child)
                SyntaxKind.FUNCTION_DECLARATION -> processFunctionDeclaration(child)
                SyntaxKind.PROPERTY_DECLARATION -> processPropertyDeclaration(child)
                SyntaxKind.TYPE_ALIAS -> processTypeAlias(child)
                SyntaxKind.ERROR -> processErrorNodeChildren(child)
                else -> {
                    if (child.namedChildren.isNotEmpty()) {
                        processErrorNodeChildren(child)
                    }
                }
            }
        }
    }

    private fun tryRecoverClassFromErrorNode(node: SyntaxNode): Boolean {
        val text = node.text
        android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: text.length=${text.length}, first100='${text.take(100)}'")

        val classMatch = CLASS_PATTERN.find(text)
        val interfaceMatch = INTERFACE_PATTERN.find(text)
        val objectMatch = OBJECT_PATTERN.find(text)

        android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: classMatch=${classMatch != null}, interfaceMatch=${interfaceMatch != null}, objectMatch=${objectMatch != null}")

        when {
            classMatch != null -> {
                val className = classMatch.groupValues[1]
                android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: found class '$className' in regex")
                val existingSymbol = currentScope.resolveFirst(className)
                if (existingSymbol is ClassSymbol) {
                    android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: class '$className' already exists, processing children in its scope")
                    val classScope = existingSymbol.memberScope
                    if (classScope != null) {
                        withScope(classScope, existingSymbol) {
                            tryRecoverClassBodyFromErrorNode(node)
                        }
                        return true
                    }
                    return false
                }
                android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: recovering class '$className'")
                var superTypes = extractSuperTypesFromErrorNode(node)
                if (superTypes.isEmpty()) {
                    android.util.Log.d("SymbolBuilder", "  no supertypes from children, trying text extraction")
                    superTypes = extractSuperTypesFromText(text, classMatch.range.last + 1)
                }
                android.util.Log.d("SymbolBuilder", "  superTypes: ${superTypes.map { it.render() }}")
                createRecoveredClassSymbol(node, className, ClassKind.CLASS, superTypes)
                return true
            }
            interfaceMatch != null -> {
                val interfaceName = interfaceMatch.groupValues[1]
                val existingSymbol = currentScope.resolveFirst(interfaceName)
                if (existingSymbol is ClassSymbol) {
                    android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: interface '$interfaceName' already exists, processing children in its scope")
                    val interfaceScope = existingSymbol.memberScope
                    if (interfaceScope != null) {
                        withScope(interfaceScope, existingSymbol) {
                            tryRecoverClassBodyFromErrorNode(node)
                        }
                        return true
                    }
                    return false
                }
                android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: recovering interface '$interfaceName'")
                var superTypes = extractSuperTypesFromErrorNode(node)
                if (superTypes.isEmpty()) {
                    superTypes = extractSuperTypesFromText(text, interfaceMatch.range.last + 1)
                }
                createRecoveredClassSymbol(node, interfaceName, ClassKind.INTERFACE, superTypes)
                return true
            }
            objectMatch != null -> {
                val objectName = objectMatch.groupValues[1]
                val existingSymbol = currentScope.resolveFirst(objectName)
                if (existingSymbol is ClassSymbol) {
                    android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: object '$objectName' already exists, processing children in its scope")
                    val objectScope = existingSymbol.memberScope
                    if (objectScope != null) {
                        withScope(objectScope, existingSymbol) {
                            tryRecoverClassBodyFromErrorNode(node)
                        }
                        return true
                    }
                    return false
                }
                android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: recovering object '$objectName'")
                createRecoveredClassSymbol(node, objectName, ClassKind.OBJECT, emptyList())
                return true
            }
        }
        android.util.Log.d("SymbolBuilder", "tryRecoverClassFromErrorNode: no match found, returning false")
        return false
    }

    private fun extractSuperTypesFromErrorNode(node: SyntaxNode): List<TypeReference> {
        val superTypes = mutableListOf<TypeReference>()

        for (child in node.namedChildren) {
            when (child.kind) {
                SyntaxKind.DELEGATION_SPECIFIER,
                SyntaxKind.ANNOTATED_DELEGATION_SPECIFIER,
                SyntaxKind.CONSTRUCTOR_INVOCATION -> {
                    val type = extractTypeFromDelegationSpecifier(child)
                    if (type != null) {
                        android.util.Log.d("SymbolBuilder", "extractSuperTypesFromErrorNode: found supertype '${type.render()}' from ${child.kind}")
                        superTypes.add(type)
                    }
                }
                SyntaxKind.USER_TYPE, SyntaxKind.SIMPLE_USER_TYPE -> {
                    val type = extractType(child)
                    if (type != null) {
                        android.util.Log.d("SymbolBuilder", "extractSuperTypesFromErrorNode: found supertype '${type.render()}' from USER_TYPE")
                        superTypes.add(type)
                    }
                }
                SyntaxKind.DELEGATION_SPECIFIERS -> {
                    val nestedTypes = extractSuperTypes(child)
                    android.util.Log.d("SymbolBuilder", "extractSuperTypesFromErrorNode: found ${nestedTypes.size} supertypes from DELEGATION_SPECIFIERS")
                    superTypes.addAll(nestedTypes)
                }
                else -> {}
            }
        }

        return superTypes
    }

    private fun extractSuperTypesFromText(text: String, startIndex: Int): List<TypeReference> {
        val afterName = text.substring(startIndex.coerceAtMost(text.length))
        val colonIndex = afterName.indexOf(':')
        if (colonIndex < 0) return emptyList()

        val afterColon = afterName.substring(colonIndex + 1)
        val braceIndex = afterColon.indexOf('{')
        val supertypesPart = if (braceIndex >= 0) afterColon.substring(0, braceIndex) else afterColon

        return SUPERTYPE_PATTERN.findAll(supertypesPart)
            .mapNotNull { match ->
                val typeName = match.groupValues[1].trim()
                if (typeName.isNotEmpty() && !typeName.all { it == '.' }) TypeReference(typeName) else null
            }
            .toList()
    }

    private fun createRecoveredClassSymbol(node: SyntaxNode, name: String, kind: ClassKind, superTypes: List<TypeReference>) {
        android.util.Log.d("SymbolBuilder", "createRecoveredClassSymbol: name='$name', kind=$kind")
        val classScope = currentScope.createChild(
            kind = ScopeKind.CLASS,
            range = node.range
        )

        val classSymbol = ClassSymbol(
            name = name,
            location = createLocation(node, node),
            modifiers = Modifiers.EMPTY,
            containingScope = currentScope,
            kind = kind,
            superTypes = superTypes,
            memberScope = classScope
        )

        currentScope.define(classSymbol)
        symbolTable.registerSymbol(classSymbol)

        withScope(classScope, classSymbol) {
            tryRecoverClassBodyFromErrorNode(node)
        }

        android.util.Log.d("SymbolBuilder", "createRecoveredClassSymbol: finished, classScope has ${classScope.allSymbols.size} symbols: ${classScope.allSymbols.map { it.name }}")
    }

    private fun tryRecoverClassBodyFromErrorNode(node: SyntaxNode) {
        android.util.Log.d("SymbolBuilder", "tryRecoverClassBodyFromErrorNode: processing ${node.namedChildren.size} children, currentScope=${currentScope.kind}")
        for (child in node.namedChildren) {
            android.util.Log.d("SymbolBuilder", "  recovery child: kind=${child.kind}, text='${child.text.take(40)}'")
            when (child.kind) {
                SyntaxKind.CLASS_BODY -> {
                    android.util.Log.d("SymbolBuilder", "    -> processing CLASS_BODY")
                    processClassBody(child)
                }
                SyntaxKind.FUNCTION_DECLARATION -> {
                    android.util.Log.d("SymbolBuilder", "    -> processing FUNCTION_DECLARATION in recovery mode")
                    val func = processFunctionDeclaration(child)
                    if (func != null) {
                        android.util.Log.d("SymbolBuilder", "    -> created function '${func.name}' with body scope, symbols: ${func.bodyScope?.allSymbols?.map { it.name }}")
                    }
                }
                SyntaxKind.PROPERTY_DECLARATION -> {
                    android.util.Log.d("SymbolBuilder", "    -> processing PROPERTY_DECLARATION in recovery mode")
                    val prop = processPropertyDeclaration(child)
                    if (prop != null) {
                        android.util.Log.d("SymbolBuilder", "    -> created property '${prop.name}' in class scope")
                    }
                }
                SyntaxKind.COMPANION_OBJECT -> processObjectDeclaration(child)
                else -> {
                    if (child.namedChildren.isNotEmpty()) {
                        tryRecoverClassBodyFromErrorNode(child)
                    }
                }
            }
        }
    }

    private fun processPackageHeader(node: SyntaxNode) {
        val identifier = node.childByFieldName("identifier")
            ?: node.findChild(SyntaxKind.IDENTIFIER)
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)

        packageName = extractQualifiedName(identifier) ?: ""

        if (packageName.isNotEmpty()) {
            val packageSymbol = PackageSymbol(
                name = packageName.substringAfterLast('.'),
                location = createLocation(node, identifier),
                packageName = packageName
            )
            symbolTable.registerSymbol(packageSymbol)
        }
    }

    private fun processImportList(node: SyntaxNode) {
        val imports = mutableListOf<ImportInfo>()

        for (child in node.namedChildren) {
            if (child.kind == SyntaxKind.IMPORT_HEADER) {
                val import = processImportHeader(child)
                if (import != null) {
                    imports.add(import)
                }
            }
        }

        symbolTable.imports = imports
    }

    private fun processImportHeader(node: SyntaxNode): ImportInfo? {
        val identifier = node.childByFieldName("identifier")
            ?: node.findChild(SyntaxKind.IDENTIFIER)
            ?: return null

        val fqName = extractQualifiedName(identifier) ?: return null
        val isStar = node.children.any { it.text == "*" }

        val aliasNode = node.findChild(SyntaxKind.IMPORT_ALIAS)
        val alias = aliasNode?.findChild(SyntaxKind.SIMPLE_IDENTIFIER)?.text

        android.util.Log.d("SymbolBuilder", "[IMPORT-DEBUG] import fqName='$fqName', nodeRange=${node.range}, nodeText='${node.text.take(80)}'")

        return ImportInfo(
            fqName = fqName,
            alias = alias,
            isStar = isStar,
            range = node.range
        )
    }

    private fun processClassDeclaration(node: SyntaxNode): ClassSymbol? {
        android.util.Log.d("SymbolBuilder", "processClassDeclaration: text='${node.text.take(60)}', hasError=${node.hasError}")
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.findChild(SyntaxKind.TYPE_IDENTIFIER)

        if (nameNode == null) {
            android.util.Log.d("SymbolBuilder", "  no name found, children: ${node.namedChildren.map { "${it.kind}='${it.text.take(20)}'" }}")
            return null
        }

        val name = nameNode.text
        android.util.Log.d("SymbolBuilder", "  found class name: $name")
        android.util.Log.d("SymbolBuilder", "  all children: ${node.children.map { "${it.kind}(${it.type})" }}")
        val modifiers = extractModifiers(node)
        val kind = determineClassKind(node, modifiers)
        val typeParameters = extractTypeParameters(node)
        android.util.Log.d("SymbolBuilder", "  about to call extractSuperTypes")
        val superTypes = extractSuperTypes(node)
        android.util.Log.d("SymbolBuilder", "  superTypes extracted: ${superTypes.map { it.render() }}")

        val classScope = currentScope.createChild(
            kind = ScopeKind.CLASS,
            range = node.range
        )

        val primaryConstructor = extractPrimaryConstructor(node, classScope)

        val classSymbol = ClassSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            kind = kind,
            typeParameters = typeParameters,
            superTypes = superTypes,
            memberScope = classScope,
            primaryConstructor = primaryConstructor
        )

        currentScope.define(classSymbol)
        symbolTable.registerSymbol(classSymbol)

        val thisSymbol = PropertySymbol(
            name = "this",
            location = createLocation(node, nameNode),
            modifiers = Modifiers.EMPTY,
            containingScope = classScope,
            type = TypeReference(name, typeParameters.map { TypeReference(it.name, emptyList()) }),
            isVar = false
        )
        classScope.define(thisSymbol)

        withScope(classScope, classSymbol) {
            processClassBody(node.childByFieldName("body") ?: node.findChild(SyntaxKind.CLASS_BODY))
        }

        return classSymbol
    }

    private fun processObjectDeclaration(node: SyntaxNode): ClassSymbol? {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: return null

        val name = nameNode.text
        val modifiers = extractModifiers(node)
        val kind = if (node.kind == SyntaxKind.COMPANION_OBJECT) {
            ClassKind.COMPANION_OBJECT
        } else {
            ClassKind.OBJECT
        }

        val objectScope = currentScope.createChild(
            kind = if (kind == ClassKind.COMPANION_OBJECT) ScopeKind.COMPANION else ScopeKind.CLASS,
            range = node.range
        )

        val objectSymbol = ClassSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers.copy(isCompanion = kind == ClassKind.COMPANION_OBJECT),
            containingScope = currentScope,
            kind = kind,
            memberScope = objectScope
        )

        currentScope.define(objectSymbol)
        symbolTable.registerSymbol(objectSymbol)

        val thisSymbol = PropertySymbol(
            name = "this",
            location = createLocation(node, nameNode),
            modifiers = Modifiers.EMPTY,
            containingScope = objectScope,
            type = TypeReference(name, emptyList()),
            isVar = false
        )
        objectScope.define(thisSymbol)

        withScope(objectScope, objectSymbol) {
            processClassBody(node.childByFieldName("body") ?: node.findChild(SyntaxKind.CLASS_BODY))
        }

        return objectSymbol
    }

    private fun processInterfaceDeclaration(node: SyntaxNode): ClassSymbol? {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.findChild(SyntaxKind.TYPE_IDENTIFIER)
            ?: return null

        val name = nameNode.text
        val modifiers = extractModifiers(node).copy(modality = Modality.ABSTRACT)
        val typeParameters = extractTypeParameters(node)
        val superTypes = extractSuperTypes(node)

        val interfaceScope = currentScope.createChild(
            kind = ScopeKind.CLASS,
            range = node.range
        )

        val interfaceSymbol = ClassSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            kind = ClassKind.INTERFACE,
            typeParameters = typeParameters,
            superTypes = superTypes,
            memberScope = interfaceScope
        )

        currentScope.define(interfaceSymbol)
        symbolTable.registerSymbol(interfaceSymbol)

        val thisSymbol = PropertySymbol(
            name = "this",
            location = createLocation(node, nameNode),
            modifiers = Modifiers.EMPTY,
            containingScope = interfaceScope,
            type = TypeReference(name, typeParameters.map { TypeReference(it.name, emptyList()) }),
            isVar = false
        )
        interfaceScope.define(thisSymbol)

        withScope(interfaceScope, interfaceSymbol) {
            processClassBody(node.childByFieldName("body") ?: node.findChild(SyntaxKind.CLASS_BODY))
        }

        return interfaceSymbol
    }

    private fun processClassBody(node: SyntaxNode?) {
        node ?: return

        android.util.Log.d("SymbolBuilder", "processClassBody: currentScope=${currentScope.kind}, range=${currentScope.range}")
        for (child in node.namedChildren) {
            android.util.Log.d("SymbolBuilder", "  classBody child: kind=${child.kind}, text='${child.text.take(30)}', range=${child.range}")
            when (child.kind) {
                SyntaxKind.FUNCTION_DECLARATION -> processFunctionDeclaration(child)
                SyntaxKind.PROPERTY_DECLARATION -> processPropertyDeclaration(child)
                SyntaxKind.CLASS_DECLARATION -> processClassDeclaration(child)
                SyntaxKind.OBJECT_DECLARATION -> processObjectDeclaration(child)
                SyntaxKind.COMPANION_OBJECT -> processObjectDeclaration(child)
                SyntaxKind.INTERFACE_DECLARATION -> processInterfaceDeclaration(child)
                SyntaxKind.SECONDARY_CONSTRUCTOR -> processSecondaryConstructor(child)
                SyntaxKind.ANONYMOUS_INITIALIZER -> processInitBlock(child)
                SyntaxKind.ENUM_CLASS_BODY -> processEnumClassBody(child)
                else -> {}
            }
        }
    }

    private fun processEnumClassBody(node: SyntaxNode) {
        for (child in node.namedChildren) {
            when (child.kind) {
                SyntaxKind.ENUM_ENTRY -> processEnumEntry(child)
                SyntaxKind.FUNCTION_DECLARATION -> processFunctionDeclaration(child)
                SyntaxKind.PROPERTY_DECLARATION -> processPropertyDeclaration(child)
                else -> {}
            }
        }
    }

    private fun processEnumEntry(node: SyntaxNode): ClassSymbol? {
        val nameNode = node.findChild(SyntaxKind.SIMPLE_IDENTIFIER) ?: return null
        val name = nameNode.text

        val entrySymbol = ClassSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = Modifiers.EMPTY,
            containingScope = currentScope,
            kind = ClassKind.ENUM_ENTRY
        )

        currentScope.define(entrySymbol)
        symbolTable.registerSymbol(entrySymbol)

        return entrySymbol
    }

    private fun processFunctionDeclaration(node: SyntaxNode): FunctionSymbol? {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: return null

        val name = nameNode.text
        android.util.Log.d("SymbolBuilder", "processFunctionDeclaration: name=$name, range=${node.range}")
        val modifiers = extractModifiers(node)
        val typeParameters = extractTypeParameters(node)
        val receiverType = extractReceiverType(node)
        val returnType = extractReturnType(node)

        val functionScope = currentScope.createChild(
            kind = ScopeKind.FUNCTION,
            range = node.range
        )
        android.util.Log.d("SymbolBuilder", "  created functionScope: range=${functionScope.range}")

        val parameters = mutableListOf<ParameterSymbol>()

        withScope(functionScope, null) {
            val paramsNode = node.childByFieldName("parameters")
                ?: node.findChild(SyntaxKind.FUNCTION_VALUE_PARAMETERS)
            paramsNode?.let { processParameters(it, parameters) }
        }

        val functionSymbol = FunctionSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            parameters = parameters,
            typeParameters = typeParameters,
            returnType = returnType,
            receiverType = receiverType,
            bodyScope = functionScope
        )

        currentScope.define(functionSymbol)
        symbolTable.registerSymbol(functionSymbol)

        android.util.Log.d("SymbolBuilder", "  processing body with functionScope, kind=${functionScope.kind}")
        withScope(functionScope, functionSymbol) {
            val body = node.childByFieldName("body")
                ?: node.findChild(SyntaxKind.FUNCTION_BODY)
                ?: node.findChild(SyntaxKind.CONTROL_STRUCTURE_BODY)
            android.util.Log.d("SymbolBuilder", "  body node: kind=${body?.kind}, text='${body?.text?.take(50)}'")
            processBody(body)
        }
        android.util.Log.d("SymbolBuilder", "  after processBody, functionScope symbols: ${functionScope.allSymbols.map { it.name }}")

        return functionSymbol
    }

    private fun processSecondaryConstructor(node: SyntaxNode): FunctionSymbol? {
        val modifiers = extractModifiers(node)

        val constructorScope = currentScope.createChild(
            kind = ScopeKind.CONSTRUCTOR,
            range = node.range
        )

        val parameters = mutableListOf<ParameterSymbol>()

        withScope(constructorScope, null) {
            val paramsNode = node.findChild(SyntaxKind.FUNCTION_VALUE_PARAMETERS)
            paramsNode?.let { processParameters(it, parameters) }
        }

        val constructorSymbol = FunctionSymbol(
            name = "<init>",
            location = createLocation(node, node),
            modifiers = modifiers,
            containingScope = currentScope,
            parameters = parameters,
            isConstructor = true,
            bodyScope = constructorScope
        )

        currentScope.define(constructorSymbol)
        symbolTable.registerSymbol(constructorSymbol)

        return constructorSymbol
    }

    private fun processInitBlock(node: SyntaxNode) {
        val initScope = currentScope.createChild(
            kind = ScopeKind.BLOCK,
            range = node.range
        )

        withScope(initScope, null) {
            processBody(node.findChild(SyntaxKind.STATEMENTS))
        }
    }

    private fun processPropertyDeclaration(node: SyntaxNode): PropertySymbol? {
        android.util.Log.d("SymbolBuilder", "processPropertyDeclaration: text='${node.text.take(50)}', range=${node.range}")
        android.util.Log.d("SymbolBuilder", "  currentScope=${currentScope.kind}, scopeRange=${currentScope.range}")

        val multiVarDecl = node.findChild(SyntaxKind.MULTI_VARIABLE_DECLARATION)
        if (multiVarDecl != null) {
            processDestructuringDeclaration(node, multiVarDecl)
            return null
        }

        var nameNode = node.childByFieldName("name")
        android.util.Log.d("SymbolBuilder", "  childByFieldName('name')=${nameNode?.text}")

        if (nameNode == null) {
            nameNode = node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            android.util.Log.d("SymbolBuilder", "  findChild(SIMPLE_IDENTIFIER)=${nameNode?.text}")
        }

        if (nameNode == null) {
            nameNode = extractPropertyName(node)
            android.util.Log.d("SymbolBuilder", "  extractPropertyName()=${nameNode?.text}")
        }

        if (nameNode == null) {
            val children = node.children
            android.util.Log.d("SymbolBuilder", "  children: ${children.map { "${it.kind}='${it.text.take(10)}'" }}")
            val valVarIndex = children.indexOfFirst { it.kind == SyntaxKind.VAL || it.kind == SyntaxKind.VAR }
            if (valVarIndex >= 0 && valVarIndex + 1 < children.size) {
                val candidate = children[valVarIndex + 1]
                android.util.Log.d("SymbolBuilder", "  fallback candidate: kind=${candidate.kind}, text='${candidate.text}'")
                val isValidIdentifier = candidate.text.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
                if (isValidIdentifier && (candidate.kind == SyntaxKind.UNKNOWN || candidate.kind == SyntaxKind.SIMPLE_IDENTIFIER)) {
                    nameNode = candidate
                }
            }
        }

        if (nameNode == null) {
            val multiVarFallback = node.children.find { it.text.startsWith("(") && it.text.endsWith(")") }
            if (multiVarFallback != null) {
                processDestructuringFromParenthesized(node, multiVarFallback)
                return null
            }

            val allIdentifiers = node.traverse().filter { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }.toList()
            android.util.Log.d("SymbolBuilder", "  deep search identifiers: ${allIdentifiers.map { it.text }}")
            if (allIdentifiers.isNotEmpty()) {
                nameNode = allIdentifiers.first()
            }
        }

        android.util.Log.d("SymbolBuilder", "  final nameNode: text='${nameNode?.text}', kind=${nameNode?.kind}")
        if (nameNode == null) return null

        val name = nameNode.text
        val modifiers = extractModifiers(node)
        var typeNode = node.childByFieldName("type")
            ?: node.findChild(SyntaxKind.NULLABLE_TYPE)
            ?: node.findChild(SyntaxKind.USER_TYPE)

        if (typeNode == null) {
            val varDecl = node.findChild(SyntaxKind.VARIABLE_DECLARATION)
            if (varDecl != null) {
                android.util.Log.d("SymbolBuilder", "  looking in VARIABLE_DECLARATION: ${varDecl.text}")
                android.util.Log.d("SymbolBuilder", "    varDecl children: ${varDecl.children.map { "${it.kind}='${it.text.take(20)}'" }}")
                typeNode = varDecl.findChild(SyntaxKind.NULLABLE_TYPE)
                    ?: varDecl.findChild(SyntaxKind.USER_TYPE)
                    ?: varDecl.findChild(SyntaxKind.FUNCTION_TYPE)
                android.util.Log.d("SymbolBuilder", "    typeNode from varDecl: ${typeNode?.kind}='${typeNode?.text}'")
            }
        }

        var type = extractType(typeNode)
        val receiverType = extractReceiverType(node)
        val isVar = node.children.any { it.kind == SyntaxKind.VAR }
        val hasInitializer = node.childByFieldName("value") != null ||
                node.children.any { it.text == "=" }
        val isDelegated = node.children.any { it.kind == SyntaxKind.BY }

        if (type == null && hasInitializer) {
            val initializerExpr = findInitializerExpression(node)
            android.util.Log.d("SymbolBuilder", "  no explicit type, inferring from initializer: ${initializerExpr?.kind}")
            type = inferTypeFromExpression(initializerExpr)
            android.util.Log.d("SymbolBuilder", "  inferred type: ${type?.render()}")
        }

        val getter = node.findChild(SyntaxKind.GETTER)?.let { processAccessor(it, "get", receiverType) }
        val setter = node.findChild(SyntaxKind.SETTER)?.let { processAccessor(it, "set", receiverType) }

        android.util.Log.d("SymbolBuilder", "[VAL-CHECK] Creating PropertySymbol: name='$name', isVar=$isVar, scope=${currentScope.kind}")
        val propertySymbol = PropertySymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            type = type,
            receiverType = receiverType,
            getter = getter,
            setter = setter,
            isVar = isVar,
            hasInitializer = hasInitializer,
            isDelegated = isDelegated
        )

        currentScope.define(propertySymbol)
        symbolTable.registerSymbol(propertySymbol)
        android.util.Log.d("SymbolBuilder", "[VAL-CHECK] PropertySymbol defined in scope: ${currentScope.allSymbols.map { "${it.name}(${(it as? PropertySymbol)?.isVar ?: "not-prop"})" }}")

        return propertySymbol
    }

    private fun findInitializerExpression(node: SyntaxNode): SyntaxNode? {
        val valueNode = node.childByFieldName("value")
        if (valueNode != null) return valueNode

        val children = node.children
        val equalsIndex = children.indexOfFirst { it.text == "=" }
        if (equalsIndex >= 0 && equalsIndex + 1 < children.size) {
            return children[equalsIndex + 1]
        }
        return null
    }

    private fun inferTypeFromExpression(node: SyntaxNode?): TypeReference? {
        node ?: return null

        return when (node.kind) {
            SyntaxKind.INTEGER_LITERAL,
            SyntaxKind.HEX_LITERAL,
            SyntaxKind.BIN_LITERAL -> TypeReference.INT

            SyntaxKind.LONG_LITERAL -> TypeReference("Long")

            SyntaxKind.REAL_LITERAL -> {
                val text = node.text
                if (text.endsWith("f", ignoreCase = true)) {
                    TypeReference("Float")
                } else {
                    TypeReference("Double")
                }
            }

            SyntaxKind.BOOLEAN_LITERAL -> TypeReference.BOOLEAN

            SyntaxKind.CHARACTER_LITERAL -> TypeReference("Char")

            SyntaxKind.STRING_LITERAL,
            SyntaxKind.LINE_STRING_LITERAL,
            SyntaxKind.MULTI_LINE_STRING_LITERAL -> TypeReference.STRING

            SyntaxKind.NULL_LITERAL -> TypeReference.ANY_NULLABLE

            SyntaxKind.COLLECTION_LITERAL -> inferCollectionLiteralType(node)

            SyntaxKind.CALL_EXPRESSION -> inferCallExpressionType(node)

            SyntaxKind.PARENTHESIZED_EXPRESSION -> {
                val inner = node.namedChildren.firstOrNull()
                inferTypeFromExpression(inner)
            }

            SyntaxKind.IF_EXPRESSION -> inferIfExpressionType(node)

            SyntaxKind.WHEN_EXPRESSION -> inferWhenExpressionType(node)

            else -> {
                val firstNamedChild = node.namedChildren.firstOrNull()
                if (firstNamedChild != null && firstNamedChild.kind != node.kind) {
                    inferTypeFromExpression(firstNamedChild)
                } else {
                    null
                }
            }
        }
    }

    private fun inferCollectionLiteralType(node: SyntaxNode): TypeReference {
        val elements = node.namedChildren
        if (elements.isEmpty()) {
            return TypeReference.generic("List", TypeReference.ANY)
        }
        val firstElementType = inferTypeFromExpression(elements.first())
        return TypeReference.generic("List", firstElementType ?: TypeReference.ANY)
    }

    private fun inferCallExpressionType(node: SyntaxNode): TypeReference? {
        val calleeNode = node.namedChildren.firstOrNull() ?: return null
        val calleeName = when (calleeNode.kind) {
            SyntaxKind.SIMPLE_IDENTIFIER -> calleeNode.text
            SyntaxKind.NAVIGATION_EXPRESSION -> {
                calleeNode.namedChildren.lastOrNull()?.text
            }
            else -> calleeNode.text
        }

        return when (calleeName) {
            "listOf", "mutableListOf", "arrayListOf" -> {
                val elementType = inferCallArgumentType(node)
                TypeReference.generic("List", elementType ?: TypeReference.ANY)
            }
            "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf" -> {
                val elementType = inferCallArgumentType(node)
                TypeReference.generic("Set", elementType ?: TypeReference.ANY)
            }
            "mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf" -> {
                TypeReference.generic("Map", TypeReference.ANY, TypeReference.ANY)
            }
            "arrayOf" -> {
                val elementType = inferCallArgumentType(node)
                TypeReference.generic("Array", elementType ?: TypeReference.ANY)
            }
            "intArrayOf" -> TypeReference("IntArray")
            "longArrayOf" -> TypeReference("LongArray")
            "floatArrayOf" -> TypeReference("FloatArray")
            "doubleArrayOf" -> TypeReference("DoubleArray")
            "booleanArrayOf" -> TypeReference("BooleanArray")
            "charArrayOf" -> TypeReference("CharArray")
            "byteArrayOf" -> TypeReference("ByteArray")
            "shortArrayOf" -> TypeReference("ShortArray")
            "emptyList" -> TypeReference.generic("List", TypeReference.ANY)
            "emptySet" -> TypeReference.generic("Set", TypeReference.ANY)
            "emptyMap" -> TypeReference.generic("Map", TypeReference.ANY, TypeReference.ANY)
            else -> null
        }
    }

    private fun inferCallArgumentType(node: SyntaxNode): TypeReference? {
        val callSuffix = node.findChild(SyntaxKind.CALL_SUFFIX) ?: return null
        val valueArguments = callSuffix.findChild(SyntaxKind.VALUE_ARGUMENTS) ?: return null
        val firstArg = valueArguments.namedChildren.firstOrNull() ?: return null
        val argValue = firstArg.findChild(SyntaxKind.VALUE_ARGUMENT)?.namedChildren?.firstOrNull()
            ?: firstArg.namedChildren.firstOrNull()
            ?: return null
        return inferTypeFromExpression(argValue)
    }

    private fun inferIfExpressionType(node: SyntaxNode): TypeReference? {
        val thenBranch = node.childByFieldName("consequence")
        return inferTypeFromExpression(thenBranch?.namedChildren?.lastOrNull())
    }

    private fun inferWhenExpressionType(node: SyntaxNode): TypeReference? {
        val firstEntry = node.findChild(SyntaxKind.WHEN_ENTRY)
        val bodyNode = firstEntry?.childByFieldName("body")
        return inferTypeFromExpression(bodyNode?.namedChildren?.lastOrNull())
    }

    private fun processDestructuringDeclaration(propertyNode: SyntaxNode, multiVarDecl: SyntaxNode) {
        android.util.Log.d("SymbolBuilder", "processDestructuringDeclaration: ${multiVarDecl.text}")

        val isVar = propertyNode.children.any { it.kind == SyntaxKind.VAR }
        val modifiers = extractModifiers(propertyNode)

        val variableNodes = multiVarDecl.namedChildren.filter {
            it.kind == SyntaxKind.VARIABLE_DECLARATION ||
            it.kind == SyntaxKind.SIMPLE_IDENTIFIER
        }

        for ((index, varNode) in variableNodes.withIndex()) {
            val nameNode = if (varNode.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                varNode
            } else {
                varNode.findChild(SyntaxKind.SIMPLE_IDENTIFIER) ?: continue
            }

            val name = nameNode.text
            if (name == "_") continue

            val typeNode = varNode.findChild(SyntaxKind.USER_TYPE)
            val type = extractType(typeNode)

            val propertySymbol = PropertySymbol(
                name = name,
                location = createLocation(varNode, nameNode),
                modifiers = modifiers,
                containingScope = currentScope,
                type = type,
                isVar = isVar,
                hasInitializer = true
            )

            currentScope.define(propertySymbol)
            symbolTable.registerSymbol(propertySymbol)
            android.util.Log.d("SymbolBuilder", "  created destructured var: $name")
        }
    }

    private fun processDestructuringFromParenthesized(propertyNode: SyntaxNode, parenNode: SyntaxNode) {
        android.util.Log.d("SymbolBuilder", "processDestructuringFromParenthesized: ${parenNode.text}")

        val isVar = propertyNode.children.any { it.kind == SyntaxKind.VAR }
        val modifiers = extractModifiers(propertyNode)

        val identifiers = parenNode.traverse()
            .filter { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
            .toList()

        for (nameNode in identifiers) {
            val name = nameNode.text
            if (name == "_") continue

            val propertySymbol = PropertySymbol(
                name = name,
                location = createLocation(nameNode, nameNode),
                modifiers = modifiers,
                containingScope = currentScope,
                type = null,
                isVar = isVar,
                hasInitializer = true
            )

            currentScope.define(propertySymbol)
            symbolTable.registerSymbol(propertySymbol)
            android.util.Log.d("SymbolBuilder", "  created destructured var: $name")
        }
    }

    private fun extractPropertyName(node: SyntaxNode): SyntaxNode? {
        for (child in node.namedChildren) {
            if (child.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                return child
            }
        }
        return null
    }

    private fun processAccessor(node: SyntaxNode, name: String, receiverType: TypeReference? = null): FunctionSymbol {
        val modifiers = extractModifiers(node)

        val accessorScope = currentScope.createChild(
            kind = ScopeKind.ACCESSOR,
            range = node.range
        )

        val accessorSymbol = FunctionSymbol(
            name = name,
            location = createLocation(node, node),
            modifiers = modifiers,
            containingScope = currentScope,
            bodyScope = accessorScope,
            receiverType = receiverType
        )

        accessorScope.owner = accessorSymbol

        val body = node.childByFieldName("body")
            ?: node.findChild(SyntaxKind.FUNCTION_BODY)
            ?: node.findChild(SyntaxKind.CONTROL_STRUCTURE_BODY)
            ?: node.namedChildren.find { it.kind != SyntaxKind.MODIFIER && it.kind != SyntaxKind.GET && it.kind != SyntaxKind.SET }

        if (body != null) {
            withScope(accessorScope, accessorSymbol) {
                processBody(body)
            }
        }

        return accessorSymbol
    }

    private fun processTypeAlias(node: SyntaxNode): TypeAliasSymbol? {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.findChild(SyntaxKind.TYPE_IDENTIFIER)
            ?: return null

        val name = nameNode.text
        val modifiers = extractModifiers(node)
        val typeParameters = extractTypeParameters(node)
        val underlyingType = extractType(node.childByFieldName("type"))

        val typeAliasSymbol = TypeAliasSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            typeParameters = typeParameters,
            underlyingType = underlyingType
        )

        currentScope.define(typeAliasSymbol)
        symbolTable.registerSymbol(typeAliasSymbol)

        return typeAliasSymbol
    }

    private fun processParameters(node: SyntaxNode, parameters: MutableList<ParameterSymbol>) {
        var nextIsVararg = false
        for (child in node.children) {
            when {
                child.kind == SyntaxKind.PARAMETER_MODIFIER && child.text == "vararg" -> {
                    nextIsVararg = true
                }
                child.text == "vararg" -> {
                    nextIsVararg = true
                }
                child.kind == SyntaxKind.PARAMETER || child.kind == SyntaxKind.CLASS_PARAMETER -> {
                    val param = processParameter(child, forceVararg = nextIsVararg)
                    if (param != null) {
                        parameters.add(param)
                        currentScope.define(param)
                        symbolTable.registerSymbol(param)
                    }
                    nextIsVararg = false
                }
            }
        }
    }

    private fun processParameter(node: SyntaxNode, forceVararg: Boolean = false): ParameterSymbol? {
        val nameNode = node.childByFieldName("name")
            ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: return null

        val name = nameNode.text
        android.util.Log.d("SymbolBuilder", "processParameter: name=$name")
        android.util.Log.d("SymbolBuilder", "  node children: ${node.children.map { "${it.kind}='${it.text.take(30)}'" }}")
        val modifiers = extractModifiers(node)

        val typeNode = node.childByFieldName("type")
        android.util.Log.d("SymbolBuilder", "  typeNode from childByFieldName: ${typeNode?.kind}='${typeNode?.text}'")
        var type = extractType(typeNode)
        android.util.Log.d("SymbolBuilder", "  extracted type: ${type?.render()}")

        if (type == null) {
            val funcType = typeNode?.findChild(SyntaxKind.FUNCTION_TYPE)
                ?: node.findChild(SyntaxKind.FUNCTION_TYPE)
            if (funcType != null) {
                android.util.Log.d("SymbolBuilder", "  found FUNCTION_TYPE directly: ${funcType.text}")
                type = extractType(funcType)
                android.util.Log.d("SymbolBuilder", "  extracted function type: ${type?.render()}")
            }
        }

        if (type == null && typeNode != null) {
            android.util.Log.d("SymbolBuilder", "  type null, checking typeNode children: ${typeNode.namedChildren.map { "${it.kind}='${it.text.take(30)}'" }}")
            val userType = typeNode.findChild(SyntaxKind.USER_TYPE)
                ?: typeNode.findChild(SyntaxKind.SIMPLE_USER_TYPE)
            if (userType != null) {
                type = extractType(userType)
            }
        }

        if (type == null) {
            val userType = node.findChild(SyntaxKind.USER_TYPE)
                ?: node.findChild(SyntaxKind.SIMPLE_USER_TYPE)
            if (userType != null) {
                type = extractType(userType)
            }
        }

        val hasDefault = node.childByFieldName("default_value") != null ||
                node.children.any { it.text == "=" }

        val isVararg = forceVararg || modifiers.isVararg

        return ParameterSymbol(
            name = name,
            location = createLocation(node, nameNode),
            modifiers = modifiers,
            containingScope = currentScope,
            type = type,
            hasDefaultValue = hasDefault,
            isVararg = isVararg,
            isCrossinline = modifiers.isCrossinline,
            isNoinline = modifiers.isNoinline
        )
    }

    private fun extractPrimaryConstructor(node: SyntaxNode, classScope: Scope): FunctionSymbol? {
        val primaryConstructorNode = node.findChild(SyntaxKind.PRIMARY_CONSTRUCTOR)
        val paramsNode = primaryConstructorNode?.findChild(SyntaxKind.CLASS_PARAMETER)
            ?: node.findChild(SyntaxKind.CLASS_PARAMETER)
            ?: return null

        val modifiers = primaryConstructorNode?.let { extractModifiers(it) } ?: Modifiers.EMPTY

        val parameters = mutableListOf<ParameterSymbol>()

        val tempScope = currentScope
        currentScope = classScope

        val actualParamsNode = primaryConstructorNode ?: node
        for (child in actualParamsNode.namedChildren) {
            if (child.kind == SyntaxKind.CLASS_PARAMETER) {
                val param = processParameter(child)
                if (param != null) {
                    parameters.add(param)
                    classScope.define(param)
                    symbolTable.registerSymbol(param)

                    if (child.children.any { it.kind == SyntaxKind.VAL || it.kind == SyntaxKind.VAR }) {
                        val isVar = child.children.any { it.kind == SyntaxKind.VAR }
                        val property = PropertySymbol(
                            name = param.name,
                            location = param.location,
                            modifiers = param.modifiers,
                            containingScope = classScope,
                            type = param.type,
                            isVar = isVar,
                            hasInitializer = true
                        )
                        classScope.define(property)
                        symbolTable.registerSymbol(property)
                    }
                }
            }
        }

        currentScope = tempScope

        return FunctionSymbol(
            name = "<init>",
            location = createLocation(primaryConstructorNode ?: node, primaryConstructorNode ?: node),
            modifiers = modifiers,
            containingScope = classScope,
            parameters = parameters,
            isConstructor = true,
            isPrimaryConstructor = true
        )
    }

    private fun processBody(node: SyntaxNode?) {
        node ?: return

        android.util.Log.d("SymbolBuilder", "processBody: node.kind=${node.kind}, currentScope=${currentScope.kind}, range=${currentScope.range}")
        val children = when (node.kind) {
            SyntaxKind.FUNCTION_BODY -> {
                val statements = node.findChild(SyntaxKind.STATEMENTS)
                android.util.Log.d("SymbolBuilder", "  FUNCTION_BODY: statements=${statements?.kind}, childCount=${statements?.namedChildren?.size ?: node.namedChildren.size}")
                statements?.namedChildren ?: node.namedChildren
            }
            SyntaxKind.CONTROL_STRUCTURE_BODY -> {
                val statements = node.findChild(SyntaxKind.STATEMENTS)
                android.util.Log.d("SymbolBuilder", "  CONTROL_STRUCTURE_BODY: statements=${statements?.kind}, childCount=${statements?.namedChildren?.size ?: node.namedChildren.size}")
                statements?.namedChildren ?: node.namedChildren
            }
            else -> node.namedChildren
        }

        for (child in children) {
            android.util.Log.d("SymbolBuilder", "  body child: kind=${child.kind}, text='${child.text.take(40)}', range=${child.range}")
            when (child.kind) {
                SyntaxKind.PROPERTY_DECLARATION -> {
                    android.util.Log.d("SymbolBuilder", "    -> processing PROPERTY_DECLARATION in scope ${currentScope.kind}")
                    val prop = processPropertyDeclaration(child)
                    android.util.Log.d("SymbolBuilder", "    -> prop result: name=${prop?.name}, currentScope symbols after=${currentScope.allSymbols.map { it.name }}")
                }
                SyntaxKind.FOR_STATEMENT -> processForStatement(child)
                SyntaxKind.IF_EXPRESSION -> processIfExpression(child)
                SyntaxKind.WHEN_EXPRESSION -> processWhenExpression(child)
                SyntaxKind.TRY_EXPRESSION -> processTryExpression(child)
                SyntaxKind.LAMBDA_LITERAL -> processLambda(child)
                else -> {
                    android.util.Log.d("SymbolBuilder", "    -> else branch, recursing into ${child.kind}")
                    processBody(child)
                }
            }
        }
    }

    private fun processForStatement(node: SyntaxNode) {
        val loopScope = currentScope.createChild(ScopeKind.FOR_LOOP, range = node.range)

        withScope(loopScope, null) {
            val loopVariable = node.childByFieldName("loop_parameter")
                ?: node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)

            if (loopVariable != null) {
                val param = ParameterSymbol(
                    name = loopVariable.text,
                    location = createLocation(loopVariable, loopVariable),
                    modifiers = Modifiers.EMPTY,
                    containingScope = currentScope
                )
                currentScope.define(param)
                symbolTable.registerSymbol(param)
            }

            processBody(node.childByFieldName("body"))
        }
    }

    private fun processIfExpression(node: SyntaxNode) {
        val thenBranch = node.childByFieldName("consequence")
        val elseBranch = node.childByFieldName("alternative")

        if (thenBranch != null) {
            val thenScope = currentScope.createChild(ScopeKind.BLOCK, range = thenBranch.range)
            withScope(thenScope, null) {
                processBody(thenBranch)
            }
        }

        if (elseBranch != null) {
            val elseScope = currentScope.createChild(ScopeKind.BLOCK, range = elseBranch.range)
            withScope(elseScope, null) {
                processBody(elseBranch)
            }
        }
    }

    private fun processWhenExpression(node: SyntaxNode) {
        for (entry in node.findChildren(SyntaxKind.WHEN_ENTRY)) {
            val entryScope = currentScope.createChild(ScopeKind.WHEN_ENTRY, range = entry.range)
            withScope(entryScope, null) {
                processBody(entry.childByFieldName("body"))
            }
        }
    }

    private fun processTryExpression(node: SyntaxNode) {
        val tryBody = node.childByFieldName("body")
        if (tryBody != null) {
            val tryScope = currentScope.createChild(ScopeKind.BLOCK, range = tryBody.range)
            withScope(tryScope, null) {
                processBody(tryBody)
            }
        }

        for (catchBlock in node.findChildren(SyntaxKind.CATCH_BLOCK)) {
            val catchScope = currentScope.createChild(ScopeKind.CATCH, range = catchBlock.range)
            withScope(catchScope, null) {
                val exceptionParam = catchBlock.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                if (exceptionParam != null) {
                    val param = ParameterSymbol(
                        name = exceptionParam.text,
                        location = createLocation(exceptionParam, exceptionParam),
                        modifiers = Modifiers.EMPTY,
                        containingScope = currentScope
                    )
                    currentScope.define(param)
                    symbolTable.registerSymbol(param)
                }
                processBody(catchBlock.childByFieldName("body"))
            }
        }

        val finallyBlock = node.findChild(SyntaxKind.FINALLY_BLOCK)
        if (finallyBlock != null) {
            val finallyScope = currentScope.createChild(ScopeKind.BLOCK, range = finallyBlock.range)
            withScope(finallyScope, null) {
                processBody(finallyBlock.childByFieldName("body"))
            }
        }
    }

    private fun processLambda(node: SyntaxNode) {
        val lambdaScope = currentScope.createChild(ScopeKind.LAMBDA, range = node.range)

        withScope(lambdaScope, null) {
            val params = node.findChild(SyntaxKind.LAMBDA_PARAMETERS)
            if (params != null) {
                for (child in params.namedChildren) {
                    if (child.kind == SyntaxKind.PARAMETER ||
                        child.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                        val nameNode = if (child.kind == SyntaxKind.PARAMETER) {
                            child.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                        } else {
                            child
                        }
                        if (nameNode != null) {
                            val param = ParameterSymbol(
                                name = nameNode.text,
                                location = createLocation(child, nameNode),
                                modifiers = Modifiers.EMPTY,
                                containingScope = currentScope
                            )
                            currentScope.define(param)
                            symbolTable.registerSymbol(param)
                        }
                    }
                }
            }

            processBody(node.findChild(SyntaxKind.STATEMENTS))
        }
    }

    private fun extractModifiers(node: SyntaxNode): Modifiers {
        val modifiersNode = node.findChild(SyntaxKind.MODIFIERS) ?: return Modifiers.EMPTY

        var visibility = Visibility.DEFAULT
        var modality = Modality.DEFAULT
        var isInline = false
        var isSuspend = false
        var isTailrec = false
        var isOperator = false
        var isInfix = false
        var isExternal = false
        var isConst = false
        var isLateInit = false
        var isData = false
        var isValue = false
        var isInner = false
        var isCompanion = false
        var isAnnotation = false
        var isEnum = false
        var isVararg = false
        var isCrossinline = false
        var isNoinline = false
        var isReified = false
        var isOverride = false
        var isExpect = false
        var isActual = false

        for (child in modifiersNode.traverse()) {
            when (child.kind) {
                SyntaxKind.VISIBILITY_MODIFIER -> {
                    visibility = when (child.text) {
                        "public" -> Visibility.PUBLIC
                        "private" -> Visibility.PRIVATE
                        "protected" -> Visibility.PROTECTED
                        "internal" -> Visibility.INTERNAL
                        else -> Visibility.DEFAULT
                    }
                }
                SyntaxKind.INHERITANCE_MODIFIER -> {
                    modality = when (child.text) {
                        "final" -> Modality.FINAL
                        "open" -> Modality.OPEN
                        "abstract" -> Modality.ABSTRACT
                        "sealed" -> Modality.SEALED
                        else -> Modality.DEFAULT
                    }
                }
                SyntaxKind.FUNCTION_MODIFIER -> {
                    when (child.text) {
                        "inline" -> isInline = true
                        "suspend" -> isSuspend = true
                        "tailrec" -> isTailrec = true
                        "operator" -> isOperator = true
                        "infix" -> isInfix = true
                        "external" -> isExternal = true
                    }
                }
                SyntaxKind.PROPERTY_MODIFIER -> {
                    if (child.text == "const") isConst = true
                }
                SyntaxKind.MEMBER_MODIFIER -> {
                    when (child.text) {
                        "override" -> isOverride = true
                        "lateinit" -> isLateInit = true
                    }
                }
                SyntaxKind.CLASS_MODIFIER -> {
                    when (child.text) {
                        "data" -> isData = true
                        "value" -> isValue = true
                        "inner" -> isInner = true
                        "annotation" -> isAnnotation = true
                        "enum" -> isEnum = true
                        "sealed" -> modality = Modality.SEALED
                    }
                }
                SyntaxKind.PARAMETER_MODIFIER -> {
                    when (child.text) {
                        "vararg" -> isVararg = true
                        "crossinline" -> isCrossinline = true
                        "noinline" -> isNoinline = true
                    }
                }
                SyntaxKind.TYPE_PARAMETER_MODIFIER -> {
                    if (child.text == "reified") isReified = true
                }
                SyntaxKind.PLATFORM_MODIFIER -> {
                    when (child.text) {
                        "expect" -> isExpect = true
                        "actual" -> isActual = true
                    }
                }
                else -> {}
            }
        }

        return Modifiers(
            visibility = visibility,
            modality = modality,
            isInline = isInline,
            isSuspend = isSuspend,
            isTailrec = isTailrec,
            isOperator = isOperator,
            isInfix = isInfix,
            isExternal = isExternal,
            isConst = isConst,
            isLateInit = isLateInit,
            isData = isData,
            isValue = isValue,
            isInner = isInner,
            isCompanion = isCompanion,
            isAnnotation = isAnnotation,
            isEnum = isEnum,
            isVararg = isVararg,
            isCrossinline = isCrossinline,
            isNoinline = isNoinline,
            isReified = isReified,
            isOverride = isOverride,
            isExpect = isExpect,
            isActual = isActual
        )
    }

    private fun extractTypeParameters(node: SyntaxNode): List<TypeParameterSymbol> {
        val typeParamsNode = node.findChild(SyntaxKind.TYPE_PARAMETERS) ?: return emptyList()

        return typeParamsNode.namedChildren
            .filter { it.kind == SyntaxKind.TYPE_PARAMETER }
            .mapNotNull { paramNode ->
                val nameNode = paramNode.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                    ?: paramNode.findChild(SyntaxKind.TYPE_IDENTIFIER)
                    ?: return@mapNotNull null

                val bounds = extractTypeBounds(paramNode)
                val variance = extractVariance(paramNode)
                val isReified = paramNode.traverse().any { it.text == "reified" }

                TypeParameterSymbol(
                    name = nameNode.text,
                    location = createLocation(paramNode, nameNode),
                    modifiers = Modifiers(isReified = isReified),
                    containingScope = currentScope,
                    bounds = bounds,
                    variance = variance,
                    isReified = isReified
                )
            }
    }

    private fun extractTypeBounds(node: SyntaxNode): List<TypeReference> {
        val bounds = mutableListOf<TypeReference>()

        for (child in node.namedChildren) {
            if (child.kind == SyntaxKind.USER_TYPE ||
                child.kind == SyntaxKind.NULLABLE_TYPE) {
                val type = extractType(child)
                if (type != null) bounds.add(type)
            }
        }

        return bounds
    }

    private fun extractVariance(node: SyntaxNode): Variance {
        for (child in node.children) {
            if (child.kind == SyntaxKind.VARIANCE_MODIFIER) {
                return Variance.fromKeyword(child.text)
            }
            if (child.text == "in") return Variance.IN
            if (child.text == "out") return Variance.OUT
        }
        return Variance.INVARIANT
    }

    private fun extractSuperTypes(node: SyntaxNode): List<TypeReference> {
        android.util.Log.d("SymbolBuilder", "extractSuperTypes: node.kind=${node.kind}")

        val results = mutableListOf<TypeReference>()

        val delegationSpecifiers = node.findChild(SyntaxKind.DELEGATION_SPECIFIERS)
        if (delegationSpecifiers != null) {
            android.util.Log.d("SymbolBuilder", "  found DELEGATION_SPECIFIERS wrapper")
            delegationSpecifiers.namedChildren
                .filter { it.kind == SyntaxKind.DELEGATION_SPECIFIER ||
                        it.kind == SyntaxKind.ANNOTATED_DELEGATION_SPECIFIER ||
                        it.kind == SyntaxKind.CONSTRUCTOR_INVOCATION ||
                        it.kind == SyntaxKind.USER_TYPE }
                .mapNotNull { spec -> extractTypeFromDelegationSpecifier(spec) }
                .let { results.addAll(it) }
        }

        val directSpecifiers = node.children.filter {
            it.kind == SyntaxKind.DELEGATION_SPECIFIER ||
            it.kind == SyntaxKind.ANNOTATED_DELEGATION_SPECIFIER ||
            it.kind == SyntaxKind.CONSTRUCTOR_INVOCATION
        }

        if (directSpecifiers.isNotEmpty()) {
            android.util.Log.d("SymbolBuilder", "  found ${directSpecifiers.size} direct delegation specifiers")
            directSpecifiers.mapNotNull { spec -> extractTypeFromDelegationSpecifier(spec) }
                .let { results.addAll(it) }
        }

        android.util.Log.d("SymbolBuilder", "  extracted superTypes: ${results.map { it.render() }}")
        return results
    }

    private fun extractTypeFromDelegationSpecifier(spec: SyntaxNode): TypeReference? {
        val typeNode = spec.findChild(SyntaxKind.USER_TYPE)
            ?: spec.findChild(SyntaxKind.CONSTRUCTOR_INVOCATION)?.findChild(SyntaxKind.USER_TYPE)
            ?: if (spec.kind == SyntaxKind.USER_TYPE) spec else null

        if (typeNode == null) {
            for (child in spec.children) {
                if (child.kind == SyntaxKind.USER_TYPE || child.kind == SyntaxKind.SIMPLE_USER_TYPE) {
                    return extractType(child)
                }
                if (child.kind == SyntaxKind.SIMPLE_IDENTIFIER || child.kind == SyntaxKind.TYPE_IDENTIFIER) {
                    return TypeReference(child.text, range = child.range)
                }
            }
            val firstIdent = spec.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                ?: spec.findChild(SyntaxKind.TYPE_IDENTIFIER)
            if (firstIdent != null) {
                return TypeReference(firstIdent.text, range = firstIdent.range)
            }
        }

        return extractType(typeNode)
    }

    private fun extractReceiverType(node: SyntaxNode): TypeReference? {
        android.util.Log.d("SymbolBuilder", "extractReceiverType: node.kind=${node.kind}, text='${node.text.take(60)}'")
        android.util.Log.d("SymbolBuilder", "  namedChildren: ${node.namedChildren.map { "${it.kind}='${it.text.take(20)}'" }}")
        android.util.Log.d("SymbolBuilder", "  children: ${node.children.map { "${it.kind}='${it.text.take(20)}'" }}")

        var receiverNode = node.findChild(SyntaxKind.RECEIVER_TYPE)
        android.util.Log.d("SymbolBuilder", "  findChild(RECEIVER_TYPE)=$receiverNode")

        if (receiverNode == null) {
            for (child in node.namedChildren) {
                android.util.Log.d("SymbolBuilder", "    checking child: ${child.kind}, text='${child.text.take(30)}'")
                if (child.kind == SyntaxKind.USER_TYPE || child.kind == SyntaxKind.SIMPLE_USER_TYPE) {
                    val nextSibling = node.namedChildren.getOrNull(node.namedChildren.indexOf(child) + 1)
                    if (nextSibling?.kind == SyntaxKind.SIMPLE_IDENTIFIER || nextSibling?.text == ".") {
                        android.util.Log.d("SymbolBuilder", "    found potential receiver: ${child.text}")
                        val type = extractType(child)
                        if (type != null) {
                            android.util.Log.d("SymbolBuilder", "    returning receiver type: ${type.render()}")
                            return type
                        }
                    }
                }
            }

            val children = node.children
            for (i in children.indices) {
                val child = children[i]
                if (child.text == ".") {
                    if (i > 0) {
                        val prevChild = children[i - 1]
                        android.util.Log.d("SymbolBuilder", "    found dot, prevChild: ${prevChild.kind}='${prevChild.text.take(30)}'")
                        if (prevChild.kind == SyntaxKind.USER_TYPE ||
                            prevChild.kind == SyntaxKind.SIMPLE_USER_TYPE ||
                            prevChild.kind == SyntaxKind.NULLABLE_TYPE) {
                            val type = extractType(prevChild)
                            if (type != null) {
                                android.util.Log.d("SymbolBuilder", "    returning receiver from dot: ${type.render()}")
                                return type
                            }
                        }
                        if (prevChild.kind == SyntaxKind.SIMPLE_IDENTIFIER || prevChild.kind == SyntaxKind.TYPE_IDENTIFIER) {
                            val type = TypeReference(prevChild.text, range = prevChild.range)
                            android.util.Log.d("SymbolBuilder", "    returning receiver from identifier: ${type.render()}")
                            return type
                        }
                    }
                }
            }

            android.util.Log.d("SymbolBuilder", "  no receiver found")
            return null
        }

        android.util.Log.d("SymbolBuilder", "  receiverNode children: ${receiverNode.namedChildren.map { "${it.kind}='${it.text.take(20)}'" }}")

        val type = extractType(receiverNode.findChild(SyntaxKind.USER_TYPE)
            ?: receiverNode.findChild(SyntaxKind.NULLABLE_TYPE)
            ?: receiverNode.findChild(SyntaxKind.PARENTHESIZED_TYPE)
            ?: receiverNode.namedChildren.firstOrNull())
        android.util.Log.d("SymbolBuilder", "  extracted type: ${type?.render()}")
        return type
    }

    private fun extractReturnType(node: SyntaxNode): TypeReference? {
        val typeNode = node.childByFieldName("return_type")
            ?: node.namedChildren.find {
                it.kind == SyntaxKind.USER_TYPE ||
                        it.kind == SyntaxKind.NULLABLE_TYPE ||
                        it.kind == SyntaxKind.FUNCTION_TYPE
            }

        return extractType(typeNode)
    }

    private fun extractType(node: SyntaxNode?): TypeReference? {
        node ?: return null

        android.util.Log.d("SymbolBuilder", "extractType: node.kind=${node.kind}, text='${node.text.take(50)}'")
        return try {
            when (node.kind) {
                SyntaxKind.USER_TYPE,
                SyntaxKind.SIMPLE_USER_TYPE -> {
                    val name = extractTypeName(node)
                    android.util.Log.d("SymbolBuilder", "  USER_TYPE: name=$name")
                    if (name.isEmpty()) return null
                    val typeArgs = extractTypeArguments(node)
                    TypeReference(name, typeArgs, range = node.range)
                }

                SyntaxKind.NULLABLE_TYPE -> {
                    val inner = node.namedChildren.firstOrNull()
                    android.util.Log.d("SymbolBuilder", "  NULLABLE_TYPE: inner=${inner?.kind}")
                    val innerType = extractType(inner)
                    innerType?.nullable()
                }

                SyntaxKind.FUNCTION_TYPE -> {
                    android.util.Log.d("SymbolBuilder", "  FUNCTION_TYPE: extracting...")
                    extractFunctionType(node)
                }

                SyntaxKind.PARENTHESIZED_TYPE -> {
                    extractType(node.namedChildren.firstOrNull())
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTypeName(node: SyntaxNode): String {
        val parts = mutableListOf<String>()

        fun collectParts(n: SyntaxNode) {
            for (child in n.namedChildren) {
                when (child.kind) {
                    SyntaxKind.SIMPLE_USER_TYPE -> {
                        val id = child.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
                            ?: child.findChild(SyntaxKind.TYPE_IDENTIFIER)
                        if (id != null) parts.add(id.text)
                    }
                    SyntaxKind.SIMPLE_IDENTIFIER,
                    SyntaxKind.TYPE_IDENTIFIER -> {
                        parts.add(child.text)
                    }
                    else -> collectParts(child)
                }
            }
        }

        val directId = node.findChild(SyntaxKind.SIMPLE_IDENTIFIER)
            ?: node.findChild(SyntaxKind.TYPE_IDENTIFIER)
        if (directId != null && node.kind == SyntaxKind.SIMPLE_USER_TYPE) {
            parts.add(directId.text)
        } else {
            collectParts(node)
        }

        return parts.joinToString(".")
    }

    private fun extractTypeArguments(node: SyntaxNode): List<TypeReference> {
        val typeArgsNode = node.findChild(SyntaxKind.TYPE_ARGUMENTS)
            ?: return emptyList()

        return typeArgsNode.namedChildren
            .filter { it.kind == SyntaxKind.TYPE_PROJECTION }
            .mapNotNull { proj ->
                extractType(proj.namedChildren.firstOrNull())
            }
    }

    private fun extractFunctionType(node: SyntaxNode): TypeReference {
        android.util.Log.d("SymbolBuilder", "extractFunctionType: node=${node.text}")
        android.util.Log.d("SymbolBuilder", "  namedChildren: ${node.namedChildren.map { "${it.kind}='${it.text.take(20)}'" }}")

        val receiverType = node.findChild(SyntaxKind.RECEIVER_TYPE)?.let {
            android.util.Log.d("SymbolBuilder", "  found RECEIVER_TYPE: ${it.text}")
            extractType(it.namedChildren.firstOrNull())
        }
        android.util.Log.d("SymbolBuilder", "  receiverType: ${receiverType?.render()}")

        val paramTypes = node.findChild(SyntaxKind.FUNCTION_TYPE_PARAMETERS)
            ?.namedChildren
            ?.mapNotNull { extractType(it) }
            ?: emptyList()
        android.util.Log.d("SymbolBuilder", "  paramTypes: ${paramTypes.map { it.render() }}")

        val returnType = node.namedChildren.lastOrNull()?.let { extractType(it) }
            ?: TypeReference.UNIT
        android.util.Log.d("SymbolBuilder", "  returnType: ${returnType.render()}")

        val result = TypeReference.functionType(
            receiverType = receiverType,
            parameterTypes = paramTypes,
            returnType = returnType,
            range = node.range
        )
        android.util.Log.d("SymbolBuilder", "  result: ${result.render()}")
        return result
    }

    private fun extractQualifiedName(node: SyntaxNode?): String? {
        node ?: return null

        return when (node.kind) {
            SyntaxKind.IDENTIFIER -> {
                node.namedChildren
                    .filter { it.kind == SyntaxKind.SIMPLE_IDENTIFIER }
                    .joinToString(".") { it.text }
            }
            SyntaxKind.SIMPLE_IDENTIFIER -> node.text
            else -> {
                val parts = mutableListOf<String>()
                for (child in node.traverse()) {
                    if (child.kind == SyntaxKind.SIMPLE_IDENTIFIER) {
                        parts.add(child.text)
                    }
                }
                if (parts.isEmpty()) null else parts.joinToString(".")
            }
        }
    }

    private fun determineClassKind(node: SyntaxNode, modifiers: Modifiers): ClassKind {
        return when {
            modifiers.isEnum -> ClassKind.ENUM_CLASS
            modifiers.isAnnotation -> ClassKind.ANNOTATION_CLASS
            modifiers.isData -> ClassKind.DATA_CLASS
            modifiers.isValue -> ClassKind.VALUE_CLASS
            else -> ClassKind.CLASS
        }
    }

    private fun createLocation(node: SyntaxNode, nameNode: SyntaxNode?): SymbolLocation {
        return SymbolLocation(
            filePath = filePath,
            range = node.range,
            nameRange = nameNode?.range ?: node.range
        )
    }

    private inline fun withScope(scope: Scope, owner: Symbol?, block: () -> Unit) {
        val previousScope = currentScope
        currentScope = scope
        if (owner != null) {
            scope.owner = owner
        }
        try {
            block()
        } finally {
            currentScope = previousScope
        }
    }

    companion object {
        private val CLASS_PATTERN = Regex(
            """(?:public\s+|private\s+|internal\s+|protected\s+|open\s+|abstract\s+|sealed\s+|data\s+|inner\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val INTERFACE_PATTERN = Regex(
            """(?:public\s+|private\s+|internal\s+|protected\s+|sealed\s+)*interface\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val OBJECT_PATTERN = Regex(
            """(?:public\s+|private\s+|internal\s+|protected\s+)*object\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val SUPERTYPE_PATTERN = Regex("""([A-Za-z_][A-Za-z0-9_.]*)\s*(?:\([^)]*\))?""")

        fun build(tree: SyntaxTree, filePath: String): SymbolTable {
            return SymbolBuilder(tree, filePath).build()
        }
    }
}
