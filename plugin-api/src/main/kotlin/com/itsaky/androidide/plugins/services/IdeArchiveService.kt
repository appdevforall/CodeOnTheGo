package com.itsaky.androidide.plugins.services

import java.io.File
import java.io.InputStream

/**
 * Supported archive formats for [IdeArchiveService.extract].
 *
 * - [XZ] and [GZIP] describe a single compressed stream. The stream content
 *   is written to [IdeArchiveService.extract]'s destination as a single file.
 * - [TAR], [TAR_XZ], [TAR_GZ], and [ZIP] describe multi-entry archives.
 *   Entries are extracted into [IdeArchiveService.extract]'s destination,
 *   which must be a directory.
 */
enum class ArchiveFormat {
    XZ,
    GZIP,
    TAR,
    TAR_XZ,
    TAR_GZ,
    ZIP
}

/**
 * Outcome of an extraction call. Uses a sealed hierarchy so callers
 * handle both paths exhaustively.
 */
sealed class ExtractResult {
    data class Success(
        val bytesWritten: Long,
        val filesExtracted: Int
    ) : ExtractResult()

    data class Failure(val error: Throwable) : ExtractResult()
}

/**
 * Service interface that extracts archives bundled inside plugin assets.
 *
 * The implementation handles compression libraries centrally so plugins do
 * not each need to bundle `org.tukaani:xz` or `commons-compress`. Destination
 * paths are validated against the plugin's write policy, so an extraction
 * that would write outside an allowed directory fails rather than leaks data.
 *
 * Extraction is synchronous and CPU-bound for large archives. Plugins should
 * invoke from a background dispatcher. The call cooperates with coroutine
 * cancellation by checking [Thread.interrupted] between entries; on
 * interruption [extract] returns [ExtractResult.Failure] carrying an
 * [InterruptedException].
 */
interface IdeArchiveService {
    /**
     * Extracts [source] into [destination].
     *
     * For single-stream formats ([ArchiveFormat.XZ], [ArchiveFormat.GZIP]),
     * [destination] is the output file path.
     * For multi-entry formats, [destination] is the directory into which
     * entries are written; it is created if it does not exist.
     *
     * [onProgress] is invoked periodically with the running byte count and,
     * for multi-entry formats, the name of the entry currently being
     * extracted.
     *
     * The caller owns [source] and is responsible for closing it.
     *
     * @return [ExtractResult.Success] with the byte and file counts on
     *   success, [ExtractResult.Failure] carrying the cause on any error
     *   (IO, malformed archive, path traversal, cancellation).
     */
    fun extract(
        source: InputStream,
        format: ArchiveFormat,
        destination: File,
        onProgress: ((bytesProcessed: Long, currentEntry: String?) -> Unit)? = null
    ): ExtractResult
}
