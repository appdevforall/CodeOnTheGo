/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.LayoutManager
import androidx.viewbinding.ViewBinding
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipManager

/**
 * A fragment which shows a [RecyclerView].
 *
 * @author Akash Yadav
 */
abstract class RecyclerViewFragment<A : RecyclerView.Adapter<*>> :
	EmptyStateFragment<FragmentRecyclerviewManualBinding>(FragmentRecyclerviewManualBinding::inflate) {
	protected abstract val fragmentTooltipTag: String?

	private var unsavedAdapter: A? = null

	private lateinit var gestureDetector: GestureDetector

	private val gestureListener =
		object : GestureDetector.SimpleOnGestureListener() {
			override fun onLongPress(e: MotionEvent) {
				showFragmentTooltip()
			}
		}

	private val touchListener =
		object : RecyclerView.OnItemTouchListener {
			override fun onInterceptTouchEvent(
				rv: RecyclerView,
				e: MotionEvent,
			): Boolean {
				// Pass the event to our gesture detector
				gestureDetector.onTouchEvent(e)
				// Always return false so we don't consume the event
				return false
			}

			override fun onTouchEvent(
				rv: RecyclerView,
				e: MotionEvent,
			) {}

			override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
		}

	override fun onFragmentLongPressed() {
		showFragmentTooltip()
	}

	/**
	 * Creates the adapter for the [RecyclerView].
	 */
	protected abstract fun onCreateAdapter(): RecyclerView.Adapter<*>

	/**
	 * Creates the layout manager for the [RecyclerView].
	 */
	protected open fun onCreateLayoutManager(): LayoutManager = LinearLayoutManager(requireContext())

	/**
	 * Sets up the recycler view in the fragment.
	 */
	protected open fun onSetupRecyclerView() {
		binding.list.apply {
			layoutManager = onCreateLayoutManager()
			adapter = unsavedAdapter ?: onCreateAdapter()
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		gestureDetector = GestureDetector(requireContext(), gestureListener)
		emptyStateBinding?.root?.setOnTouchListener { _, event ->
			gestureDetector.onTouchEvent(event)
			false
		}

		onSetupRecyclerView()

		binding.list.addOnItemTouchListener(touchListener)

		unsavedAdapter = null

		checkIsEmpty()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		unsavedAdapter = null
	}

	/**
	 * Set the adapter for the [RecyclerView].
	 */
	fun setAdapter(adapter: A) {
		_binding?.list?.let { list -> list.adapter = adapter } ?: run { unsavedAdapter = adapter }
        if (isAdded && view != null) {
            checkIsEmpty()
        }
	}

	private fun showFragmentTooltip() {
		val workingContext = context ?: return
		val anchorView = this@RecyclerViewFragment.view ?: return
		val tooltipTag = fragmentTooltipTag ?: return
		TooltipManager.showIdeCategoryTooltip(
			context = workingContext,
			anchorView = anchorView,
			tag = tooltipTag,
		)
	}

	private fun checkIsEmpty() {
        if (!isAdded || isDetached) return
        isEmpty = _binding?.list?.adapter?.itemCount == 0
	}
}

/**
 * Manual [ViewBinding] for [R.layout.fragment_recyclerview] so annotation processors (kapt) do not
 * depend on generated `FragmentRecyclerviewBinding` during stub analysis.
 *
 * Public (not internal/file-private): [RecyclerViewFragment] is public and Kotlin forbids a public
 * class from using a non-public type as a [EmptyStateFragment] type argument.
 *
 * [getRoot] returns [RecyclerView] (covariant override), matching generated view binding so
 * subclasses can use `binding.root.adapter` and other [RecyclerView] APIs.
 */
class FragmentRecyclerviewManualBinding(
	val list: RecyclerView,
) : ViewBinding {
	override fun getRoot(): RecyclerView = list

	companion object {
		fun inflate(
			inflater: LayoutInflater,
			parent: ViewGroup?,
			attachToParent: Boolean,
		): FragmentRecyclerviewManualBinding {
			val root = inflater.inflate(R.layout.fragment_recyclerview, parent, false) as RecyclerView
			return FragmentRecyclerviewManualBinding(root)
		}
	}
}
