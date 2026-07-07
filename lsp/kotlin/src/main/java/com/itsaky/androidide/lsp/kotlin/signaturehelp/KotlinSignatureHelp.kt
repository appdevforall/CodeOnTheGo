package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.models.SignatureInformation
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.slf4j.LoggerFactory

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

private val logger = LoggerFactory.getLogger("KotlinSignatureHelp")

/**
 * Computes [SignatureHelp] for the request described by [params], using the given
 * [CompilationEnvironment] to resolve the enclosing call and analyze it.
 */
context(env: CompilationEnvironment)
internal fun doSignatureHelp(params: SignatureHelpParams): SignatureHelp {
  if (params.cancelChecker.isCancelled()) {
    return SignatureHelp.empty()
  }

  val ktFile = env.ktSymbolIndex.getOpenedKtFile(params.file)
  if (ktFile == null) {
    logger.warn("File {} is not open", params.file)
    return SignatureHelp.empty()
  }

  return try {
    val offset = params.position.requireIndex()
    env.project.read {
      val call = findEnclosingCall(ktFile, offset) ?: return@read SignatureHelp.empty()
      analyzeMaybeDangling(ktFile) {
        buildSignatureHelp(call, offset)
      }
    }
  } catch (e: Throwable) {
    if (e is CancellationException) throw e
    logger.warn("An error occurred while computing signature help for {}", params.file, e)
    SignatureHelp.empty()
  }
}
