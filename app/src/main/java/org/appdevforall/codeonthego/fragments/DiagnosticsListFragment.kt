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
package org.appdevforall.codeonthego.fragments

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import org.appdevforall.codeonthego.adapters.DiagnosticsAdapter
import org.appdevforall.codeonthego.idetooltips.TooltipTag
import org.appdevforall.codeonthego.lsp.IDELanguageClientImpl

class DiagnosticsListFragment : RecyclerViewFragment<DiagnosticsAdapter>() {
	override val fragmentTooltipTag: String? = TooltipTag.PROJECT_DIAGNOSTICS

	override fun onCreateAdapter(): RecyclerView.Adapter<*> = DiagnosticsAdapter(ArrayList(), null)

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		emptyStateViewModel.setEmptyMessage(getString(R.string.msg_emptyview_diagnostics))
		loadExistingDiagnostics()
	}

	override fun onResume() {
		super.onResume()
		loadExistingDiagnostics()
	}

	private fun loadExistingDiagnostics() {
		if (!IDELanguageClientImpl.isInitialized()) {
			return
		}

		val client = IDELanguageClientImpl.getInstance()
		val activity = activity ?: return

		if (activity is org.appdevforall.codeonthego.interfaces.DiagnosticClickListener) {
			setAdapter(client.newDiagnosticsAdapter())
		}
	}
}
