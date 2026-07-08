package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.itsaky.androidide.lsp.kotlin.utils.renderName
import com.itsaky.androidide.lsp.models.MarkupContent
import com.itsaky.androidide.lsp.models.ParameterInformation
import com.itsaky.androidide.lsp.models.SignatureInformation
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SignatureInfoBuilder")

@OptIn(KaExperimentalApi::class)
internal fun KaSession.buildSignatureInformation(symbol: KaFunctionSymbol): SignatureInformation {
	val name = functionDisplayName(symbol)
	val paramLabels = symbol.valueParameters.map { paramLabel(it) }
	val label = paramLabels.joinToString(prefix = "$name(", separator = ", ", postfix = ")")
	val parameters = paramLabels.map { ParameterInformation(it, MarkupContent()) }
	logger.debug("buildSignatureInformation produced label '{}'", label)
	return SignatureInformation(label, MarkupContent(), parameters)
}

private fun functionDisplayName(symbol: KaFunctionSymbol): String =
	when (symbol) {
		is KaNamedFunctionSymbol -> symbol.name.asString()
		is KaConstructorSymbol -> symbol.containingClassId?.shortClassName?.asString() ?: "<init>"
		else -> "<anonymous>"
	}

@OptIn(KaExperimentalApi::class)
private fun KaSession.paramLabel(param: KaValueParameterSymbol): String {
	val prefix = if (param.isVararg) "vararg " else ""
	return "$prefix${param.name.asString()}: ${renderName(param.returnType)}"
}
