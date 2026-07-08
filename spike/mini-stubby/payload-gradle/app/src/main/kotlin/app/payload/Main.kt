package app.payload

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/** Kotlin + Material3 payload — CoGo's default language. */
object Main {
    @JvmStatic
    fun render(host: Activity): View {
        val root = LayoutInflater.from(host).inflate(R.layout.main, null)
        var n = 0
        root.findViewById<MaterialButton>(R.id.btn).setOnClickListener {
            Snackbar.make(root, "Kotlin tap v3 v2 v1 ${++n}", Snackbar.LENGTH_SHORT).show()
        }
        return root
    }
}
