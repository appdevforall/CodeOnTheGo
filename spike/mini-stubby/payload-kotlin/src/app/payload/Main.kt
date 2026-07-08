package app.payload

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Framework-only Kotlin payload driven by the persistent warm-compile service.
 * Uses kotlin-stdlib features (collections, string templates, lambdas) so the
 * cached stdlib dex is genuinely exercised. Edit this file and save — the warm
 * service recompiles just this class and hot-reloads.
 */
object Main {
    private const val HEADLINE = "tiers"

    @JvmStatic
    fun render(host: Activity): View {
        val root = LayoutInflater.from(host).inflate(R.layout.main, null)
        root.findViewById<TextView>(R.id.title).text =
            host.getString(R.string.app) + " · " + HEADLINE

        val items = listOf("warm JVM", "held classpath", "incremental dex")
        val body = items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        root.findViewById<TextView>(R.id.body).text = "Why it's fast:\n$body"

        val btn = root.findViewById<Button>(R.id.btn)
        var count = 0
        btn.text = "tap count = 0"
        btn.setOnClickListener { count++; btn.text = "tap count = $count" }
        return root
    }
}
