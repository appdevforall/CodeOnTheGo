package com.codeonthego.gisplugin.region

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.GisPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * Bottom-sheet tab that lists cached map regions.
 *
 * Lifecycle:
 *  - `onCreateView` inflates the layout via [PluginFragmentHelper.getPluginInflater]
 *    so the IDE's resource resolver finds our XML.
 *  - `onResume` re-loads the cache. We deliberately do this on every resume
 *    rather than holding a long-lived listener: the cache is changed by the
 *    wizard (in another Activity) and by the user deleting/redownloading from
 *    inside this fragment, so a refresh-on-resume pattern catches both
 *    without the complexity of a content observer.
 *
 * C1: empty cache renders the empty state, period. Delete / re-download wire-up
 * lands in C3 once C2 has provided real regions to act on.
 */
class RegionManagerFragment : Fragment() {

    private companion object {
        const val PLUGIN_ID = "com.codeonthego.gisplugin"
    }

    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private val adapter = RegionAdapter()

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_region_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.regions_list)
        emptyState = view.findViewById(R.id.empty_state)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Reload from disk and toggle empty / list visibility accordingly. */
    private fun refresh() {
        val items = RegionCache.list()
        adapter.submit(items)
        val isEmpty = items.isEmpty()
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }
}
