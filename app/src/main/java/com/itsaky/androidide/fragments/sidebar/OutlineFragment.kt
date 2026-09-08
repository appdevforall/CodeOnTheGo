package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.ui.models.OutlineUiEffect
import com.itsaky.androidide.ui.outline.OutlinePanel
import com.itsaky.androidide.viewmodel.EditorViewModel
import com.itsaky.androidide.viewmodel.OutlineViewModel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.nio.file.Path

class OutlineFragment : Fragment() {
	private val viewModel: OutlineViewModel by activityViewModel()
	private val editorViewModel: EditorViewModel by activityViewModels()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				IdeTheme {
					OutlinePanel(viewModel)
				}
			}
		}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		editorViewModel.currentFile.observe(viewLifecycleOwner) { view.post { seedFromCurrentEditor() } }
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.effects.collect { effect ->
					when (effect) {
						is OutlineUiEffect.NavigateTo -> navigateTo(effect.position)
					}
				}
			}
		}
	}

	override fun onStart() {
		super.onStart()
		if (!EventBus.getDefault().isRegistered(this)) {
			EventBus.getDefault().register(this)
		}
	}

	override fun onStop() {
		EventBus.getDefault().unregister(this)
		super.onStop()
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentChanged(event: DocumentChangeEvent) {
		val editor = currentEditor() ?: return
		val file = editor.file ?: return
		if (normalized(file.toPath()) != normalized(event.changedFile)) return
		viewModel.onSnapshot(
			path = normalized(event.changedFile),
			extension = file.extension,
			text = event.newText ?: editor.text.toString(),
			immediate = false,
		)
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentOpened(event: DocumentOpenEvent) {
		val file = currentEditor()?.file ?: return
		if (normalized(file.toPath()) != normalized(event.openedFile)) return
		viewModel.onSnapshot(
			path = normalized(event.openedFile),
			extension = extensionOf(event.openedFile),
			text = event.text,
			immediate = true,
		)
	}

	private fun seedFromCurrentEditor() {
		val editor = currentEditor()
		val file = editor?.file
		if (editor == null || file == null) {
			viewModel.onNoEditor()
			return
		}
		viewModel.onSnapshot(
			path = normalized(file.toPath()),
			extension = file.extension,
			text = editor.text.toString(),
			immediate = true,
		)
	}

	private fun currentEditor() = (activity as? EditorHandlerActivity)?.getCurrentEditor()?.editor

	private fun normalized(path: Path): String = path.toAbsolutePath().normalize().toString()

	private fun navigateTo(position: Position) {
		val editorActivity = activity as? EditorHandlerActivity ?: return
		val drawer = editorActivity.binding.editorDrawerLayout
		val editor = editorActivity.getCurrentEditor()?.editor
		if (editor == null || !editor.isValidPosition(position, true)) {
			drawer.closeDrawer(GravityCompat.START)
			return
		}
		editor.setSelection(position)
		if (!drawer.isDrawerOpen(GravityCompat.START)) {
			centerPositionInView(editor, position)
			return
		}
		drawer.addDrawerListener(
			object : DrawerLayout.SimpleDrawerListener() {
				override fun onDrawerClosed(drawerView: View) {
					drawer.removeDrawerListener(this)
					centerPositionInView(editor, position)
				}
			},
		)
		drawer.closeDrawer(GravityCompat.START)
	}

	private fun centerPositionInView(
		editor: com.itsaky.androidide.editor.ui.IDEEditor,
		position: Position,
	) {
		val rowY = editor.layout.getCharLayoutOffset(position.line, position.column)[0]
		val targetY =
			(rowY - editor.height / 2f)
				.toInt()
				.coerceIn(0, editor.scrollMaxY)
		editor.scroller.startScroll(editor.offsetX, editor.offsetY, 0, targetY - editor.offsetY, 0)
		editor.postInvalidate()
	}

	private fun extensionOf(path: Path): String = path.fileName.toString().substringAfterLast('.', "")
}
