package app.payload

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Jetpack Compose payload. The shell hosts a PLAIN android.app.Activity, which
 * provides none of the ViewTree owners a ComposeView walks up the tree to find
 * (Lifecycle / ViewModelStore / SavedStateRegistry). So the payload supplies its
 * OWN owners — from its own bundled androidx — drives the lifecycle to RESUMED,
 * and attaches them to the ComposeView before setContent.
 *
 * MaterialTheme{} is intentionally omitted (a compose-compiler 1.5.10 IR-lowering
 * quirk on the defaulted MaterialTheme call) — the point here is proving the
 * ComposeView + owners hosting mechanism, not Material3 theming, which the XML
 * Material3 payload already proved.
 */
object Main {

    private class PayloadOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry
        fun resume() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    @JvmStatic
    fun render(host: Activity): View {
        val owner = PayloadOwner()
        owner.resume()
        // Compose's window recomposer walks UP the view tree from the attached
        // ComposeView to find the lifecycle owner, and reaches the shell's own
        // root (a plain LinearLayout) which has none. Setting the owners on the
        // host window's decor view guarantees the lookup succeeds no matter where
        // in the shell hierarchy the ComposeView is mounted.
        host.window.decorView.let {
            it.setViewTreeLifecycleOwner(owner)
            it.setViewTreeViewModelStoreOwner(owner)
            it.setViewTreeSavedStateRegistryOwner(owner)
        }
        return ComposeView(host).apply {
            setBackgroundColor(Color.WHITE)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                var n by remember { mutableStateOf(0) }
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Jetpack Compose payload", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("hot-loaded into a plain Activity shell",
                        modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { n++ }, modifier = Modifier.padding(top = 24.dp)) {
                        Text("Recompositions: $n")
                    }
                }
            }
        }
    }
}
