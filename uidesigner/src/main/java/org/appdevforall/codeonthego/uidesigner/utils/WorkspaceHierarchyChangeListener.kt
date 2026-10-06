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

package org.appdevforall.codeonthego.uidesigner.utils

import org.appdevforall.codeonthego.uidesigner.models.UiViewGroup
import org.appdevforall.codeonthego.uidesigner.viewmodel.WorkspaceViewModel

/**
 * Handles hierarchy changes in the UI Designer's workspace.
 *
 * @author Akash Yadav
 */
internal class WorkspaceHierarchyChangeListener(
  private val viewModel: WorkspaceViewModel,
  private val workspaceView: UiViewGroup
) : org.appdevforall.codeonthego.inflater.IViewGroup.SingleOnHierarchyChangeListener() {


}
