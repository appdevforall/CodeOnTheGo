package org.appdevforall.cotg.profiler.ui

import org.appdevforall.cotg.profiler.ui.components.ProfilerTableRow

internal object SampleProfileTables {
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
