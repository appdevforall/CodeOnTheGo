package org.appdevforall.cotg.profiler

import org.appdevforall.cotg.profiler.heap.HeapDominators
import org.appdevforall.cotg.profiler.heap.HeapProfile
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import java.util.Locale

/**
 * Parses a `.hprof` heap dump into a [HeapProfile]: the object **dominator tree** (what each object
 * retains — the flamegraph-ready structure, see [HeapDominators]) plus the per-class histogram
 * (count + shallow size) kept for the "Class List" table, mirroring Android Studio's memory profiler.
 */
object HeapDumpAnalyzer {
    private const val MAX_ROWS = 50

    private class ClassStats {
        var count: Long = 0
        var shallowBytes: Long = 0
    }

    /** Builds the dominator tree + top [MAX_ROWS] class histogram. Blocking/IO heavy — call off the main thread. */
    fun analyze(file: File): HeapProfile {
        val byClass = HashMap<String, ClassStats>()

        file.openHeapGraph().use { graph ->
            for (obj in graph.objects) {
                val className: String
                val shallow: Int
                when (obj) {
                    is HeapInstance -> {
                        className = obj.instanceClassName
                        shallow = obj.byteSize
                    }
                    is HeapObjectArray -> {
                        className = obj.arrayClassName
                        shallow = obj.byteSize
                    }
                    is HeapPrimitiveArray -> {
                        className = obj.arrayClassName
                        shallow = obj.byteSize
                    }
                    else -> continue // class/constant objects are not histogrammed
                }
                val stats = byClass.getOrPut(className) { ClassStats() }
                stats.count++
                stats.shallowBytes += shallow
            }

            val rows = byClass.entries
                .sortedByDescending { it.value.shallowBytes }
                .take(MAX_ROWS)
                .map { (className, stats) ->
                    ProfilerTableRow(
                        id = className,
                        cells = listOf(className, formatCount(stats.count), formatBytes(stats.shallowBytes)),
                    )
                }

            val root = HeapDominators.fromHeapGraph(graph)
            return HeapProfile(
                root = root,
                totalRetainedBytes = root.retainedBytes,
                totalObjects = root.retainedCount,
                rows = rows,
            )
        }
    }

    private fun formatCount(count: Long): String = String.format(Locale.US, "%,d", count)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
