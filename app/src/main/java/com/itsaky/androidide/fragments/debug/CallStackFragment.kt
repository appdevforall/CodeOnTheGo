package com.itsaky.androidide.fragments.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.common.databinding.LayoutSimpleIconTextBinding
import com.itsaky.androidide.fragments.RecyclerViewFragment
import com.itsaky.androidide.lsp.debug.model.StackFrame
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers

/**
 * @author Akash Yadav
 */
class CallStackFragment : RecyclerViewFragment<CallStackAdapter>() {

    private val viewModel by viewModels<DebuggerViewModel>(ownerProducer = { requireActivity() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.observeLatestSelectedFrame(
            notifyOn = Dispatchers.Main
        ) { _, _ ->
            setAdapter(onCreateAdapter())
        }
    }

    override fun onCreateAdapter(): CallStackAdapter {
        return CallStackAdapter(viewModel.selectedThread.value.first?.getFrames() ?: emptyList())
    }
}

class CallStackAdapter(
    private val frames: List<StackFrame>
): RecyclerView.Adapter<CallStackAdapter.VH>() {

    class VH(
        val binding: LayoutSimpleIconTextBinding
    ): RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = LayoutSimpleIconTextBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int {
        return frames.size
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame = frames[position]
        val binding = holder.binding
        binding.text.text = frame.toString()
    }
}