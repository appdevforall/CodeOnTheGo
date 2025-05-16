package com.itsaky.androidide.fragments.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.common.databinding.LayoutSimpleIconTextBinding
import com.itsaky.androidide.fragments.RecyclerViewFragment
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import kotlinx.coroutines.Dispatchers

/**
 * @author Akash Yadav
 */
class VariableListFragment : RecyclerViewFragment<VariableListAdapter>() {

    private val viewModel by viewModels<DebuggerViewModel>(ownerProducer = { requireActivity() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.observeLatestSelectedFrame(
            notifyOn = Dispatchers.Main
        ) { _, _ ->
            setAdapter(onCreateAdapter())
        }
    }

    override fun onCreateAdapter(): VariableListAdapter =
        VariableListAdapter(viewModel.selectedFrameVariables.value)
}

class VariableListAdapter(
    private val variables: List<Variable<*>>
) : RecyclerView.Adapter<VariableListAdapter.VH>() {

    class VH(
        val binding: LayoutSimpleIconTextBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding =
            LayoutSimpleIconTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int {
        return variables.size
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        val variable = variables[position]
        binding.text.text = "${variable.name} = ${variable.typeName} ${variable.value()}"
    }
}
