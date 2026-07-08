package app.payload

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Framework-only Kotlin payload driven by the persistent warm-compile service.
 *
 * Structured for the Tier 1 (JVMTI hot-swap) demo: `count` and `btnRef` are
 * fields on the Main object (survive class redefinition — only method bodies are
 * swapped), and `onTap()` is invoked on every button press. Edit onTap's body and
 * the service redefines Main IN PLACE — the next tap runs the new code with the
 * tap count preserved, no reload.
 */
object Main {
    private var count = 0
    private var btnRef: Button? = null

    @JvmStatic
    fun render(host: Activity): View {
        val root = LayoutInflater.from(host).inflate(R.layout.main, null)
        root.findViewById<TextView>(R.id.title).text = host.getString(R.string.app)

        val items = listOf("warm JVM", "held classpath", "incremental dex")
        val body = items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        root.findViewById<TextView>(R.id.body).text = "Why it's fast:\n$body"

        btnRef = root.findViewById<Button>(R.id.btn)
        btnRef?.setOnClickListener { count++; onTap() }
        onTap()
        return root
    }

    // ---- edit THIS body; the service hot-swaps it in place (Tier 1) ----
    private fun onTap() {
        btnRef?.text = "final n=$count"
    }
}
