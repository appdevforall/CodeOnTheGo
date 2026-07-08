package app.payload

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentController
import androidx.fragment.app.FragmentHostCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * androidx Fragment payload. A plain android.app.Activity has no androidx
 * FragmentManager (that comes from FragmentActivity). Same fix pattern as the
 * Compose payload: the payload supplies its OWN host — a FragmentController +
 * FragmentHostCallback backed by payload-created Lifecycle/ViewModelStore/
 * SavedStateRegistry/OnBackPressedDispatcher owners — drives it to RESUMED, and
 * commits a Fragment into a container it owns.
 */
object Main {

    private const val CONTAINER_ID = 0x0f0f01

    private class Host(private val activity: Activity, private val container: ViewGroup) :
        LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)
        private val backDispatcher = OnBackPressedDispatcher()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

        private val hostCallback =
            object : FragmentHostCallback<Host>(activity, Handler(Looper.getMainLooper()), 0) {
                override fun onGetHost(): Host = this@Host
                override fun onFindViewById(id: Int): View? =
                    if (id == container.id) container else container.findViewById(id)
                // Must be a CLONE, not the Activity's own inflater: the shell
                // already called setFactory2 on that one, and Fragment insists on
                // setting its own factory (throws "A factory has already been
                // set" otherwise). cloneInContext resets the factory-set flag
                // while still inheriting the shell's custom-view Factory2.
                override fun onGetLayoutInflater(): LayoutInflater =
                    LayoutInflater.from(activity).cloneInContext(activity)
            }

        val controller: FragmentController = FragmentController.createController(hostCallback)
        val fragmentManager get() = controller.supportFragmentManager

        fun start() {
            savedStateController.performRestore(null)
            controller.attachHost(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            controller.dispatchCreate()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            controller.dispatchActivityCreated()
            controller.dispatchStart()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            controller.dispatchResume()
        }
    }

    class DemoFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, parent: ViewGroup?, s: android.os.Bundle?,
        ): View {
            val col = LinearLayout(requireContext())
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            col.setPadding(48, 48, 48, 48)
            var taps = 0
            val label = TextView(requireContext()).apply {
                text = "androidx Fragment hosted in a plain-Activity shell"
                setTextColor(Color.parseColor("#FF102A43")); textSize = 18f; gravity = Gravity.CENTER
            }
            val btn = Button(requireContext()).apply {
                text = "Fragment taps: 0"
                setOnClickListener { taps++; text = "Fragment taps: $taps" }
            }
            col.addView(label)
            col.addView(btn)
            return col
        }
    }

    @JvmStatic
    fun render(host: Activity): View {
        val container = FrameLayout(host).apply {
            id = CONTAINER_ID
            setBackgroundColor(Color.WHITE)
        }
        val h = Host(host, container)
        h.start()
        h.fragmentManager.beginTransaction()
            .add(container.id, DemoFragment())
            .commitNow()
        return container
    }
}
