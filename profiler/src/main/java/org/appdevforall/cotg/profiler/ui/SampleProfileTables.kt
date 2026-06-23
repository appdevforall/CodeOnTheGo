package org.appdevforall.cotg.profiler.ui

import org.appdevforall.cotg.profiler.heap.HeapObjectNode
import org.appdevforall.cotg.profiler.heap.HeapProfile
import org.appdevforall.cotg.profiler.model.ProcessInfo
import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow

internal object SampleProfileTables {
    val SAMPLE_PROCESSES: List<ProcessInfo> =
        listOf(
            ProcessInfo(1287, "com.example.app", "Example App", debuggable = true, profileable = true),
            ProcessInfo(1422, "com.example.app:service", "Example App (service)", debuggable = true, profileable = false),
            ProcessInfo(2051, "org.appdevforall.cotg", "Code on the Go", debuggable = false, profileable = true),
            ProcessInfo(3310, "com.example.game", "Example Game", debuggable = false, profileable = true),
        )

    val HEAP_ROWS: List<ProfilerTableRow> =
        listOf(
            ProfilerTableRow("byte[]", listOf("byte[]", "1,284", "4.2 MB", "4.2 MB")),
            ProfilerTableRow("String", listOf("java.lang.String", "9,210", "221 KB", "1.1 MB")),
            ProfilerTableRow("Bitmap", listOf("android.graphics.Bitmap", "42", "3.9 KB", "3.1 MB")),
            ProfilerTableRow("Object[]", listOf("java.lang.Object[]", "2,005", "96 KB", "812 KB")),
            ProfilerTableRow("HashMapNode", listOf("java.util.HashMap\$Node", "5,120", "164 KB", "640 KB")),
            ProfilerTableRow("MainActivity", listOf("com.example.app.MainActivity", "1", "120 B", "2.4 MB")),
            ProfilerTableRow("ArrayList", listOf("kotlin.collections.ArrayList", "1,540", "37 KB", "498 KB")),
            ProfilerTableRow("Class", listOf("java.lang.Class", "3,402", "272 KB", "421 KB")),
            ProfilerTableRow("LinkedHashMap", listOf("java.util.LinkedHashMap", "612", "29 KB", "318 KB")),
            ProfilerTableRow("Drawable", listOf("android.graphics.drawable.VectorDrawable", "88", "11 KB", "204 KB")),
        )

    // A small, internally-consistent dominator tree (retained >= sum of children) for previews.
    val SAMPLE_HEAP_PROFILE: HeapProfile =
        HeapProfile(
            root = HeapObjectNode(
                label = "(heap)", shallowBytes = 0, retainedBytes = 8_000_000, retainedCount = 20_000,
                children = listOf(
                    HeapObjectNode("byte[]", shallowBytes = 4_200_000, retainedBytes = 4_200_000, retainedCount = 1_284, children = emptyList()),
                    HeapObjectNode(
                        "com.example.app.MainActivity", shallowBytes = 120, retainedBytes = 2_400_000, retainedCount = 8_000,
                        children = listOf(
                            HeapObjectNode("android.graphics.Bitmap", shallowBytes = 3_900, retainedBytes = 2_000_000, retainedCount = 50, children = emptyList()),
                            HeapObjectNode("java.util.HashMap\$Node", shallowBytes = 164_000, retainedBytes = 360_000, retainedCount = 5_120, children = emptyList()),
                        ),
                    ),
                    HeapObjectNode("java.lang.String", shallowBytes = 221_000, retainedBytes = 1_100_000, retainedCount = 9_210, children = emptyList()),
                ),
            ),
            totalRetainedBytes = 8_000_000,
            totalObjects = 20_000,
            rows = HEAP_ROWS,
        )

    val CPU_ROWS: List<ProfilerTableRow> =
        listOf(
            ProfilerTableRow("doFrame", listOf("android.view.Choreographer.doFrame", "482", "1,530")),
            ProfilerTableRow("pollOnce", listOf("libc.so nativePollOnce", "388", "388")),
            ProfilerTableRow("decode", listOf("Bitmap.nativeDecodeByteArray", "276", "441")),
            ProfilerTableRow("draw", listOf("android.view.View.draw", "214", "902")),
            ProfilerTableRow("layout", listOf("RecyclerView.onLayout", "165", "588")),
            ProfilerTableRow("gc", listOf("art::gc::ConcurrentMark", "151", "151")),
            ProfilerTableRow("dispatch", listOf("okhttp3.Dispatcher.execute", "84", "256")),
            ProfilerTableRow("jni", listOf("JNI stringFromJNI", "47", "47")),
        )
}
