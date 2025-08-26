package com.itsaky.androidide.utils

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlin.jvm.Throws

/**
 * The [CoroutineScope] attached to the fragment's [view lifecycle owner][Fragment.getViewLifecycleOwner].
 *
 * This must be called only after [Fragment.onCreateView] and before [Fragment.onDestroyView].
 */
@get:Throws(IllegalStateException::class)
val Fragment.viewLifecycleScope: CoroutineScope
	get() = viewLifecycleOwner.lifecycleScope

/**
 * The [CoroutineScope] attached to the fragment's
 * [view lifecycle owner][Fragment.getViewLifecycleOwner], or `null` if the
 * fragment's view has already been destroyed or not yet created.
 */
val Fragment.viewLifecycleScopeOrNull: CoroutineScope?
	get() = try {
		viewLifecycleScope
	} catch (e: IllegalStateException) {
		// invalid state
		null
	}
