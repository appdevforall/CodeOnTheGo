package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.completion.DeclarationContext
import com.itsaky.androidide.lsp.kotlin.completion.DeclarationKind
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaScopeContext
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.MODIFIER_KEYWORDS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ContextResolver")

/**
 * Defines context at the cursor position.
 */
internal data class AnalysisContext(
	val env: CompilationEnvironment,
	val file: Path,
	val psiElement: PsiElement,
	val ktFile: KtFile,
	val ktElement: KtElement,
	val scopeContext: KaScopeContext,
	val scope: KaScope,
	val declarationContext: DeclarationContext,
	val declarationKind: DeclarationKind,
	val existingModifiers: Set<KtModifierKeywordToken>,
	val isInsideModifierList: Boolean,
	val partial: String,
)

/**
 * Resolves [AnalysisContext] at the given offset in the given [KtFile].
 *
 * @param env The compilation environment.
 * @param ktFile The Kotlin file.
 * @param offset The offset to resolve context at.
 * @param partial The partial identifier at the cursor position.
 */
internal fun KaSession.resolveAnalysisContext(
	env: CompilationEnvironment,
	file: Path,
	ktFile: KtFile,
	offset: Int,
	partial: String
): AnalysisContext? {
	val psiElement = ktFile.findElementAt(offset)
	if (psiElement == null) {
		logger.error("Unable to find PSI element at offset {} in file {}", offset, ktFile)
		return null
	}

	val ktElement = psiElement.getParentOfType<KtElement>(strict = false)
	if (ktElement == null) {
		logger.error("Cannot find parent of element {}", psiElement)
		return null
	}

	val scopeContext = ktFile.scopeContext(ktElement)
	val compositeScope = scopeContext.compositeScope()

	// The element is typically a KtModifierList, an error node,
	// or the incomplete declaration itself.
	val modifierList = ktElement.getParentOfType<KtModifierList>(strict = false)
	val existingModifiers = modifierList
		?.node?.getChildren(MODIFIER_KEYWORDS)
		?.mapNotNull { it.elementType as? KtModifierKeywordToken }
		?.toSet()
		?: emptySet()

	val declarationKind = resolveDeclarationKind(ktElement)
	val declarationContext = resolveDeclarationContext(ktElement)

	return AnalysisContext(
		env = env,
		file = file,
		psiElement = psiElement,
		ktFile = ktFile,
		ktElement = ktElement,
		scopeContext = scopeContext,
		scope = compositeScope,
		declarationContext = declarationContext,
		declarationKind = declarationKind,
		existingModifiers = existingModifiers,
		isInsideModifierList = modifierList != null,
		partial = partial,
	)
}

private fun resolveDeclarationContext(element: KtElement): DeclarationContext {
	for (ancestor in element.parents) {
		when (ancestor) {
			is KtClassBody -> {
				return when (val owner = ancestor.parent) {
					is KtClass -> when {
						owner.isInterface() -> DeclarationContext.INTERFACE_BODY
						owner.isEnum() -> DeclarationContext.ENUM_BODY
						owner.isAnnotation() -> DeclarationContext.ANNOTATION_BODY
						else -> DeclarationContext.CLASS_BODY
					}

					is KtObjectDeclaration -> DeclarationContext.OBJECT_BODY
					else -> DeclarationContext.CLASS_BODY
				}
			}

			is KtBlockExpression -> return DeclarationContext.FUNCTION_BODY
			is KtFile -> return if (ancestor.isScript())
				DeclarationContext.SCRIPT_TOP_LEVEL
			else
				DeclarationContext.TOP_LEVEL
		}
	}
	return DeclarationContext.TOP_LEVEL
}

private fun resolveDeclarationKind(element: KtElement): DeclarationKind {
	// Walk up to the nearest declaration owning this modifier list / position
	return when (val declaration = element.getNonStrictParentOfType<KtDeclaration>()) {
		is KtClass -> when {
			declaration.isInterface() -> DeclarationKind.INTERFACE
			declaration.isEnum() -> DeclarationKind.ENUM_CLASS
			declaration.isAnnotation() -> DeclarationKind.ANNOTATION_CLASS
			else -> DeclarationKind.CLASS
		}

		is KtObjectDeclaration -> DeclarationKind.OBJECT
		is KtNamedFunction -> DeclarationKind.FUN
		is KtProperty -> if (declaration.isVar) DeclarationKind.PROPERTY_VAR
		else DeclarationKind.PROPERTY_VAL

		is KtTypeAlias -> DeclarationKind.TYPEALIAS
		is KtConstructor<*> -> DeclarationKind.CONSTRUCTOR
		null -> DeclarationKind.UNKNOWN  // pure modifier list, no keyword yet
		else -> DeclarationKind.UNKNOWN
	}
}
