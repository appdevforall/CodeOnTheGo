package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.DebuggerCallstackItemBinding
import com.itsaky.androidide.fragments.RecyclerViewFragment
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.DEBUG_OUTPUT_CALLSTACK
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author Akash Yadav
 */
class CallStackFragment : RecyclerViewFragment<CallStackAdapter>() {
	override val fragmentTooltipTag: String = DEBUG_OUTPUT_CALLSTACK
	private val viewHolder by activityViewModels<DebuggerViewModel>()

	private lateinit var gestureDetector: GestureDetector

	private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
		override fun onLongPress(e: MotionEvent) {
            TooltipManager.showTooltip(requireContext(), _binding!!.root, fragmentTooltipTag)
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		gestureDetector = GestureDetector(requireContext(), gestureListener)
		_binding?.root?.setOnTouchListener { _, event ->
			gestureDetector.onTouchEvent(event)
			false
		}

		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewHolder.observeLatestAllFrames(
					scope = this,
					notifyOn = Dispatchers.Main,
				) {
					setAdapter(onCreateAdapter())
				}

				viewHolder.observeLatestSelectedFrame(
					scope = this,
					notifyOn = Dispatchers.Main,
				) { _, index ->
					(_binding?.root?.adapter as? CallStackAdapter?)?.apply {
						val currentSelected = selectedFrameIndex
						selectedFrameIndex = index
						notifyItemChanged(currentSelected)
						notifyItemChanged(index)
					}
				}
			}
		}
	}

	override fun onCreateAdapter() =
		CallStackAdapter(viewLifecycleScope, viewHolder.allFrames.value) { newPosition ->
			viewLifecycleScope.launch {
				viewHolder.setSelectedFrameIndex(newPosition)
			}
		}

}

class CallStackAdapter(
	private val coroutineScope: CoroutineScope,
	private val frames: List<ResolvableStackFrame>,
	private val onItemClickListener: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<CallStackAdapter.VH>() {
	var selectedFrameIndex: Int = 0

	class VH(
		val binding: DebuggerCallstackItemBinding,
	) : RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): VH {
		val inflater = LayoutInflater.from(parent.context)
		val binding = DebuggerCallstackItemBinding.inflate(inflater, parent, false)
		return VH(binding)
	}

	override fun getItemCount() = frames.size

	@SuppressLint("SetTextI18n")
	override fun onBindViewHolder(
		holder: VH,
		position: Int,
	) {
		val binding = holder.binding
		val frame = frames[position]

		if (!frame.isResolved) {
			binding.source.text = binding.root.context.getString(R.string.debugger_status_resolving)
		}

		coroutineScope.launch {
			val descriptor = frame.resolve()
			withContext(Dispatchers.Main) {
				if (descriptor == null) {
					binding.label.text = "<error>"
					return@withContext
				}

				binding.source.text = "${descriptor.sourceFile}:${descriptor.lineNumber}"
				binding.label.text = descriptor.displayText()
				binding.indicator.visibility = if (position == selectedFrameIndex) View.VISIBLE else View.INVISIBLE

				binding.root.setOnClickListener {
					onItemClickListener?.invoke(position)
				}
			}
		}
	}
}