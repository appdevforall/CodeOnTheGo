package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.forms.plugin.R

/**
 * Step 3 of 4 — Semantic rules.
 *
 * Lists the fields gathered in step 2 and lets the user toggle required /
 * reusable. C4 will add postal-code lookup as a third per-field option.
 */
class Step3RulesFragment : Fragment() {

    private val viewModel: WizardViewModel by activityViewModels()
    private lateinit var adapter: RulesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_wizard_step3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.forms_wizard_step3_rules)
        adapter = RulesAdapter { id, required, reusable ->
            viewModel.setFieldRules(id, required, reusable)
        }
        rv.adapter = adapter
        rv.layoutManager = LinearLayoutManager(requireContext())

        viewModel.fields.observe(viewLifecycleOwner) { fields ->
            adapter.submitList(fields)
        }
    }
}
