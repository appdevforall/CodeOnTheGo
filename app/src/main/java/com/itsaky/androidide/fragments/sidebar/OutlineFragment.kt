package com.itsaky.androidide.fragments.sidebar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentSelectedEvent
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.ui.models.OutlineUiEffect
import com.itsaky.androidide.ui.outline.OutlinePanel
import com.itsaky.androidide.viewmodel.OutlineViewModel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.nio.file.Path

class OutlineFragment : Fragment() {
	private val viewModel: OutlineViewModel by activityViewModel()

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
		seedFromCurrentEditor()
	}

	override fun onStop() {
		EventBus.getDefault().unregister(this)
		super.onStop()
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentChanged(event: DocumentChangeEvent) {
		viewModel.onSnapshot(
			fileName = event.changedFile.fileName.toString(),
			extension = extensionOf(event.changedFile),
			text = event.newText ?: FileManager.getDocumentContents(event.changedFile),
			immediate = false,
		)
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentOpened(event: DocumentOpenEvent) {
		viewModel.onSnapshot(
			fileName = event.openedFile.fileName.toString(),
			extension = extensionOf(event.openedFile),
			text = event.text,
			immediate = true,
		)
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentSelected(event: DocumentSelectedEvent) {
		seedFromCurrentEditor()
	}

	@Subscribe(threadMode = MAIN)
	fun onDocumentClosed(event: DocumentCloseEvent) {
		seedFromCurrentEditor()
	}

	private fun seedFromCurrentEditor() {
		val editor = (activity as? EditorHandlerActivity)?.getCurrentEditor()?.editor
		val file = editor?.file
		if (editor == null || file == null) {
			viewModel.onNoEditor()
			return
		}
		viewModel.onSnapshot(
			fileName = file.name,
			extension = file.extension,
			text = editor.text.toString(),
			immediate = true,
		)
	}

	private fun navigateTo(position: Position) {
		val editorActivity = activity as? EditorHandlerActivity ?: return
		editorActivity.binding.editorDrawerLayout.closeDrawer(GravityCompat.START)
		val editor = editorActivity.getCurrentEditor()?.editor ?: return
		if (editor.isValidPosition(position, true)) {
			editor.setSelection(position)
		}
	}

	private fun extensionOf(path: Path): String = path.fileName.toString().substringAfterLast('.', "")
}
