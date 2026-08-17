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
package com.itsaky.androidide.activities

import android.os.Bundle
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityPreferencesBinding
import com.itsaky.androidide.fragments.IDEPreferencesFragment
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.PluginSettingsEntryPreference
import com.itsaky.androidide.preferences.addRootPreferences
import com.itsaky.androidide.preferences.pluginSettingsPreferences
import com.itsaky.androidide.utils.onLongPress
import com.itsaky.androidide.utils.showIdeCategoryTooltipIfPresent
import com.itsaky.androidide.preferences.IDEPreferences as prefs

class PreferencesActivity : EdgeToEdgeIDEActivity() {
	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityPreferencesBinding? = null
	private val binding: ActivityPreferencesBinding
		get() = checkNotNull(_binding) { "Activity has been destroyed" }
	private var feedbackButtonManager: FeedbackButtonManager? = null

	/**
	 * The plugin-contributed rows present in the tree currently on screen. Plugins load
	 * asynchronously at startup, so a tree built in [onCreate] can miss rows that exist moments
	 * later; re-reading them on resume also picks up an install, uninstall, enable or disable done
	 * in the Plugin Manager, with no IDE restart.
	 */
	private var contributedPreferences: List<PluginSettingsEntryPreference>? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setSupportActionBar(binding.toolbar)
		supportActionBar!!.setTitle(R.string.ide_preferences)
		supportActionBar!!.setDisplayHomeAsUpEnabled(true)

		binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.toolbar.setOnLongClickListener {
			// _binding directly, not the checkNotNull-backed binding getter: this can run after the
			// activity is destroyed (see the onLongPress guard below, which needs it for certain).
			val toolbar = _binding?.toolbar ?: return@setOnLongClickListener true
			// No manual haptic feedback: the platform already fires it for a listener returning true.
			showIdeCategoryTooltipIfPresent(this, toolbar, currentScreenTooltipTag(), playHapticFeedback = false)
			true
		}

		// Fallback for a long-press that lands below a short screen's content: the RecyclerView is
		// wrap_content-sized to its own rows, so on a short screen (e.g. Git, About) it doesn't fill
		// this NestedScrollView, and the remaining space would otherwise have no tooltip at all.
		// A plain setOnLongClickListener never fires here - NestedScrollView overrides onTouchEvent()
		// for its own drag/fling handling, same as RecyclerView - so this needs the GestureDetector-
		// based View.onLongPress instead.
		binding.fragmentContainerParent.onLongPress {
			// _binding directly: the GestureDetector's ~500ms long-press timer is independent of the
			// view's own lifecycle and can fire after the activity (and its binding) is destroyed.
			val container = _binding?.fragmentContainerParent ?: return@onLongPress
			showIdeCategoryTooltipIfPresent(this, container, currentScreenTooltipTag())
		}

		feedbackButtonManager =
			FeedbackButtonManager(
				activity = this,
				feedbackFab = binding.fabFeedback.root,
			)
		feedbackButtonManager?.setupDraggableFab()

		if (savedInstanceState != null) {
			contributedPreferences =
				BundleCompat.getParcelableArrayList(
					savedInstanceState,
					STATE_CONTRIBUTED_PREFERENCES,
					PluginSettingsEntryPreference::class.java,
				)
			return
		}

		loadRootFragment()
	}

	private fun loadRootFragment() {
		// Snapshot before building the tree: a plugin that loads in between then reads as a change on
		// the next resume instead of being silently missed.
		contributedPreferences = pluginSettingsPreferences()

		prefs.clearPreferences()

		prefs.addRootPreferences()

		val args = Bundle()
		args.putParcelableArrayList(
			IDEPreferencesFragment.EXTRA_CHILDREN,
			ArrayList(prefs.children),
		)
		args.putString(IDEPreferencesFragment.EXTRA_SCREEN_TOOLTIP_TAG, prefs.tooltipTag)

		// A fresh instance every time: arguments cannot be set on a fragment whose state was saved.
		loadFragment(IDEPreferencesFragment().also { it.arguments = args })
	}

	private fun reloadRootFragmentIfContributedRowsChanged() {
		// Only while the root screen is showing - rebuilding it under a sub-screen would pop the user
		// out of the screen they are on.
		if (supportFragmentManager.backStackEntryCount > 0) {
			return
		}

		if (pluginSettingsPreferences() == contributedPreferences) {
			return
		}

		loadRootFragment()
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		val binding = _binding ?: return
		val toolbar: View = binding.toolbar
		toolbar.setPadding(
			toolbar.paddingLeft + insets.left,
			toolbar.paddingTop,
			toolbar.paddingRight + insets.right,
			toolbar.paddingBottom,
		)

		val fragmentContainer: View = binding.fragmentContainerParent
		fragmentContainer.setPadding(
			fragmentContainer.paddingLeft + insets.left,
			fragmentContainer.paddingTop,
			fragmentContainer.paddingRight + insets.right,
			fragmentContainer.paddingBottom,
		)
	}

	override fun bindLayout(): View {
		_binding =
			ActivityPreferencesBinding.inflate(
				layoutInflater,
			)
		return binding.root
	}

	private fun loadFragment(fragment: Fragment) {
		super.loadFragment(fragment, binding.fragmentContainer.id)
	}

	/** The tag of whichever [IDEPreferencesFragment] screen is currently on top, or [TooltipTag.PREFS_TOP]. */
	private fun currentScreenTooltipTag(): String {
		val fragment = supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
		return (fragment as? IDEPreferencesFragment)?.screenTooltipTag ?: TooltipTag.PREFS_TOP
	}

	override fun onDestroy() {
		super.onDestroy()
		_binding = null
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		contributedPreferences?.let {
			outState.putParcelableArrayList(STATE_CONTRIBUTED_PREFERENCES, ArrayList(it))
		}
	}

	override fun onResume() {
		super.onResume()
		feedbackButtonManager?.loadFabPosition()
		reloadRootFragmentIfContributedRowsChanged()
	}

	companion object {
		private const val STATE_CONTRIBUTED_PREFERENCES = "contributedPreferences"
	}
}
