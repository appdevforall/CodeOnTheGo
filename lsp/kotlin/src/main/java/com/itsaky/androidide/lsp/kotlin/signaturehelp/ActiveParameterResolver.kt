package com.itsaky.androidide.lsp.kotlin.signaturehelp

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ActiveParameterResolver")

/**
 * Computes the index of the active value parameter for the call at [offset].
 *
 * Positional arguments map by order. When the cursor is on a named argument, the index is remapped
 * to that parameter's declared position in [resolvedCall] (handles reordered named args). The
 * result is applied by the shared UI to whichever overload it renders, so named-arg remapping is
 * resolved against the active overload only.
 */
internal fun KaSession.computeActiveParameter(
  call: KtCallElement,
  resolvedCall: KaFunctionCall<*>?,
  offset: Int
): Int {
  val arguments = call.valueArgumentList?.arguments.orEmpty()

  // The argument the cursor is at/inside: the first whose end is at or after the cursor.
  val current = arguments.firstOrNull { offset <= it.textRange.endOffset }
  val positional = if (current != null) arguments.indexOf(current) else arguments.size

  if (current != null && resolvedCall != null) {
    val argExpr = current.getArgumentExpression()
    val paramSignature = argExpr?.let { resolvedCall.argumentMapping[it] }
    if (paramSignature != null) {
      val declaredIndex = resolvedCall.symbol.valueParameters
        .indexOfFirst { it.name == paramSignature.name }
      if (declaredIndex >= 0) {
        logger.debug(
          "computeActiveParameter: resolved named-argument index {} at offset {}",
          declaredIndex,
          offset
        )
        return declaredIndex
      }
    }
  }
  logger.debug("computeActiveParameter: using positional index {} at offset {}", positional, offset)
  return positional
}
