

package com.itsaky.androidide.floating.fragment

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.core.view.LayoutInflaterCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentController
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentHostCallback
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import com.google.android.material.theme.MaterialComponentsViewInflater
import com.itsaky.androidide.floating.window.FloatingWindowHost

/**
 * Hosts a single [Fragment] with no Activity, inside a floating overlay window.
 *
 * A [FragmentManager] normally requires a [androidx.fragment.app.FragmentActivity]. This drives one
 * directly via a [FragmentController] over a [FragmentHostCallback], delegating the view-model
 * store, lifecycle, saved-state registry and back-press dispatcher to the window's
 * [FloatingWindowHost]. That is what lets plugin tab Fragments float over other apps and survive the
 * editor activity being destroyed.
 *
 * The hosted Fragment is re-instantiated here (not moved across FragmentManagers, which Android does
 * not support); callers supply a [FragmentFactory] so plugin classloaders still resolve.
 */
class OverlayFragmentHost(
	private val context: Context,
	private val owner: FloatingWindowHost,
	fragmentFactory: FragmentFactory? = null,
) {
	private val handler = Handler(Looper.getMainLooper())

	private val viewFactory = MaterialViewFactory()

	private val container: FrameLayout =
		FrameLayout(context).apply { id = View.generateViewId() }

	private val hostCallback =
		object :
			FragmentHostCallback<Context>(context, handler, 0),
			ViewModelStoreOwner,
			LifecycleOwner,
			SavedStateRegistryOwner,
			OnBackPressedDispatcherOwner {
			override fun onGetHost(): Context = context

			override fun onFindViewById(id: Int): View? = container.findViewById(id)

			override fun onHasView(): Boolean = true

			override fun onGetLayoutInflater(): LayoutInflater {
				val themed = LayoutInflater.from(context).cloneInContext(context)
				LayoutInflaterCompat.setFactory2(themed, viewFactory)
				return themed.cloneInContext(context)
			}

			override val viewModelStore: ViewModelStore
				get() = owner.viewModelStore

			override val lifecycle: Lifecycle
				get() = owner.lifecycle

			override val savedStateRegistry: SavedStateRegistry
				get() = owner.savedStateRegistry

			override val onBackPressedDispatcher: OnBackPressedDispatcher
				get() = owner.onBackPressedDispatcher
		}

	private val controller: FragmentController = FragmentController.createController(hostCallback)

	private var started = false

	val view: View
		get() = container

	val fragmentManager: FragmentManager
		get() = controller.supportFragmentManager

	init {
		if (fragmentFactory != null) {
			controller.supportFragmentManager.fragmentFactory = fragmentFactory
		}
	}

	fun start() {
		if (started) return
		controller.attachHost(null)
		controller.dispatchCreate()
		controller.dispatchActivityCreated()
		controller.dispatchStart()
		controller.dispatchResume()
		started = true
	}

	fun setFragment(fragment: Fragment) {
		if (!started) start()
		controller.supportFragmentManager
			.beginTransaction()
			.replace(container.id, fragment)
			.commitNowAllowingStateLoss()
	}

	fun destroy() {
		if (!started) return
		controller.dispatchPause()
		controller.dispatchStop()
		controller.dispatchDestroyView()
		controller.dispatchDestroy()
		started = false
	}
}

/**
 * Restores the AppCompat/Material widget substitution that a window without an activity loses.
 *
 * An activity installs AppCompat's view factory on its [LayoutInflater], and that is what turns an
 * unqualified `<Button>` in a layout into a `MaterialButton`. [OverlayFragmentHost] drives a
 * FragmentManager with no activity, so without this the same layout inflates plain framework
 * widgets and a plugin's UI visibly changes the moment it is undocked.
 *
 * The inflater handed to a Fragment must still accept the child FragmentManager's own factory, and
 * [LayoutInflater] refuses a second `setFactory2`. Cloning after installing this one carries the
 * factory across with that flag cleared, so the two are merged instead of colliding.
 */
private class MaterialViewFactory : LayoutInflater.Factory2 {
	private val inflater = MaterialComponentsViewInflater()

	override fun onCreateView(
		parent: View?,
		name: String,
		context: Context,
		attrs: AttributeSet,
	): View? = inflater.createView(parent, name, context, attrs, false, false, true, false)

	override fun onCreateView(
		name: String,
		context: Context,
		attrs: AttributeSet,
	): View? = onCreateView(null, name, context, attrs)
}
