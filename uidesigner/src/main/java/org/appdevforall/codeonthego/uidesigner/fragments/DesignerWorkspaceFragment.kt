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

package org.appdevforall.codeonthego.uidesigner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration.get
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import org.appdevforall.codeonthego.fragments.BaseFragment
import org.appdevforall.codeonthego.inflater.IView
import org.appdevforall.codeonthego.inflater.internal.LayoutFile
import org.appdevforall.codeonthego.inflater.utils.endParse
import org.appdevforall.codeonthego.inflater.utils.startParse
import com.itsaky.androidide.uidesigner.R
import org.appdevforall.codeonthego.uidesigner.UIDesignerActivity
import com.itsaky.androidide.uidesigner.databinding.FragmentDesignerWorkspaceBinding
import org.appdevforall.codeonthego.uidesigner.drag.WidgetDragListener
import org.appdevforall.codeonthego.uidesigner.drag.WidgetTouchListener
import org.appdevforall.codeonthego.uidesigner.drawable.UiViewLayeredForeground
import org.appdevforall.codeonthego.uidesigner.models.CommonUiView
import org.appdevforall.codeonthego.uidesigner.models.PlaceholderView
import org.appdevforall.codeonthego.uidesigner.models.RootWorkspaceView
import org.appdevforall.codeonthego.uidesigner.models.UiViewGroup
import org.appdevforall.codeonthego.uidesigner.undo.UndoManager
import org.appdevforall.codeonthego.uidesigner.utils.UiLayoutInflater
import org.appdevforall.codeonthego.uidesigner.utils.bgDesignerView
import org.appdevforall.codeonthego.uidesigner.utils.layeredForeground
import org.appdevforall.codeonthego.uidesigner.viewmodel.WorkspaceViewModel
import org.appdevforall.codeonthego.utils.dpToPx
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The fragement that previews the inflated layout.
 *
 * @author Akash Yadav
 */
class DesignerWorkspaceFragment : BaseFragment() {
	private var binding: FragmentDesignerWorkspaceBinding? = null
	internal val viewModel by viewModels<WorkspaceViewModel>(ownerProducer = { requireActivity() })

	private val touchSlop by lazy { get(requireContext()).scaledTouchSlop }

	internal var isInflating = false
	internal val workspaceView by lazy {
		RootWorkspaceView(
			LayoutFile(
				File(""),
				"",
			),
			LinearLayout::class.qualifiedName!!,
			binding!!.workspace,
		)
	}

	val undoManager: UndoManager
		get() = viewModel.undoManager

	internal val placeholder by lazy {
		val view =
			View(requireContext()).apply {
				setBackgroundResource(R.drawable.bg_widget_drag_placeholder)
				layoutParams =
					ViewGroup.LayoutParams(
						requireContext().dpToPx(PLACEHOLDER_WIDTH_DP),
						requireContext().dpToPx(PLACEHOLDER_HEIGHT_DP),
					)
			}
		PlaceholderView(view)
	}

	private val hierarchyHandler by lazy { WorkspaceViewHierarchyHandler() }
	private val attrHandler by lazy { WorkspaceViewAttrHandler() }

	companion object {
		private val log = LoggerFactory.getLogger(DesignerWorkspaceFragment::class.java)

		const val DRAGGING_WIDGET = "DRAGGING_WIDGET"
		const val DRAGGING_WIDGET_MIME = "androidide/uidesigner_widget"
		const val HIERARCHY_CHANGE_TRANSITION_DURATION = 100L

		private const val PLACEHOLDER_WIDTH_DP = 40f
		private const val PLACEHOLDER_HEIGHT_DP = 20f
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		this.binding = FragmentDesignerWorkspaceBinding.inflate(inflater, container, false)
		hierarchyHandler.init(this)
		attrHandler.init(this)
		return this.binding!!.root
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)

		viewModel._workspaceScreen.observe(viewLifecycleOwner) { binding?.flipper?.displayedChild = it }
		viewModel._errText.observe(viewLifecycleOwner) { binding?.errText?.text = it }

		val inflationHandler = WorkspaceLayoutInflationHandler()
		inflationHandler.init(this)

		val inflater = UiLayoutInflater()
		inflater.inflationEventListener = inflationHandler

		val inflated =
			try {
				startParse(viewModel.file)
				inflater.inflate(viewModel.file, workspaceView).also {
					viewModel.layoutHasError = false
				}
			} catch (e: Throwable) {
				log.error("Failed to inflate layout", e)
				viewModel.errText = "${e.message}${e.cause?.message?.let { "\n$it" } ?: ""}"
				viewModel.layoutHasError = true
				emptyList()
			} finally {
				inflationHandler.release()
				inflater.close()
			}

		if (inflated.isEmpty() && !viewModel.layoutHasError) {
			viewModel.errText = getString(R.string.msg_empty_ui_layout)
		}

		binding!!
			.workspace
			.setOnDragListener(WidgetDragListener(workspaceView, this.placeholder, touchSlop))
	}

	override fun onDestroyView() {
		super.onDestroyView()
		this.binding = null
		this.hierarchyHandler.release()
		this.attrHandler.release()

		endParse()
	}

	internal fun setupView(view: IView) {
		if (view is CommonUiView && !view.needSetup) {
			return
		}

		view.registerAttributeChangeListener(attrHandler)
		view.view.setOnTouchListener(
			WidgetTouchListener(view, requireContext()) {
				showViewInfo(it)
				true
			},
		)

		when (val fg = view.view.foreground) {
			null -> {
				view.view.foreground = bgDesignerView(requireContext())
			}

			is UiViewLayeredForeground -> {
				log.warn("Attempt to reset UiViewLayeredForeground on view {} with foreground drawable type {}", view.name, fg::class.java)
			}

			else -> {
				view.view.foreground = layeredForeground(requireContext(), fg)
			}
		}

		if (view is UiViewGroup && view.canModifyChildViews()) {
			setupViewGroup(view)
		}

		if (view is CommonUiView) {
			view.needSetup = false
		}
	}

	internal fun showViewInfo(view: IView) {
		viewModel.view = view

		val existing = childFragmentManager.findFragmentByTag(ViewInfoSheet.TAG)
		if (existing == null) {
			val viewInfo = ViewInfoSheet()
			viewInfo.show(childFragmentManager, ViewInfoSheet.TAG)
		}
	}

	private fun setupViewGroup(viewGroup: UiViewGroup) {
		viewGroup.view.setOnDragListener(WidgetDragListener(viewGroup, placeholder, touchSlop))
		viewGroup.addOnHierarchyChangeListener(hierarchyHandler)
	}

	fun updateHierarchy() {
		if (workspaceView.childCount > 0) {
			(requireActivity() as UIDesignerActivity).setupHierarchy(workspaceView[0])
		}
	}
}
