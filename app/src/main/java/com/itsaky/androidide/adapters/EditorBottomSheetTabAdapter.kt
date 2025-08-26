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
package com.itsaky.androidide.adapters

import androidx.collection.LongSparseArray
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.itsaky.androidide.fragments.DiagnosticsListFragment
import com.itsaky.androidide.fragments.SearchResultFragment
import com.itsaky.androidide.fragments.debug.DebuggerFragment
import com.itsaky.androidide.fragments.output.AppLogFragment
import com.itsaky.androidide.fragments.output.BuildOutputFragment
import com.itsaky.androidide.fragments.output.IDELogFragment
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.reflect.Constructor

class EditorBottomSheetTabAdapter(
    fragmentActivity: FragmentActivity,
) : FragmentStateAdapter(fragmentActivity) {
    private val allTabs =
        mutableListOf<Tab>().apply {
            @Suppress("KotlinConstantConditions")
            add(
                Tab(
                    title = fragmentActivity.getString(R.string.build_output),
                    fragmentClass = BuildOutputFragment::class.java,
                    itemId = size.toLong()
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.app_logs),
                    fragmentClass = AppLogFragment::class.java,
                    itemId = size.toLong(),
                    tooltipTag = TooltipTag.PROJECT_APP_LOGS
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.ide_logs),
                    fragmentClass = IDELogFragment::class.java,
                    itemId = size.toLong(),
                    tooltipTag = TooltipTag.PROJECT_IDE_LOGS
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.view_diags),
                    fragmentClass = DiagnosticsListFragment::class.java,
                    itemId = size.toLong(),
                    tooltipTag = TooltipTag.PROJECT_SEARCH_RESULTS
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.view_search_results),
                    fragmentClass = SearchResultFragment::class.java,
                    itemId = size.toLong(),
                    tooltipTag = TooltipTag.PROJECT_DIAGNOSTICS
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.debugger_title),
                    fragmentClass = DebuggerFragment::class.java,
                    itemId = size.toLong(),
                    tooltipTag = TooltipTag.PROJECT_DEBUGGER_OUTPUT
                ),
            )
        }

    private val tabs = MutableList(allTabs.size) { allTabs[it] }

    fun removeFragment(klass: Class<out Fragment>) {
        val index = findIndexOfFragmentByClass(klass)
        if (index == -1) {
            return
        }

        tabs.removeAt(index)
        notifyItemRemoved(index)
    }

    fun restoreFragment(klass: Class<out Fragment>): Boolean {
        if (findFragmentByClass(klass) != null) {
            return false
        }

        val originalIndex = allTabs.indexOfFirst { it.fragmentClass == klass }
        if (originalIndex == -1) return false

        // Calculate where to insert based on which tabs are currently shown
        var insertIndex = 0
        for (i in 0 until originalIndex) {
            val tab = allTabs[i]
            if (tabs.contains(tab)) {
                insertIndex++
            }
        }

        val tabToRestore = allTabs[originalIndex]
        tabs.add(insertIndex, tabToRestore)
        notifyItemInserted(insertIndex)
        return true
    }

    fun toggleFragment(klass: Class<out Fragment>): Boolean {
        val index = findIndexOfFragmentByClass(klass)
        return if (index != -1) {
            removeFragment(klass)
            false
        } else {
            restoreFragment(klass)
            true
        }
    }

    fun setFragmentVisibility(
        klass: Class<out Fragment>,
        isVisible: Boolean,
    ) = if (isVisible) restoreFragment(klass) else removeFragment(klass)

    fun getFragmentAtIndex(index: Int): Fragment? = getFragmentById(getItemId(index))

    private fun getFragmentById(itemId: Long): Fragment? {
        val fragments = getFragments()
        if (fragments != null) {
            return fragments[itemId]
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun getFragments(): LongSparseArray<Fragment?>? {
        try {
            val field = FragmentStateAdapter::class.java.getDeclaredField("mFragments")
            field.isAccessible = true
            return field.get(this) as LongSparseArray<Fragment?>?
        } catch (th: Throwable) {
            logger.error("Unable to reflect fragment list from adapter.", th)
        }

        return null
    }

    override fun createFragment(position: Int): Fragment {
        try {
            val tab = tabs[position]
            val klass = tab.fragmentClass
            val constructor: Constructor<out Fragment> = klass.getDeclaredConstructor()
            constructor.isAccessible = true
            return constructor.newInstance()
        } catch (th: Throwable) {
            throw RuntimeException("Unable to create fragment", th)
        }
    }

    override fun getItemCount(): Int = tabs.size

    fun getTitle(position: Int): String? = tabs[position].title

    val buildOutputFragment: BuildOutputFragment?
        get() = findFragmentByClass(BuildOutputFragment::class.java)

    val logFragment: AppLogFragment?
        get() = findFragmentByClass(AppLogFragment::class.java)

    val diagnosticsFragment: DiagnosticsListFragment?
        get() = findFragmentByClass(DiagnosticsListFragment::class.java)

    val searchResultFragment: SearchResultFragment?
        get() = findFragmentByClass(SearchResultFragment::class.java)

    fun <T : Fragment?> findIndexOfFragmentByClass(tClass: Class<T>): Int {
        val pair = findTabAndIndexByClass(tClass)
        if (pair == null) {
            return -1
        }

        return pair.second
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Fragment> findFragmentByClass(clazz: Class<out T>): T? {
        for (tab in tabs) {
            if (tab.fragmentClass == clazz) {
                return getFragmentById(tab.itemId) as T?
            }
        }

        return null
    }

    private fun <T : Fragment?> findTabAndIndexByClass(tClass: Class<T>): Pair<Tab, Int>? {
        for ((index, tab) in this.tabs.withIndex()) {
            if (tab.fragmentClass == tClass) {
                return tab to index
            }
        }

        return null
    }

    internal data class Tab(
        val title: String,
        val fragmentClass: Class<out Fragment>,
        val itemId: Long,
        val tooltipTag: String? = null
    )

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(EditorBottomSheetTabAdapter::class.java)
    }

    fun getTooltipTag(position: Int): String? {
        return allTabs[position].tooltipTag
    }
}
