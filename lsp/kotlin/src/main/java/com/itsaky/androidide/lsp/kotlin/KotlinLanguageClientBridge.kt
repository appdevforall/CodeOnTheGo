/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.lsp.models.DiagnosticResult
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

typealias PositionToOffsetResolver = (uri: String) -> ((line: Int, column: Int) -> Int)?

class KotlinLanguageClientBridge(
    private val ideClient: ILanguageClient,
    private val positionResolver: PositionToOffsetResolver
) : LanguageClient {

    companion object {
        private val log = LoggerFactory.getLogger(KotlinLanguageClientBridge::class.java)
    }

    override fun telemetryEvent(obj: Any?) {
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        log.info("[DIAG-DEBUG] publishDiagnostics: uri={}, count={}", diagnostics.uri, diagnostics.diagnostics.size)

        val path = try {
            Paths.get(URI(diagnostics.uri))
        } catch (e: Exception) {
            Paths.get(diagnostics.uri)
        }

        val positionToOffset = positionResolver(diagnostics.uri) ?: run {
            log.warn("[DIAG-DEBUG] Position resolver NULL for: {}, using fallback", diagnostics.uri)
            createFallbackPositionCalculator(path)
        }

        if (positionToOffset == null) {
            log.error("[DIAG-DEBUG] No resolver, dropping {} diagnostics for: {}", diagnostics.diagnostics.size, diagnostics.uri)
            return
        }

        val diagnosticItems = diagnostics.diagnostics.mapNotNull { diag ->
            try {
                val startIndex = positionToOffset(diag.range.start.line, diag.range.start.character)
                val endIndex = positionToOffset(diag.range.end.line, diag.range.end.character)

                val expectedColSpan = if (diag.range.start.line == diag.range.end.line) {
                    diag.range.end.character - diag.range.start.character
                } else {
                    -1
                }
                val actualIndexSpan = endIndex - startIndex

                log.info("[DIAG-DEBUG] range={}:{}-{}:{} -> idx={}-{} (colSpan={}, idxSpan={}) '{}'",
                    diag.range.start.line, diag.range.start.character,
                    diag.range.end.line, diag.range.end.character,
                    startIndex, endIndex,
                    expectedColSpan, actualIndexSpan,
                    diag.message.take(50)
                )

                if (expectedColSpan >= 0 && actualIndexSpan != expectedColSpan) {
                    log.warn("[DIAG-DEBUG] MISMATCH! idxSpan={} != colSpan={}", actualIndexSpan, expectedColSpan)
                }

                val startPos = com.itsaky.androidide.models.Position(
                    diag.range.start.line,
                    diag.range.start.character,
                    startIndex
                )
                val endPos = com.itsaky.androidide.models.Position(
                    diag.range.end.line,
                    diag.range.end.character,
                    endIndex
                )

                com.itsaky.androidide.lsp.models.DiagnosticItem(
                    diag.message,
                    diag.code?.left ?: diag.code?.right?.toString() ?: "",
                    com.itsaky.androidide.models.Range(startPos, endPos),
                    diag.source ?: "ktlsp",
                    when (diag.severity) {
                        org.eclipse.lsp4j.DiagnosticSeverity.Error ->
                            com.itsaky.androidide.lsp.models.DiagnosticSeverity.ERROR
                        org.eclipse.lsp4j.DiagnosticSeverity.Warning ->
                            com.itsaky.androidide.lsp.models.DiagnosticSeverity.WARNING
                        org.eclipse.lsp4j.DiagnosticSeverity.Information ->
                            com.itsaky.androidide.lsp.models.DiagnosticSeverity.INFO
                        org.eclipse.lsp4j.DiagnosticSeverity.Hint ->
                            com.itsaky.androidide.lsp.models.DiagnosticSeverity.HINT
                        null -> com.itsaky.androidide.lsp.models.DiagnosticSeverity.INFO
                    }
                )
            } catch (e: Exception) {
                log.error("Error converting diagnostic: ${diag.message}", e)
                null
            }
        }

        val result = DiagnosticResult(path, diagnosticItems)
        log.info("[DIAG-DEBUG] Publishing {} diagnostics to IDE", diagnosticItems.size)
        ideClient.publishDiagnostics(result)
    }

    private fun createFallbackPositionCalculator(path: java.nio.file.Path): ((Int, Int) -> Int)? {
        return try {
            val file = path.toFile()
            if (!file.exists() || !file.isFile) {
                log.warn("File does not exist for fallback position calculation: {}", path)
                return null
            }

            val content = file.readText()
            val lineOffsets = mutableListOf<Int>()
            lineOffsets.add(0)

            var offset = 0
            for (char in content) {
                offset++
                if (char == '\n') {
                    lineOffsets.add(offset)
                }
            }

            log.info("Created fallback position calculator for {} with {} lines", path, lineOffsets.size)

            val calculator: (Int, Int) -> Int = { line, column ->
                if (line < lineOffsets.size) {
                    lineOffsets[line] + column
                } else {
                    content.length
                }
            }
            calculator
        } catch (e: Exception) {
            log.error("Error creating fallback position calculator for {}: {}", path, e.message)
            null
        }
    }

    override fun showMessage(messageParams: MessageParams) {
        log.info("Kotlin LSP: ${messageParams.message}")
    }

    override fun showMessageRequest(
        requestParams: ShowMessageRequestParams
    ): CompletableFuture<MessageActionItem> {
        log.info("Kotlin LSP request: ${requestParams.message}")
        return CompletableFuture.completedFuture(null)
    }

    override fun logMessage(message: MessageParams) {
        when (message.type) {
            org.eclipse.lsp4j.MessageType.Error -> log.error("Kotlin LSP: ${message.message}")
            org.eclipse.lsp4j.MessageType.Warning -> log.warn("Kotlin LSP: ${message.message}")
            org.eclipse.lsp4j.MessageType.Info -> log.info("Kotlin LSP: ${message.message}")
            org.eclipse.lsp4j.MessageType.Log -> log.debug("Kotlin LSP: ${message.message}")
            null -> log.debug("Kotlin LSP: ${message.message}")
        }
    }
}
