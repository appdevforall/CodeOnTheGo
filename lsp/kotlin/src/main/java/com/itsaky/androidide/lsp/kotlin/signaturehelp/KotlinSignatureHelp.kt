package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.models.SignatureInformation
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.psi.KtCallElement
import org.slf4j.LoggerFactory
import kotlin.io.path.name

/**
 * Builds a [SignatureHelp] for the function [call] with the cursor at [offset].
 *
 * Overloads come from the compiler's own candidate resolution; the active overload is the candidate
 * the compiler marks as best (falling back to the resolved call, then to the first candidate). The
 * active parameter is computed against that active overload.
 */
internal fun KaSession.buildSignatureHelp(call: KtCallElement, offset: Int): SignatureHelp {
  val calleeText = call.calleeExpression?.text

  // (resolved function call, isBest) pairs, in candidate order.
  val resolvedCandidates = call.resolveToCallCandidates()
    .mapNotNull { info ->
      (info.candidate as? KaFunctionCall<*>)?.let { it to info.isInBestCandidates }
    }
  logger.debug(
    "resolveToCallCandidates() found {} candidate(s) for call '{}'",
    resolvedCandidates.size,
    calleeText
  )

  val candidates = resolvedCandidates.ifEmpty {
      // Fallback: a single successfully-resolved function call.
      logger.debug("No candidates from resolveToCallCandidates(); falling back to resolveToCall() for '{}'", calleeText)
      call.resolveToCall()?.successfulFunctionCallOrNull()?.let { listOf(it to true) } ?: emptyList()
    }

  if (candidates.isEmpty()) {
    logger.debug("No resolvable candidates for call '{}'; returning empty signature help", calleeText)
    return SignatureHelp.empty()
  }

  val signatures: List<SignatureInformation> =
    candidates.map { (fnCall, _) -> buildSignatureInformation(fnCall.symbol) }

  val activeSignature = candidates.indexOfFirst { it.second }.let { if (it < 0) 0 else it }
  val activeCall = candidates[activeSignature].first
  val activeParameter = computeActiveParameter(call, activeCall, offset)

  logger.debug(
    "buildSignatureHelp for '{}': {} signature(s), activeSignature={}, activeParameter={}",
    calleeText,
    signatures.size,
    activeSignature,
    activeParameter
  )

  return SignatureHelp(signatures, activeSignature, activeParameter)
}

private val logger = LoggerFactory.getLogger("KotlinSignatureHelp")

/**
 * Computes [SignatureHelp] for the request described by [params], using the given
 * [CompilationEnvironment] to resolve the enclosing call and analyze it.
 */
context(env: CompilationEnvironment)
internal fun doSignatureHelp(params: SignatureHelpParams): SignatureHelp {
  logger.debug("doSignatureHelp requested for file={} position={}", params.file, params.position)

  if (params.cancelChecker.isCancelled()) {
    logger.debug("Signature help request for {} was cancelled before processing", params.file)
    return SignatureHelp.empty()
  }

  val ktFile = env.ktSymbolIndex.getOpenedKtFile(params.file)
  if (ktFile == null) {
    logger.warn("File {} is not open", params.file)
    return SignatureHelp.empty()
  }

  return try {
    val offset = params.position.requireIndex()
    val result = env.project.read {
      // Resolve against the live document contents rather than the opened KtFile. The cursor
      // [offset] is computed by the editor against its in-memory buffer, but the opened KtFile is
      // refreshed asynchronously (KotlinLanguageServer.onDocumentChange is an ASYNC event
      // subscriber), so it can lag one or more keystrokes behind. Feeding a live-buffer offset into
      // a stale KtFile makes it land on the wrong element (or none) and signature help silently
      // fails. Completion avoids this the same way; see KotlinCompletions.doComplete.
      val liveText = FileManager.getDocumentContents(params.file)
      val signatureKtFile = env.parser.createFile(fileName = params.file.name, text = liveText).apply {
        originalFile = ktFile
        originalKtFile = ktFile
      }
      val call = findEnclosingCall(signatureKtFile, offset) ?: return@read SignatureHelp.empty()
      analyzeMaybeDangling(signatureKtFile) {
        buildSignatureHelp(call, offset)
      }
    }
    logger.debug(
      "Signature help result for {}: {} signature(s), activeSignature={}, activeParameter={}",
      params.file,
      result.signatures.size,
      result.activeSignature,
      result.activeParameter
    )
    result
  } catch (e: Throwable) {
    if (e is CancellationException) throw e
    logger.warn("Signature help computation failed for {}", params.file, e)
    SignatureHelp.empty()
  }
}
