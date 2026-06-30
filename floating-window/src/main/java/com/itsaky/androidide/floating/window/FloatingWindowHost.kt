

package com.itsaky.androidide.floating.window

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
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
 * Owns the lifecycle, view-model store, saved-state registry and back-press dispatcher for a single
 * floating overlay window.
 *
 * A view added through [android.view.WindowManager.addView] is not part of any activity's view
 * hierarchy, so it inherits none of the view-tree owners that Jetpack Compose and the
 * [androidx.fragment.app.FragmentController] require. This host supplies them per window: call
 * [attach] on the window's root view once it is added to the [android.view.WindowManager], and
 * [destroy] when the window is removed.
 */
class FloatingWindowHost :
	LifecycleOwner,
	ViewModelStoreOwner,
	SavedStateRegistryOwner,
	OnBackPressedDispatcherOwner {
	private val lifecycleRegistry = LifecycleRegistry(this)
	private val savedStateRegistryController = SavedStateRegistryController.create(this)

	override val lifecycle: Lifecycle
		get() = lifecycleRegistry

	override val viewModelStore: ViewModelStore = ViewModelStore()

	override val savedStateRegistry: SavedStateRegistry
		get() = savedStateRegistryController.savedStateRegistry

	override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

	init {
		savedStateRegistryController.performRestore(null)
	}

	fun attach(root: View) {
		root.setViewTreeLifecycleOwner(this)
		root.setViewTreeViewModelStoreOwner(this)
		root.setViewTreeSavedStateRegistryOwner(this)
		root.setViewTreeOnBackPressedDispatcherOwner(this)
		lifecycleRegistry.currentState = Lifecycle.State.RESUMED
	}

	fun pause() {
		if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
			lifecycleRegistry.currentState = Lifecycle.State.STARTED
		}
	}

	fun resume() {
		if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
			lifecycleRegistry.currentState = Lifecycle.State.RESUMED
		}
	}

	fun destroy() {
		lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
		viewModelStore.clear()
	}
}
