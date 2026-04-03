package com.itsaky.androidide.dnd

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner


fun Fragment.handleGitUrlDrop(
    targetView: View = requireView(),
    shouldAcceptDrop: () -> Boolean = { isVisible },
    onDropped: (String) -> Unit
) {
    val dropTarget = GitUrlDropTarget(
        context = requireContext(),
        rootView = targetView,
        shouldAcceptDrop = shouldAcceptDrop,
        onRepositoryDropped = onDropped
    )

    dropTarget.attach()

    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            dropTarget.detach()
        }
    })
}
