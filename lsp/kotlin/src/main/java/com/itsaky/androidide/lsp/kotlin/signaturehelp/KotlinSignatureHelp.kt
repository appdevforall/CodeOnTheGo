package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureInformation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallElement

/**
 * Builds a [SignatureHelp] for the function [call] with the cursor at [offset].
 *
 * Overloads come from the compiler's own candidate resolution; the active overload is the candidate
 * the compiler marks as best (falling back to the resolved call, then to the first candidate). The
 * active parameter is computed against that active overload.
 */
internal fun KaSession.buildSignatureHelp(call: KtCallElement, offset: Int): SignatureHelp {
  // (resolved function call, isBest) pairs, in candidate order.
  val candidates = call.resolveToCallCandidates()
    .mapNotNull { info ->
      (info.candidate as? KaFunctionCall<*>)?.let { it to info.isInBestCandidates }
    }
    .ifEmpty {
      // Fallback: a single successfully-resolved function call.
      call.resolveToCall()?.successfulFunctionCallOrNull()?.let { listOf(it to true) } ?: emptyList()
    }

  if (candidates.isEmpty()) {
    return SignatureHelp.empty()
  }

  val signatures: List<SignatureInformation> =
    candidates.map { (fnCall, _) -> buildSignatureInformation(fnCall.symbol) }

  val activeSignature = candidates.indexOfFirst { it.second }.let { if (it < 0) 0 else it }
  val activeCall = candidates[activeSignature].first
  val activeParameter = computeActiveParameter(call, activeCall, offset)

  return SignatureHelp(signatures, activeSignature, activeParameter)
}
