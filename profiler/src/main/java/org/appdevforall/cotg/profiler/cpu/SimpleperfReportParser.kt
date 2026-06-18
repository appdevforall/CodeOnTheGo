package org.appdevforall.cotg.profiler.cpu

import org.appdevforall.cotg.profiler.cpu.proto.SimpleperfReportProto
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Parses the output of `simpleperf report-sample --protobuf` into a [CpuProfile] (call tree + table).
 *
 * The stream is simpleperf's container framing: the ASCII magic `"SIMPLEPERF"`, a little-endian
 * uint16 version, then a sequence of `[uint32 little-endian length][Record]` blocks terminated by a
 * zero-length block (or EOF). `File` records carry the symbol tables that `Sample` callchain entries
 * reference by `(file_id, symbol_id)`. simpleperf emits all `Sample` records first and the `File`
 * records last, so samples are buffered and resolved once the full symbol table is known.
 *
 * With the `cpu-clock` event, a sample's `event_count` is CPU time in nanoseconds; weights are
 * summed per frame (self = leaf only; inclusive = each distinct frame in the chain, so recursion
 * isn't double counted) and converted to microseconds.
 *
 * @author Akash Yadav
 */
object SimpleperfReportParser {
    private const val MAGIC = "SIMPLEPERF"
    private const val MAX_METHOD_ROWS = 100

    /** Returned when the report stream is empty/truncated (no usable samples). */
    private val EMPTY_PROFILE =
        CpuProfile(
            root = CpuCallNode(name = "(root)", selfMicros = 0, totalMicros = 0, children = emptyList()),
            totalMicros = 0,
            methods = emptyList(),
        )

    private class FileEntry(val path: String, val symbols: List<String>)

    /** A buffered sample: [weight] (ns) and its callchain as interleaved (fileId, symbolId) pairs. */
    private class RawSample(val weight: Long, val chain: IntArray)

    private class MutableNode(val name: String) {
        var selfNanos = 0L
        var totalNanos = 0L
        val children = LinkedHashMap<String, MutableNode>()

        fun child(name: String): MutableNode = children.getOrPut(name) { MutableNode(name) }

        fun freeze(): CpuCallNode =
            CpuCallNode(
                name = name,
                selfMicros = selfNanos / 1000,
                totalMicros = totalNanos / 1000,
                children = children.values.map { it.freeze() }.sortedByDescending { it.totalMicros },
            )
    }

    fun parse(file: File): CpuProfile = file.inputStream().buffered().use { parse(it) }

    fun parse(input: InputStream): CpuProfile {
        val data = DataInputStream(input)

        // An empty/too-short stream means simpleperf produced no report (e.g. a failed or aborted
        // recording, or a perf.data with no samples that never got written). Treat it as an empty
        // profile rather than throwing an EOFException.
        val magic = ByteArray(MAGIC.length)
        try {
            data.readFully(magic)
            readLe16(data) // format version, not needed
        } catch (e: EOFException) {
            return EMPTY_PROFILE
        }
        if (String(magic, Charsets.US_ASCII) != MAGIC) {
            throw IllegalArgumentException("Not a simpleperf report-sample protobuf stream")
        }

        val files = HashMap<Int, FileEntry>()
        val samples = ArrayList<RawSample>()

        // Pass 1: read the stream. File records arrive after all samples, so buffer samples.
        while (true) {
            val size = readLe32(data) ?: break
            if (size == 0) break
            val bytes = ByteArray(size)
            try {
                data.readFully(bytes)
            } catch (e: EOFException) {
                // Truncated stream (e.g. recording killed mid-flush); use whatever was buffered.
                break
            }
            val record = SimpleperfReportProto.Record.parseFrom(bytes)

            when {
                record.hasFile() -> {
                    val f = record.file
                    files[f.id] = FileEntry(f.path, f.symbolList)
                }

                record.hasSample() -> {
                    val sample = record.sample
                    val weight = sample.eventCount
                    if (weight <= 0L) continue
                    val chain = IntArray(sample.callchainCount * 2)
                    sample.callchainList.forEachIndexed { i, entry ->
                        chain[i * 2] = entry.fileId
                        chain[i * 2 + 1] = entry.symbolId
                    }
                    samples.add(RawSample(weight, chain))
                }
            }
        }

        // Pass 2: now that the symbol tables are known, resolve names and aggregate.
        val root = MutableNode("(root)")
        val selfNanos = HashMap<String, Long>()
        val inclusiveNanos = HashMap<String, Long>()
        var totalNanos = 0L

        for (sample in samples) {
            val weight = sample.weight
            totalNanos += weight
            val frameCount = sample.chain.size / 2
            if (frameCount == 0) continue

            // Resolve leaf-first frame names.
            val names = ArrayList<String>(frameCount)
            for (i in 0 until frameCount) {
                names.add(resolveName(sample.chain[i * 2], sample.chain[i * 2 + 1], files))
            }

            // Self time: innermost (leaf) frame only.
            selfNanos.merge(names.first(), weight, Long::plus)

            // Inclusive time: each distinct frame in the chain (recursion-safe).
            val seen = HashSet<String>()
            for (name in names) {
                if (seen.add(name)) inclusiveNanos.merge(name, weight, Long::plus)
            }

            // Tree: root -> outermost ... -> leaf. callchain is leaf-first, so walk reversed.
            root.totalNanos += weight
            var node = root
            for (i in names.indices.reversed()) {
                node = node.child(names[i])
                node.totalNanos += weight
            }
            node.selfNanos += weight
        }

        val methods =
            inclusiveNanos.entries
                .map { (name, inclusive) ->
                    val self = selfNanos[name] ?: 0L
                    val children = (inclusive - self).coerceAtLeast(0L)
                    CpuMethodRow(
                        name = name,
                        totalMicros = inclusive / 1000,
                        totalPercent = percent(inclusive, totalNanos),
                        childrenMicros = children / 1000,
                        childrenPercent = percent(children, totalNanos),
                    )
                }
                .sortedByDescending { it.totalMicros }
                .take(MAX_METHOD_ROWS)

        return CpuProfile(root = root.freeze(), totalMicros = totalNanos / 1000, methods = methods)
    }

    private fun resolveName(fileId: Int, symbolId: Int, files: Map<Int, FileEntry>): String {
        val file = files[fileId]
        if (file != null && symbolId >= 0 && symbolId < file.symbols.size) {
            return file.symbols[symbolId]
        }
        // Fall back to the shared-object/dex basename so unknown frames are at least attributable.
        val path = file?.path
        if (!path.isNullOrEmpty()) {
            return path.substringAfterLast('/')
        }
        return "unknown"
    }

    private fun percent(part: Long, total: Long): Float =
        if (total > 0) (part.toDouble() / total * 100.0).toFloat() else 0f

    private fun readLe16(data: DataInputStream): Int {
        val b0 = data.read()
        val b1 = data.read()
        if (b0 < 0 || b1 < 0) throw EOFException("Truncated report-sample header")
        return b0 or (b1 shl 8)
    }

    /** Reads a little-endian uint32 length, or null at clean EOF. */
    private fun readLe32(data: DataInputStream): Int? {
        val b0 = data.read()
        if (b0 < 0) return null
        val b1 = data.read()
        val b2 = data.read()
        val b3 = data.read()
        if (b1 < 0 || b2 < 0 || b3 < 0) return null
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
