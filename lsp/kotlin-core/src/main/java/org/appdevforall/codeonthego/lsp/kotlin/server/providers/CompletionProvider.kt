package org.appdevforall.codeonthego.lsp.kotlin.server.providers

import org.appdevforall.codeonthego.lsp.kotlin.index.ClasspathIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbol
import org.appdevforall.codeonthego.lsp.kotlin.index.IndexedSymbolKind
import org.appdevforall.codeonthego.lsp.kotlin.index.ProjectIndex
import org.appdevforall.codeonthego.lsp.kotlin.index.StdlibIndex
import org.appdevforall.codeonthego.lsp.kotlin.parser.KotlinParser
import org.appdevforall.codeonthego.lsp.kotlin.parser.Position
import org.appdevforall.codeonthego.lsp.kotlin.semantic.AnalysisContext
import org.appdevforall.codeonthego.lsp.kotlin.server.AnalysisScheduler
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentManager
import org.appdevforall.codeonthego.lsp.kotlin.server.DocumentState
import org.appdevforall.codeonthego.lsp.kotlin.symbol.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provides code completion suggestions.
 */
class CompletionProvider(
    private val documentManager: DocumentManager,
    private val projectIndex: ProjectIndex,
    private val analysisScheduler: AnalysisScheduler
) {
    private val completionParser = KotlinParser()

    fun provideCompletions(
        uri: String,
        line: Int,
        character: Int,
        context: CompletionContext?
    ): CompletionList {
        val state = documentManager.get(uri) ?: return CompletionList(false, emptyList())

        val content = state.content
        val triggerChar = context?.triggerCharacter

        val patchResult = analyzeWithCompletionPatch(state, uri)
        val symbolTable = patchResult.first
        val analysisContext = patchResult.second

        val completionContext = analyzeCompletionContext(content, line, character, triggerChar)

        val items = when (completionContext) {
            is CompletionKind.MemberAccess -> provideMemberCompletions(
                completionContext.receiver,
                completionContext.prefix,
                symbolTable,
                analysisContext,
                line,
                character
            )
            is CompletionKind.TypeAnnotation -> provideTypeCompletions(
                completionContext.prefix,
                symbolTable
            )
            is CompletionKind.Import -> provideImportCompletions(
                completionContext.prefix
            )
            is CompletionKind.Statement -> provideStatementCompletions(
                completionContext.prefix,
                symbolTable,
                analysisContext,
                line,
                character
            )
            is CompletionKind.Annotation -> provideAnnotationCompletions(
                completionContext.prefix
            )
        }

        return CompletionList(false, items)
    }

    fun resolveCompletionItem(item: CompletionItem): CompletionItem {
        val data = item.data
        if (data is String) {
            val symbol = projectIndex.findByFqName(data)
            if (symbol != null) {
                item.documentation = Either.forRight(MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = buildDocumentation(symbol)
                })
            }
        }
        return item
    }

    fun provideSignatureHelp(uri: String, line: Int, character: Int): SignatureHelp? {
        val state = documentManager.get(uri) ?: return null

        analysisScheduler.analyzeSync(uri)

        val content = state.content
        val offset = state.positionToOffset(line, character)

        val callInfo = findCallContext(content, offset) ?: return null

        val functions = projectIndex.findByPrefix(callInfo.functionName, limit = 20)
            .filter { it.kind == IndexedSymbolKind.FUNCTION && it.name == callInfo.functionName }

        if (functions.isEmpty()) return null

        val signatures = functions.map { func ->
            SignatureInformation().apply {
                label = buildSignatureLabel(func)
                documentation = Either.forRight(MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = "```kotlin\n${func.toDisplayString()}\n```"
                })
                parameters = func.parameters.map { param ->
                    ParameterInformation().apply {
                        label = Either.forLeft("${param.name}: ${param.type}")
                    }
                }
            }
        }

        return SignatureHelp().apply {
            this.signatures = signatures
            activeSignature = 0
            activeParameter = callInfo.argumentIndex
        }
    }

    private fun analyzeCompletionContext(
        content: String,
        line: Int,
        character: Int,
        triggerChar: String?
    ): CompletionKind {
        val lines = content.split('\n')
        if (line >= lines.size) return CompletionKind.Statement("")

        val lineText = lines[line]
        val beforeCursor = if (character <= lineText.length) lineText.substring(0, character) else lineText

        val trimmedLine = beforeCursor.trimStart()
        if (trimmedLine.startsWith("import ") || trimmedLine == "import") {
            val importPart = if (trimmedLine == "import") "" else trimmedLine.substringAfter("import ").trim()
            return CompletionKind.Import(importPart)
        }

        if (triggerChar == ".") {
            val receiver = extractReceiverExpression(beforeCursor.dropLast(1))
            return CompletionKind.MemberAccess(receiver, "")
        }

        if (triggerChar == ":") {
            if (beforeCursor.trimEnd().endsWith(":")) {
                return CompletionKind.TypeAnnotation("")
            }
        }

        if (triggerChar == "@") {
            return CompletionKind.Annotation("")
        }

        val dotIndex = beforeCursor.lastIndexOf('.')
        if (dotIndex >= 0) {
            val afterDot = beforeCursor.substring(dotIndex + 1)
            if (afterDot.all { it.isLetterOrDigit() || it == '_' }) {
                val receiver = extractReceiverExpression(beforeCursor.substring(0, dotIndex))
                return CompletionKind.MemberAccess(receiver, afterDot)
            }
        }

        val colonIndex = beforeCursor.lastIndexOf(':')
        if (colonIndex >= 0) {
            val afterColon = beforeCursor.substring(colonIndex + 1).trim()
            if (afterColon.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                return CompletionKind.TypeAnnotation(afterColon)
            }
        }

        val prefix = extractIdentifierPrefix(beforeCursor)
        return CompletionKind.Statement(prefix)
    }

    private fun extractReceiverExpression(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return ""

        var depth = 0
        var i = trimmed.length - 1

        while (i >= 0) {
            when (trimmed[i]) {
                ')' -> depth++
                '(' -> {
                    depth--
                    if (depth < 0) break
                }
                ']' -> depth++
                '[' -> {
                    depth--
                    if (depth < 0) break
                }
                ' ', '\t', '\n', '=', ',', ';', '{', '}' -> {
                    if (depth == 0) break
                }
            }
            i--
        }

        return trimmed.substring(i + 1)
    }

    private fun extractIdentifierPrefix(text: String): String {
        var i = text.length - 1
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '_')) {
            i--
        }
        return text.substring(i + 1)
    }

    private fun provideMemberCompletions(
        receiver: String,
        prefix: String,
        symbolTable: SymbolTable?,
        analysisContext: AnalysisContext?,
        line: Int,
        character: Int
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val addedMemberNames = mutableSetOf<String>()

        val typeRef = resolveReceiverType(receiver, symbolTable, analysisContext, line, character)
        val typeName = typeRef?.name ?: receiver

        if (symbolTable != null) {
            val position = Position(line, character)
            val symbols = symbolTable.resolve(receiver, position)
            val symbol = symbols.firstOrNull()

            when (symbol) {
                is ClassSymbol -> {
                    val companion = symbol.companionObject
                        ?: symbol.members.filterIsInstance<ClassSymbol>()
                            .find { it.kind == ClassKind.COMPANION_OBJECT }
                    companion?.members?.forEach { member ->
                        addedMemberNames.add(member.name)
                        items.add(createCompletionItem(member, CompletionItemPriority.MEMBER, analysisContext))
                    }
                }
                is PropertySymbol, is ParameterSymbol -> {
                    val resolvedType = typeRef?.name
                    if (resolvedType != null) {
                        val classSymbol = symbolTable.resolve(resolvedType, position).firstOrNull()
                        if (classSymbol is ClassSymbol) {
                            classSymbol.members.forEach { member ->
                                addedMemberNames.add(member.name)
                                items.add(createCompletionItem(member, CompletionItemPriority.MEMBER, analysisContext))
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        val classpathIndex = projectIndex.getClasspathIndex()
        if (classpathIndex != null) {
            val fqName = resolveToFqName(typeName, symbolTable)
            val classpathMembers = findClasspathMembersWithInheritance(fqName, classpathIndex)
            classpathMembers.forEach { member ->
                if (member.name !in addedMemberNames && !isFilteredMember(member.name)) {
                    addedMemberNames.add(member.name)
                    items.add(createCompletionItemFromIndexed(member, CompletionItemPriority.MEMBER))
                }
            }

            for (member in classpathMembers) {
                val name = member.name
                val propName = when {
                    name.startsWith("get") && name.length > 3 && name[3].isUpperCase() ->
                        name[3].lowercase() + name.substring(4)
                    name.startsWith("is") && name.length > 2 && name[2].isUpperCase() ->
                        name[2].lowercase() + name.substring(3)
                    else -> null
                }
                if (propName != null && propName !in addedMemberNames) {
                    addedMemberNames.add(propName)
                    items.add(CompletionItem().apply {
                        label = propName
                        kind = CompletionItemKind.Property
                        detail = member.returnType ?: ""
                        sortText = "${CompletionItemPriority.MEMBER.ordinal}_$propName"
                        insertText = propName
                    })
                }
            }
        }

        val projectExtensions = projectIndex.findExtensions(typeName, emptyList())
        projectExtensions.forEach { ext ->
            if (!ext.isStdlib) {
                items.add(createCompletionItemFromIndexed(ext, CompletionItemPriority.EXTENSION))
            }
        }

        val stdlibIndex = projectIndex.getStdlibIndex()

        if (stdlibIndex != null) {
            val simpleTypeName = typeName.substringAfterLast('.')

            val stdlibMembers = findStdlibMembersWithInheritance(simpleTypeName, stdlibIndex)
            stdlibMembers.forEach { member ->
                if (member.name !in addedMemberNames) {
                    addedMemberNames.add(member.name)
                    items.add(createCompletionItemFromIndexed(member, CompletionItemPriority.MEMBER))
                }
            }

            val typeExtensions = stdlibIndex.findExtensions(simpleTypeName, emptyList())
            typeExtensions.forEach { ext ->
                if (ext.name !in addedMemberNames) {
                    addedMemberNames.add(ext.name)
                    items.add(createCompletionItemFromIndexed(ext, CompletionItemPriority.MEMBER))
                }
            }

            val typeClass = stdlibIndex.findBySimpleName(simpleTypeName)
                .firstOrNull { it.kind.isClass }

            if (typeClass != null) {
                typeClass.superTypes.forEach { superType ->
                    val superSimpleName = superType.substringAfterLast('.')
                    val superExtensions = stdlibIndex.findExtensions(superSimpleName, emptyList())
                    superExtensions.forEach { ext ->
                        if (ext.name !in addedMemberNames) {
                            addedMemberNames.add(ext.name)
                            items.add(createCompletionItemFromIndexed(ext, CompletionItemPriority.MEMBER))
                        }
                    }
                }
            }
        }

        val stdlibExtensions = stdlibIndex?.findExtensions(typeName, emptyList()) ?: emptyList()
        stdlibExtensions.forEach { ext ->
            if (items.none { it.label == ext.name }) {
                items.add(createCompletionItemFromIndexed(ext, CompletionItemPriority.STDLIB))
            }
        }

        return if (prefix.isNotEmpty()) {
            items.filter { it.label.startsWith(prefix, ignoreCase = true) }
        } else {
            items
        }
    }

    private fun provideTypeCompletions(prefix: String, symbolTable: SymbolTable?): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val addedNames = mutableSetOf<String>()

        symbolTable?.topLevelSymbols
            ?.filterIsInstance<ClassSymbol>()
            ?.filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            ?.forEach { cls ->
                addedNames.add(cls.name)
                items.add(createCompletionItem(cls, CompletionItemPriority.LOCAL))
            }

        val classpathIndex = projectIndex.getClasspathIndex()
        val classpathTypes = classpathIndex?.getAllClasses()
            ?.filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            ?.sortedWith(compareBy<IndexedSymbol> { if (it.name.equals(prefix, ignoreCase = true)) 0 else 1 }.thenBy { it.name })
            ?: emptyList()

        val stdlibTypes = projectIndex.getStdlibIndex()?.getAllClasses()
            ?.filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            ?.sortedWith(compareBy<IndexedSymbol> { if (it.name.equals(prefix, ignoreCase = true)) 0 else 1 }.thenBy { it.name })
            ?: emptyList()

        classpathTypes.take(50).forEach { type ->
            if (type.name !in addedNames) {
                addedNames.add(type.name)
                items.add(createCompletionItemFromIndexed(type, CompletionItemPriority.IMPORTED))
            }
        }

        stdlibTypes.take(30).forEach { type ->
            if (type.name !in addedNames) {
                addedNames.add(type.name)
                items.add(createCompletionItemFromIndexed(type, CompletionItemPriority.STDLIB))
            }
        }

        return items
    }

    private fun provideImportCompletions(prefix: String): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val addedNames = mutableSetOf<String>()

        val packageName: String
        val incomplete: String

        when {
            prefix.isEmpty() -> {
                packageName = ""
                incomplete = ""
            }
            prefix.endsWith(".") -> {
                packageName = prefix.dropLast(1)
                incomplete = ""
            }
            prefix.contains(".") -> {
                packageName = prefix.substringBeforeLast('.')
                incomplete = prefix.substringAfterLast('.')
            }
            else -> {
                packageName = ""
                incomplete = prefix
            }
        }

        val allPackages = projectIndex.packageNames

        if (packageName.isEmpty()) {
            val rootPackages = allPackages
                .map { it.substringBefore('.') }
                .filter { it.isNotEmpty() }
                .distinct()
                .filter { incomplete.isEmpty() || it.startsWith(incomplete, ignoreCase = true) }
                .sorted()

            rootPackages.take(50).forEach { pkg ->
                if (pkg !in addedNames) {
                    addedNames.add(pkg)
                    items.add(CompletionItem().apply {
                        label = pkg
                        kind = CompletionItemKind.Module
                        insertText = pkg
                        sortText = "0_$pkg"
                    })
                }
            }
        } else {
            val subpackagePrefix = "$packageName."
            val subpackages = allPackages
                .filter { it.startsWith(subpackagePrefix) && it != packageName }
                .map { pkg ->
                    val rest = pkg.removePrefix(subpackagePrefix)
                    val firstDot = rest.indexOf('.')
                    if (firstDot > 0) rest.substring(0, firstDot) else rest
                }
                .distinct()
                .filter { incomplete.isEmpty() || it.startsWith(incomplete, ignoreCase = true) }
                .sorted()

            subpackages.take(30).forEach { subPkg ->
                if (subPkg !in addedNames) {
                    addedNames.add(subPkg)
                    items.add(CompletionItem().apply {
                        label = subPkg
                        kind = CompletionItemKind.Module
                        insertText = subPkg
                        sortText = "0_$subPkg"
                    })
                }
            }

            val classesInPackage = projectIndex.findByPackage(packageName)
                .filter { it.isTopLevel && it.kind.isClass }
                .filter { incomplete.isEmpty() || it.name.startsWith(incomplete, ignoreCase = true) }

            classesInPackage.take(30).forEach { cls ->
                if (cls.name !in addedNames) {
                    addedNames.add(cls.name)
                    items.add(CompletionItem().apply {
                        label = cls.name
                        kind = cls.kind.toCompletionKind()
                        insertText = cls.name
                        detail = cls.fqName
                        sortText = "1_${cls.name}"
                    })
                }
            }
        }

        return items
    }

    private fun provideStatementCompletions(
        prefix: String,
        symbolTable: SymbolTable?,
        analysisContext: AnalysisContext?,
        line: Int,
        character: Int
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        val position = Position(line, character)

        val importedPackages = symbolTable?.imports
            ?.map { it.packageName }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val visibleSymbols = symbolTable?.allVisibleSymbols(position) ?: emptyList()
        visibleSymbols
            .filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            .forEach { symbol ->
                val priority = when (symbol) {
                    is ParameterSymbol, is PropertySymbol -> CompletionItemPriority.LOCAL
                    else -> CompletionItemPriority.MEMBER
                }
                items.add(createCompletionItem(symbol, priority, analysisContext))
            }

        if (prefix.isEmpty()) {
            return items
        }

        val classpathIndex = projectIndex.getClasspathIndex()
        val classpathSymbols = classpathIndex?.findByPrefix(prefix, 30) ?: emptyList()
        val classpathFqNames = classpathSymbols.map { it.fqName }.toSet()

        val projectSymbols = projectIndex.findByPrefix(prefix, limit = 20)
            .filter { it.fqName !in classpathFqNames }

        val stdlibSymbols = projectIndex.getStdlibIndex()?.findByPrefix(prefix, 20) ?: emptyList()

        val sortedProjectSymbols = projectSymbols.sortedWith(
            compareBy<IndexedSymbol> { if (it.name.equals(prefix, ignoreCase = true)) 0 else 1 }
                .thenBy { it.name }
        )

        val sortedClasspathSymbols = classpathSymbols.sortedWith(
            compareBy<IndexedSymbol> { if (it.packageName in importedPackages) 0 else 1 }
                .thenBy { if (it.name.equals(prefix, ignoreCase = true)) 0 else 1 }
                .thenBy { it.name }
        )

        val sortedStdlibSymbols = stdlibSymbols.sortedWith(
            compareBy<IndexedSymbol> { if (it.packageName in importedPackages) 0 else 1 }
                .thenBy { if (it.name.equals(prefix, ignoreCase = true)) 0 else 1 }
                .thenBy { it.name }
        )

        val addedNames = items.map { it.label }.toMutableSet()

        sortedProjectSymbols.take(30).forEach { indexed ->
            if (indexed.name !in addedNames) {
                addedNames.add(indexed.name)
                items.add(createCompletionItemFromIndexed(indexed, CompletionItemPriority.LOCAL, importedPackages))
            }
        }

        sortedClasspathSymbols.take(50).forEach { indexed ->
            if (indexed.name !in addedNames) {
                addedNames.add(indexed.name)
                items.add(createCompletionItemFromIndexed(indexed, CompletionItemPriority.IMPORTED, importedPackages))
            }
        }

        sortedStdlibSymbols.take(30).forEach { indexed ->
            if (indexed.name !in addedNames) {
                addedNames.add(indexed.name)
                items.add(createCompletionItemFromIndexed(indexed, CompletionItemPriority.STDLIB, importedPackages))
            }
        }

        if (prefix.isNotEmpty()) {
            KOTLIN_KEYWORDS
                .filter { it.startsWith(prefix, ignoreCase = true) && it !in addedNames }
                .forEach { keyword ->
                    items.add(CompletionItem().apply {
                        label = keyword
                        kind = CompletionItemKind.Keyword
                        insertText = keyword
                        sortText = "${CompletionItemPriority.KEYWORD.ordinal}_$keyword"
                    })
                }
        }

        return items
    }

    private fun provideAnnotationCompletions(prefix: String): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        val annotations = projectIndex.getAllClasses()
            .filter { it.kind == IndexedSymbolKind.ANNOTATION_CLASS }
            .filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            .take(30)

        annotations.forEach { ann ->
            items.add(CompletionItem().apply {
                label = ann.name
                kind = CompletionItemKind.Class
                insertText = ann.name
                detail = ann.packageName
                data = ann.fqName
            })
        }

        return items
    }

    private fun analyzeWithCompletionPatch(
        state: DocumentState,
        uri: String
    ): Pair<SymbolTable?, AnalysisContext?> {
        val content = state.content
        val patchedContent = patchDanglingDots(content)

        if (patchedContent != content) {
            val parseResult = completionParser.parse(patchedContent, state.filePath)
            val symbolTable = SymbolBuilder.build(parseResult.tree, state.filePath)
            val analysisContext = AnalysisContext(
                tree = parseResult.tree,
                symbolTable = symbolTable,
                filePath = state.filePath,
                stdlibIndex = projectIndex.getStdlibIndex(),
                projectIndex = projectIndex,
                syntaxErrorRanges = parseResult.syntaxErrors.map { it.range }
            )
            return symbolTable to analysisContext
        }

        analysisScheduler.analyzeSync(uri)
        return state.symbolTable to state.analysisContext
    }

    private fun patchDanglingDots(content: String): String {
        val lines = content.split('\n').toMutableList()
        var patched = false

        for (i in lines.indices) {
            val trimmed = lines[i].trimEnd()
            if (trimmed.endsWith('.')) {
                val dotPos = lines[i].lastIndexOf('.')
                val beforeDot = lines[i].substring(0, dotPos).trimEnd()
                if (beforeDot.isNotEmpty() && (beforeDot.last().isLetterOrDigit() || beforeDot.last() == '_' || beforeDot.last() == ')' || beforeDot.last() == ']')) {
                    lines[i] = lines[i].substring(0, dotPos + 1) + "toString()"
                    patched = true
                }
            }
        }

        return if (patched) lines.joinToString("\n") else content
    }

    private fun resolveToFqName(typeName: String, symbolTable: SymbolTable?): String {
        if (typeName.contains('.')) return typeName

        symbolTable?.imports?.forEach { import ->
            if (!import.isStar) {
                val importedName = import.alias ?: import.fqName.substringAfterLast('.')
                if (importedName == typeName) return import.fqName
            }
        }

        if (symbolTable != null) {
            val pkg = symbolTable.packageName
            val hasDeclaredType = symbolTable.classes.any { it.name == typeName }
                || symbolTable.typeAliases.any { it.name == typeName }
            if (hasDeclaredType) {
                return if (pkg.isNotEmpty()) "$pkg.$typeName" else typeName
            }
        }

        val projectMatches = projectIndex.findInProjectFiles(typeName).filter { it.kind.isClass }
        if (projectMatches.isNotEmpty()) {
            if (projectMatches.size == 1) return projectMatches.first().fqName
            val samePackage = projectMatches.firstOrNull { it.packageName == symbolTable?.packageName }
            return (samePackage ?: projectMatches.first()).fqName
        }

        symbolTable?.imports?.filter { it.isStar }?.forEach { import ->
            val candidate = "${import.fqName}.$typeName"
            if (projectIndex.findByFqName(candidate) != null) return candidate
        }

        val classpathIndex = projectIndex.getClasspathIndex()
        if (classpathIndex != null) {
            val matches = classpathIndex.findBySimpleName(typeName).filter { it.kind.isClass }
            if (matches.size == 1) return matches.first().fqName
        }

        val stdlibIndex = projectIndex.getStdlibIndex()
        if (stdlibIndex != null) {
            val stdlibMatch = stdlibIndex.findBySimpleName(typeName).firstOrNull { it.kind.isClass }
            if (stdlibMatch != null) return stdlibMatch.fqName
        }

        return typeName
    }

    private fun findStdlibMembersWithInheritance(
        simpleTypeName: String,
        stdlibIndex: StdlibIndex
    ): List<IndexedSymbol> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<IndexedSymbol>()
        val queue = ArrayDeque<String>()
        queue.add(simpleTypeName)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            val fqCandidates = stdlibIndex.findBySimpleName(current).filter { it.kind.isClass }
            for (classSymbol in fqCandidates) {
                result.addAll(stdlibIndex.findMembers(classSymbol.fqName))
                classSymbol.superTypes.forEach { superType ->
                    val superSimple = superType.substringAfterLast('.')
                    if (superSimple !in visited) queue.add(superSimple)
                }
            }
        }

        val seen = mutableSetOf<String>()
        return result.filter { member ->
            val key = member.kind.name + ":" + member.name + "(" + member.parameters.joinToString(",") { it.type } + ")"
            seen.add(key)
        }
    }

    private fun findClasspathMembersWithInheritance(
        classFqName: String,
        classpathIndex: ClasspathIndex
    ): List<IndexedSymbol> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<IndexedSymbol>()
        val queue = ArrayDeque<String>()
        queue.add(classFqName)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            result.addAll(classpathIndex.findMembers(current))

            val classSymbol = classpathIndex.findByFqName(current)
            classSymbol?.superTypes?.forEach { superType ->
                if (superType !in visited) queue.add(superType)
            }
        }

        val seen = mutableSetOf<String>()
        return result.filter { member ->
            val key = member.kind.name + ":" + member.name + "(" + member.parameters.joinToString(",") { it.type } + ")"
            seen.add(key)
        }
    }

    private fun resolveReceiverType(
        receiver: String,
        symbolTable: SymbolTable?,
        analysisContext: AnalysisContext?,
        line: Int,
        character: Int
    ): TypeReference? {
        if (symbolTable == null) return null

        val position = Position(line, character)
        val symbols = symbolTable.resolve(receiver, position)
        val symbol = symbols.firstOrNull() ?: return null

        val smartCastType = analysisContext?.getSmartCastTypeAtPosition(symbol, line, character)
        if (smartCastType != null) {
            return TypeReference(smartCastType.render(), emptyList())
        }

        val explicitType = when (symbol) {
            is PropertySymbol -> symbol.type
            is ParameterSymbol -> symbol.type
            is FunctionSymbol -> symbol.returnType
            else -> null
        }

        if (explicitType != null) return explicitType

        val inferredType = analysisContext?.getSymbolType(symbol)
        if (inferredType != null) {
            return TypeReference(inferredType.render(), emptyList())
        }

        return null
    }

    private fun isFilteredMember(name: String): Boolean {
        if (name == "<init>" || name == "<clinit>") return true
        if (name.startsWith("access$") || name.startsWith("$")) return true
        return false
    }

    private fun createCompletionItem(
        symbol: Symbol,
        priority: CompletionItemPriority,
        analysisContext: AnalysisContext? = null
    ): CompletionItem {
        return CompletionItem().apply {
            label = symbol.name
            kind = symbol.toCompletionKind()
            sortText = "${priority.ordinal}_${symbol.name}"

            when (symbol) {
                is FunctionSymbol -> {
                    detail = symbol.toSignatureString()
                    insertText = if (symbol.parameters.isEmpty()) {
                        "${symbol.name}()"
                    } else {
                        symbol.name
                    }
                    insertTextFormat = InsertTextFormat.PlainText
                }
                is PropertySymbol -> {
                    val inferredType = analysisContext?.getSymbolType(symbol)
                    detail = symbol.type?.render()
                        ?: inferredType?.render()
                        ?: "Unknown"
                }
                is ParameterSymbol -> {
                    val inferredType = analysisContext?.getSymbolType(symbol)
                    detail = symbol.type?.render()
                        ?: inferredType?.render()
                        ?: "Unknown"
                }
                is ClassSymbol -> {
                    detail = symbol.kind.name.lowercase()
                }
                else -> {}
            }
        }
    }

    private fun createCompletionItemFromIndexed(
        symbol: IndexedSymbol,
        priority: CompletionItemPriority,
        importedPackages: Set<String> = emptySet()
    ): CompletionItem {
        val importBoost = if (symbol.packageName in importedPackages) "0" else "1"
        return CompletionItem().apply {
            label = symbol.name
            kind = symbol.kind.toCompletionKind()
            sortText = "${priority.ordinal}${importBoost}_${symbol.name}"
            detail = when {
                symbol.kind.isCallable -> symbol.toDisplayString()
                else -> symbol.packageName
            }
            data = symbol.fqName

            if (symbol.deprecated) {
                @Suppress("DEPRECATION")
                deprecated = true
                tags = listOf(CompletionItemTag.Deprecated)
            }

            if (symbol.kind == IndexedSymbolKind.FUNCTION) {
                insertText = if (symbol.arity == 0) "${symbol.name}()" else symbol.name
            }
        }
    }

    private fun buildDocumentation(symbol: IndexedSymbol): String {
        return buildString {
            append("```kotlin\n")
            append(symbol.toDisplayString())
            append("\n```\n\n")
            append("**Package:** ${symbol.packageName}")
            if (symbol.deprecated) {
                append("\n\n⚠️ **Deprecated**")
                symbol.deprecationMessage?.let { append(": $it") }
            }
        }
    }

    private fun findCallContext(content: String, offset: Int): CallContext? {
        var parenDepth = 0
        var argIndex = 0
        var functionStart = -1

        for (i in (offset - 1) downTo 0) {
            when (content[i]) {
                ')' -> parenDepth++
                '(' -> {
                    if (parenDepth == 0) {
                        functionStart = i
                        break
                    }
                    parenDepth--
                }
                ',' -> {
                    if (parenDepth == 0) argIndex++
                }
            }
        }

        if (functionStart < 0) return null

        var nameEnd = functionStart - 1
        while (nameEnd >= 0 && content[nameEnd].isWhitespace()) nameEnd--

        var nameStart = nameEnd
        while (nameStart > 0 && (content[nameStart - 1].isLetterOrDigit() || content[nameStart - 1] == '_')) {
            nameStart--
        }

        if (nameStart > nameEnd) return null

        val functionName = content.substring(nameStart, nameEnd + 1)
        return CallContext(functionName, argIndex)
    }

    private fun buildSignatureLabel(func: IndexedSymbol): String {
        return buildString {
            append(func.name)
            append("(")
            append(func.parameters.joinToString(", ") { param ->
                "${param.name}: ${param.type}"
            })
            append(")")
            func.returnType?.let { append(": $it") }
        }
    }

    companion object {
        private val KOTLIN_KEYWORDS = listOf(
            "abstract", "actual", "annotation", "as", "break", "by", "catch",
            "class", "companion", "const", "constructor", "continue", "crossinline",
            "data", "do", "else", "enum", "expect", "external", "false", "final",
            "finally", "for", "fun", "get", "if", "import", "in", "infix", "init",
            "inline", "inner", "interface", "internal", "is", "lateinit", "noinline",
            "null", "object", "open", "operator", "out", "override", "package",
            "private", "protected", "public", "reified", "return", "sealed", "set",
            "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias",
            "val", "var", "vararg", "when", "where", "while"
        )
    }
}

private sealed class CompletionKind {
    data class MemberAccess(val receiver: String, val prefix: String) : CompletionKind()
    data class TypeAnnotation(val prefix: String) : CompletionKind()
    data class Import(val prefix: String) : CompletionKind()
    data class Statement(val prefix: String) : CompletionKind()
    data class Annotation(val prefix: String) : CompletionKind()
}

private enum class CompletionItemPriority {
    LOCAL,
    MEMBER,
    EXTENSION,
    IMPORTED,
    STDLIB,
    KEYWORD
}

private data class CallContext(
    val functionName: String,
    val argumentIndex: Int
)

private fun Symbol.toCompletionKind(): CompletionItemKind {
    return when (this) {
        is FunctionSymbol -> CompletionItemKind.Function
        is PropertySymbol -> {
            val scopeKind = containingScope?.kind
            if (scopeKind != null && scopeKind.canContainLocals) {
                CompletionItemKind.Variable
            } else {
                CompletionItemKind.Property
            }
        }
        is ParameterSymbol -> CompletionItemKind.Variable
        is ClassSymbol -> when (kind) {
            ClassKind.INTERFACE -> CompletionItemKind.Interface
            ClassKind.ENUM_CLASS -> CompletionItemKind.Enum
            ClassKind.OBJECT, ClassKind.COMPANION_OBJECT -> CompletionItemKind.Module
            else -> CompletionItemKind.Class
        }
        is TypeParameterSymbol -> CompletionItemKind.TypeParameter
        else -> CompletionItemKind.Text
    }
}

private fun IndexedSymbolKind.toCompletionKind(): CompletionItemKind {
    return when (this) {
        IndexedSymbolKind.CLASS -> CompletionItemKind.Class
        IndexedSymbolKind.INTERFACE -> CompletionItemKind.Interface
        IndexedSymbolKind.OBJECT -> CompletionItemKind.Module
        IndexedSymbolKind.ENUM_CLASS -> CompletionItemKind.Enum
        IndexedSymbolKind.ANNOTATION_CLASS -> CompletionItemKind.Class
        IndexedSymbolKind.DATA_CLASS -> CompletionItemKind.Class
        IndexedSymbolKind.VALUE_CLASS -> CompletionItemKind.Class
        IndexedSymbolKind.FUNCTION -> CompletionItemKind.Function
        IndexedSymbolKind.PROPERTY -> CompletionItemKind.Property
        IndexedSymbolKind.CONSTRUCTOR -> CompletionItemKind.Constructor
        IndexedSymbolKind.TYPE_ALIAS -> CompletionItemKind.TypeParameter
    }
}

private fun FunctionSymbol.toSignatureString(): String {
    return buildString {
        append("fun ")
        if (typeParameters.isNotEmpty()) {
            append("<")
            append(typeParameters.joinToString(", ") { it.name })
            append("> ")
        }
        receiverType?.let { append("${it.render()}.") }
        append(name)
        append("(")
        append(parameters.joinToString(", ") { "${it.name}: ${it.type?.render() ?: "Any"}" })
        append(")")
        returnType?.let { append(": ${it.render()}") }
    }
}
