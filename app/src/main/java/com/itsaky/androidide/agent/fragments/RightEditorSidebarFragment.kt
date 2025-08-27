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

package com.itsaky.androidide.agent.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.databinding.FragmentRightEditorSidebarBinding
import com.itsaky.androidide.fragments.FragmentWithBinding
import com.itsaky.androidide.utils.FeatureFlags.isExperimentsEnabled
import com.itsaky.androidide.utils.RightEditorSidebarActions
import com.itsaky.androidide.utils.TooltipUtils
import kotlinx.coroutines.launch

/**
 * Fragment for showing the default items in the editor activity's sidebar.
 *
 * @author Akash Yadav
 */
class RightEditorSidebarFragment : FragmentWithBinding<FragmentRightEditorSidebarBinding>(
  FragmentRightEditorSidebarBinding::inflate
) {
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    if (!isExperimentsEnabled()) {
      return
    }
    RightEditorSidebarActions.setup(this)
  }

  fun setupTooltip(view: View, tooltipCategory: String, tooltipTag: String) {
    (requireActivity() as? EditorHandlerActivity)?.let { activity ->
      view.setOnLongClickListener { view ->
        activity.lifecycleScope.launch {
          try {
            val tooltipData = activity.getTooltipData(tooltipCategory, tooltipTag)
            tooltipData?.let {
              TooltipUtils.showIDETooltip(
                context = view.context,
                anchorView = view,
                level = 0,
                tooltipItem = it
              )
            }
          } catch (e: Exception) {
            Log.e("Tooltip", "Error loading tooltip for $tooltipTag", e)
          }
        }
        true
      }
    }
  }

  /**
   * Get the (nullable) binding object for this fragment.
   */
  internal fun getBinding() = _binding
}