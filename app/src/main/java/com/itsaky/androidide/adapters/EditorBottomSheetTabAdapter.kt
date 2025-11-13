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
import com.itsaky.androidide.agent.fragments.AgentFragmentContainer
import com.itsaky.androidide.app.IDEApplication.Companion.getPluginManager
import com.itsaky.androidide.fragments.DiagnosticsListFragment
import com.itsaky.androidide.fragments.SearchResultFragment
import com.itsaky.androidide.fragments.debug.DebuggerFragment
import com.itsaky.androidide.fragments.output.AppLogFragment
import com.itsaky.androidide.fragments.output.BuildOutputFragment
import com.itsaky.androidide.fragments.output.IDELogFragment
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.resources.R
import org.slf4j.LoggerFactory
import java.lang.reflect.Constructor


class EditorBottomSheetTabAdapter(
	fragmentActivity: FragmentActivity,
) : FragmentStateAdapter(fragmentActivity) {
	companion object {
		private val logger = LoggerFactory.getLogger(EditorBottomSheetTabAdapter::class.java)

		// These constants correspond their actual position
		// in the tabs list. Any update here requires updating
		// BottomSheetViewModel.
		const val TAB_BUILD_OUTPUT = 0
		const val TAB_APPLICATION_LOGS = 1
		const val TAB_IDE_LOGS = 2
		const val TAB_DIAGNOSTICS = 3
		const val TAB_SEARCH_RESULTS = 4
		const val TAB_DEBUGGER = 5
        const val TAB_AGENT = 6
	}

	private val allTabs =
		mutableListOf<Tab>().apply {
			add(
				Tab(
					title = fragmentActivity.getString(R.string.build_output),
					fragmentClass = BuildOutputFragment::class.java,
					itemId = TAB_BUILD_OUTPUT,
					tooltipTag = TooltipTag.PROJECT_BUILD_OUTPUT,
				),
			)

			add(
				Tab(
					title = fragmentActivity.getString(R.string.app_logs),
					fragmentClass = AppLogFragment::class.java,
					itemId = TAB_APPLICATION_LOGS,
					tooltipTag = TooltipTag.PROJECT_APP_LOGS,
				),
			)

			add(
				Tab(
					title = fragmentActivity.getString(R.string.ide_logs),
					fragmentClass = IDELogFragment::class.java,
					itemId = TAB_IDE_LOGS,
					tooltipTag = TooltipTag.PROJECT_IDE_LOGS,
				),
			)

			add(
				Tab(
					title = fragmentActivity.getString(R.string.view_diags),
					fragmentClass = DiagnosticsListFragment::class.java,
					itemId = TAB_DIAGNOSTICS,
					tooltipTag = TooltipTag.PROJECT_DIAGNOSTICS,
				),
			)

			add(
				Tab(
					title = fragmentActivity.getString(R.string.view_search_results),
					fragmentClass = SearchResultFragment::class.java,
					itemId = TAB_SEARCH_RESULTS,
					tooltipTag = TooltipTag.PROJECT_SEARCH_RESULTS,
				),
			)

			add(
				Tab(
					title = fragmentActivity.getString(R.string.debugger_title),
					fragmentClass = DebuggerFragment::class.java,
					itemId = TAB_DEBUGGER,
                    tooltipTag = TooltipTag.PROJECT_DEBUGGER_OUTPUT,
                ),
            )

            add(
                Tab(
                    title = fragmentActivity.getString(R.string.title_agent),
                    fragmentClass = AgentFragmentContainer::class.java,
                    itemId = TAB_AGENT,
                    tooltipTag = TooltipTag.PROJECT_AGENT,
				),
			)
		}

	private val tabs = MutableList(allTabs.size) { allTabs[it] }
	private val pluginFragmentFactories = mutableMapOf<Long, () -> Fragment>()

	init {
		addPluginTabs()
	}

	fun clearAll() {
		val size = tabs.size
		if (size == 0) return
		tabs.clear()
		pluginFragmentFactories.clear()
		notifyDataSetChanged()
	}

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

	/**
	 * Set the visibility of a fragment.
	 *
	 * @param klass The fragment class to show/hide.
	 * @param isVisible Whether to show or hide the fragment.
	 * @return `true` if fragment's state changed from hidden to visible, `false`
	 * 			otherwise.
	 */
	fun setFragmentVisibility(
		klass: Class<out Fragment>,
		isVisible: Boolean,
	): Boolean {
		if (isVisible) {
			return restoreFragment(klass)
		}

		removeFragment(klass)
		return false
	}

	fun <T : Fragment> getFragmentAtIndex(index: Int): T? = getFragmentById(getItemId(index))

	@Suppress("UNCHECKED_CAST")
	private fun <T : Fragment> getFragmentById(itemId: Long): T? {
		val fragments = getFragments()
		if (fragments != null) {
			return fragments[itemId] as T?
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

			// Check if this is a plugin fragment
			val pluginFactory = pluginFragmentFactories[tab.itemId]
			if (pluginFactory != null) {
				return pluginFactory.invoke()
			}

			// Regular fragment creation
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
		val tab = findTabAndIndexByClass(clazz)?.first ?: return null
		return getFragmentById(tab.itemId)
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
		val tooltipTag: String? = null,
	) {
		constructor(
			title: String,
			fragmentClass: Class<out Fragment>,
			itemId: Int,
			tooltipTag: String? = null,
		) : this(title, fragmentClass, itemId.toLong(), tooltipTag)
	}

	fun getTooltipTag(position: Int): String? = allTabs[position].tooltipTag

	private fun addPluginTabs() {
		try {
			val pluginManager = getPluginManager()
			if (pluginManager == null) {
				logger.debug("PluginManager not initialized, skipping plugin tab registration")
				return
			}

			val loadedPlugins = pluginManager.getAllPluginInstances()
			logger.debug("Found {} loaded plugins for tab registration", loadedPlugins.size)

			val pluginTabs = mutableListOf<TabItem>()

			for (plugin in loadedPlugins) {
				try {
					if (plugin is UIExtension) {
						logger.debug("Processing UIExtension plugin: {}", plugin.javaClass.simpleName)

						val tabItems = plugin.getEditorTabs()
						logger.debug(
							"Plugin {} contributed {} tab items",
							plugin.javaClass.simpleName,
							tabItems.size
						)

						for (tabItem in tabItems) {
							if (tabItem.isEnabled && tabItem.isVisible) {
								pluginTabs.add(tabItem)
								logger.debug("Added plugin tab: {} - {}", tabItem.id, tabItem.title)
							}
						}
					}
				} catch (e: Exception) {
					logger.error(
						"Error registering plugin tabs for {}: {}",
						plugin.javaClass.simpleName,
						e.message,
						e
					)
				}
			}

			// Sort tabs by order
			pluginTabs.sortBy { it.order }

			// Add plugin tabs to the adapter at the end
			val startIndex = allTabs.size
			for ((index, tabItem) in pluginTabs.withIndex()) {
				val tab = Tab(
					title = tabItem.title,
					fragmentClass = Fragment::class.java, // Placeholder, actual fragment from factory
					itemId = startIndex + index + 1000L, // Offset to avoid conflicts
					tooltipTag = null
				)

				// Store the fragment factory for later use
				pluginFragmentFactories[tab.itemId] = tabItem.fragmentFactory

				allTabs.add(tab)
				tabs.add(tab)

				logger.debug("Registered plugin tab at index {}: {}", startIndex + index, tabItem.title)
			}

		} catch (e: Exception) {
			logger.error("Error in plugin tab integration: {}", e.message, e)
		}
	}

}
