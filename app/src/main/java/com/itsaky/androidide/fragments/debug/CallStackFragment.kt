package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.DebuggerCallstackItemBinding
import com.itsaky.androidide.fragments.RecyclerViewFragment
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers

/**
 * @author Akash Yadav
 */
class CallStackFragment : RecyclerViewFragment<CallStackAdapter>() {

    private val viewHolder by viewModels<DebuggerViewModel>(ownerProducer = { requireActivity() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewHolder.observeLatestAllFrames(
            notifyOn = Dispatchers.Main
        ) {
            setAdapter(onCreateAdapter())
        }

        viewHolder.observeLatestSelectedFrame(
            notifyOn = Dispatchers.Main
        ) { _, index ->
            (_binding?.root?.adapter as? CallStackAdapter?)?.apply {
                val currentSelected = selectedFrameIndex
                selectedFrameIndex = index
                notifyItemChanged(currentSelected)
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateAdapter() = CallStackAdapter(viewHolder.allFrames.value) { newPosition ->
        viewHolder.setSelectedFrameIndex(newPosition)
    }
}

class CallStackAdapter(
    private val frames: List<EagerStackFrame>,
    private val onItemClickListener: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<CallStackAdapter.VH>() {

    var selectedFrameIndex: Int = 0

    class VH(
        val binding: DebuggerCallstackItemBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = DebuggerCallstackItemBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun getItemCount() = frames.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        val frame = frames[position]

        if (!frame.isResolved) {
            binding.source.text = "Resolving..."
            return
        }

        val descriptor = frame.resolved
        binding.source.text = "${descriptor.sourceFile}:${descriptor.lineNumber}"
        binding.label.text = descriptor.displayText()
        binding.indicator.visibility = if (position == selectedFrameIndex) View.VISIBLE else View.INVISIBLE

        binding.root.setOnClickListener {
            onItemClickListener?.invoke(position)
        }
    }
}