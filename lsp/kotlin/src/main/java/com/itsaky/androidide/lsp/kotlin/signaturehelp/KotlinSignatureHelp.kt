package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isAnalysisCancellation
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureHelpParams
import com.itsaky.androidide.lsp.models.SignatureInformation
import kotlinx.coroutines.future.await
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
        logger.debug(
            "No candidates from resolveToCallCandidates(); falling back to resolveToCall() for '{}'",
            calleeText
        )
        call.resolveToCall()?.successfulFunctionCallOrNull()?.let { listOf(it to true) }
            ?: emptyList()
    }

    if (candidates.isEmpty()) {
        logger.debug(
            "No resolvable candidates for call '{}'; returning empty signature help",
            calleeText
        )
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
internal suspend fun doSignatureHelp(params: SignatureHelpParams): SignatureHelp {
    logger.debug("doSignatureHelp requested for file={} position={}", params.file, params.position)

    if (params.cancelChecker.isCancelled()) {
        logger.debug("Signature help request for {} was cancelled before processing", params.file)
        return SignatureHelp.empty()
    }

    // Safe to await a (possibly blocking) refresh here: this runs outside any project.read/write
    // block, so it can't deadlock against the refresh's project.write (unlike KtSymbolIndex.getKtFile).
    val ktFile = env.ktSymbolIndex.getCurrentKtFile(params.file).await()
    if (ktFile == null) {
        logger.warn("File {} is not open", params.file)
        return SignatureHelp.empty()
    }

    // Signature help is interactive (the user is typing arguments): run at INTERACTIVE priority so it
    // preempts background diagnostics/indexing and is discarded when a newer interactive request wins.
    // params.cancelChecker is request-scoped (CancellableRequestParams), so wrap it directly — no
    // global Lookup fallback needed.
    val cancelChecker = ScheduledCancelChecker(params.cancelChecker)

    return try {
        val offset = params.position.requireIndex()
        cancelChecker.abortIfCancelled()
        val result = env.project.read {
            val call = findEnclosingCall(ktFile, offset) ?: return@read SignatureHelp.empty()
            analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) {
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
        if (e.isAnalysisCancellation()) {
            logger.debug(
                "Signature help for {} cancelled (preempted={})",
                params.file,
                e is AnalysisPreemptedException
            )
            return SignatureHelp.empty()
        }
        logger.warn("Signature help computation failed for {}", params.file, e)
        SignatureHelp.empty()
    }
}
