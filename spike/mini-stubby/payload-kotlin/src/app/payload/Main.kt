package app.payload

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Framework-only Kotlin payload driven by the persistent warm-compile service.
 *
 * Tier 1 (JVMTI hot-swap) demo, structured so a method-body edit is a TRUE
 * method-body-only change with a STABLE class schema:
 *  - state (`count`, `btnRef`) is @JvmField (public field, no getter/setter)
 *  - the click handler and onTap are public @JvmStatic methods
 *  So the SAM click-listener class touches only PUBLIC members of Main → the
 *  compiler/d8 generates NO synthetic accessor methods on Main, so d8 of just
 *  Main.class produces the same Main schema as the full-app dex, and ART's
 *  RedefineClasses sees only a changed method body (not SCHEMA_CHANGED).
 */
object Main {
    @JvmField var count = 0
    @JvmField var btnRef: Button? = null

    @JvmStatic
    fun render(host: Activity): View {
        val root = LayoutInflater.from(host).inflate(R.layout.main, null)
        root.findViewById<TextView>(R.id.title).text = host.getString(R.string.app)

        val items = listOf("warm JVM", "held classpath", "incremental dex")
        val body = items.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        root.findViewById<TextView>(R.id.body).text = "Why it's fast:\n$body"

        btnRef = root.findViewById<Button>(R.id.btn)
        btnRef!!.setOnClickListener { onClickBtn() }
        onTap()
        return root
    }

    @JvmStatic
    fun onClickBtn() { count++; onTap() }

    // ---- edit THIS body; the service hot-swaps it in place (Tier 1) ----
    @JvmStatic
    fun onTap() {
        btnRef?.text = "tap count = $count"
    }
}
