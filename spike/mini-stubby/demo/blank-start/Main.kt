package app.payload

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Blank "before" payload for the on-camera Ask-Claude demo. Deliberately trivial:
 * an empty stage that says "tap Ask Claude to build me", so prompt #1 visibly builds
 * the whole Lemonade Stand game from scratch on-device. Contract-correct entry point
 * (object Main / @JvmStatic render) so the shell loads it like any generated payload.
 */
object Main {
    @JvmStatic
    fun render(host: Activity): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFF6FFF6"))
            // Leave room for the shell's top status strip on reload.
            setPadding(64, 96, 64, 64)
        }
        root.addView(TextView(host).apply {
            text = "🍋  Lemonade Stand"
            textSize = 30f
            setTextColor(Color.parseColor("#FF1B5E20"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(host).apply {
            text = "Empty app — tap “Ask Claude” to build the game."
            textSize = 16f
            setTextColor(Color.parseColor("#FF555555"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })
        return root
    }
}
