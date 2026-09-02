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

package com.itsaky.androidide.activities.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.collection.MutableIntObjectMap
import androidx.core.content.IntentCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.itsaky.androidide.R
import com.itsaky.androidide.R.string
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.build.QuickRunAction
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.activities.PluginManagerActivity
import com.itsaky.androidide.activities.projectsRoot
import com.itsaky.androidide.analytics.DeepLinkMetric
import com.itsaky.androidide.analytics.DeepLinkOutcome
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.analytics.depth
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.EditorEvents
import com.itsaky.androidide.app.EditorProviderImpl
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.FileActionPopupWindowBinding
import com.itsaky.androidide.databinding.FileActionPopupWindowItemBinding
import com.itsaky.androidide.deeplink.PendingDeepLinkOpen
import com.itsaky.androidide.di.APPLICATION_SCOPE
import com.itsaky.androidide.editor.language.treesitter.JavaLanguage
import com.itsaky.androidide.editor.language.treesitter.JsonLanguage
import com.itsaky.androidide.editor.language.treesitter.KotlinLanguage
import com.itsaky.androidide.editor.language.treesitter.LogLanguage
import com.itsaky.androidide.editor.language.treesitter.TSLanguageRegistry
import com.itsaky.androidide.editor.language.treesitter.XMLLanguage
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.eventbus.events.plugin.PluginCrashedEvent
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.fragments.sidebar.EditorSidebarFragment
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.models.DeepLinkOpenRequest
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.EditorIntentExtras
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.OpenedFilesCache
import com.itsaky.androidide.models.PendingFileRequest
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.plugins.manager.build.PluginBuildActionManager
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.plugins.manager.ui.PluginDrawableResolver
import com.itsaky.androidide.plugins.manager.ui.PluginEditorTabManager
import com.itsaky.androidide.plugins.manager.ui.PluginToolbarHost
import com.itsaky.androidide.plugins.manager.ui.PluginUiActionManager
import com.itsaky.androidide.preferences.internal.EditorPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildResult
import com.itsaky.androidide.repositories.RecentProjectRepository
import com.itsaky.androidide.shortcuts.IdeShortcutActions
import com.itsaky.androidide.shortcuts.ShortcutContext
import com.itsaky.androidide.shortcuts.ShortcutExecutionContext
import com.itsaky.androidide.shortcuts.ShortcutManager
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import com.itsaky.androidide.ui.ARCHIVE_EXTENSIONS
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.utils.DeepLinkProjectLookup
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.DialogUtils.showConfirmationDialog
import com.itsaky.androidide.utils.EditorActivityActions
import com.itsaky.androidide.utils.EditorSidebarActions
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.ImageUtils
import com.itsaky.androidide.utils.IntentUtils.openImage
import com.itsaky.androidide.utils.UniqueNameBuilder
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.forEachViewRecursively
import com.itsaky.androidide.utils.hasVisibleDialog
import com.itsaky.androidide.utils.isDeepLinkTargetOfOpenProject
import com.itsaky.androidide.utils.recordProjectOpenedBookkeeping
import com.itsaky.androidide.utils.resolveDeepLinkProject
import com.itsaky.androidide.utils.resolveWithinDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Base class for EditorActivity. Handles logic for working with file editors.
 *
 * @author Akash Yadav
 */
open class EditorHandlerActivity :
	ProjectHandlerActivity(),
	IEditorHandler,
	PluginToolbarHost {
	private val singleBuildListeners = CopyOnWriteArrayList<Consumer<BuildResult>>()

	companion object {
		const val PREF_KEY_OPEN_FILES_CACHE = "open_files_cache_v1"
		const val PREF_KEY_OPEN_PLUGIN_TABS = "open_plugin_tabs_v1"
	}

	protected val isOpenedFilesSaved = AtomicBoolean(false)

	private val fileTimestamps = ConcurrentHashMap<String, Long>()

	private val pluginTabIndices = mutableMapOf<String, Int>()
	private val tabIndexToPluginId = mutableMapOf<Int, String>()
	private var lastAppliedPluginFontScale = EditorPreferences.editorFontScale
	private val pluginTextBaseSizes = WeakHashMap<TextView, Float>()

	private val pluginFontScalingListener =
		object : FragmentManager.FragmentLifecycleCallbacks() {
			override fun onFragmentViewCreated(
				mFragmentManager: FragmentManager,
				mFragment: Fragment,
				view: View,
				savedInstanceState: Bundle?,
			) {
				val scale = EditorPreferences.editorFontScale
				if (scale != 1f && isPluginFragment(mFragment)) {
					applyPluginFontScale(view, scale)
				}
			}
		}
	private val shortcutManager by lazy { ShortcutManager(applicationContext) }

	private val analyticsManager: IAnalyticsManager by inject()
	private val recentProjectRepository: RecentProjectRepository by inject()
	private val pendingDeepLinkOpen: PendingDeepLinkOpen by inject()

	// The process-wide scope from AppModule, used only by saveAllAsync -- see there for why the
	// activity's own scope is not enough.
	private val appScope: CoroutineScope by inject(named(APPLICATION_SCOPE))

	private var pluginEditorProvider: EditorProviderImpl? = null

	// True once onCreate() has completed past its isFinishing check -- see there and preDestroy()
	// for why a doomed, finishing-from-birth instance must not run teardown meant only for an
	// instance that actually became the live one.
	private var didCompleteLiveOnCreate = false

	private fun getTabPositionForFileIndex(fileIndex: Int): Int {
		val safeContent = contentOrNull ?: return -1
		val totalTabs = safeContent.tabs.tabCount

		if (fileIndex < 0) return -1
		var tabPos = 0
		var fileCount = 0
		while (tabPos < totalTabs) {
			if (!isPluginTab(tabPos)) {
				if (fileCount == fileIndex) return tabPos
				fileCount++
			}
			tabPos++
		}
		return -1
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean =
		shortcutManager.dispatch(
			event = event,
			context = ShortcutContext.EDITOR,
			focusView = currentFocus,
			hasModal = supportFragmentManager.hasVisibleDialog(),
			executionContext = editorShortcutExecutionContext(),
		) || super.dispatchKeyEvent(event)

	private fun editorShortcutExecutionContext(): ShortcutExecutionContext =
		ShortcutExecutionContext(
			ideShortcutActions =
				IdeShortcutActions {
					createToolbarActionData()
				},
		)

	override fun doOpenFile(
		file: File,
		selection: Range?,
	) {
		openFileAndSelect(file, selection)
	}

	override fun doCloseAll() {
		closeAll {}
	}

	override fun provideCurrentEditor(): CodeEditorView? = getCurrentEditor()

	override fun provideEditorAt(index: Int): CodeEditorView? = getEditorAtIndex(index)

	override fun preDestroy() {
		super.preDestroy()
		// TSLanguageRegistry.instance is a process-wide singleton whose own KDoc says destroy() "must
		// be called only when the application is exiting" -- guarded on didCompleteLiveOnCreate (same
		// reasoning as pluginEditorProvider below) so a doomed instance, spun up and finishing before
		// its onCreate() ever got this far, can't tear down the registry a different, actually-live
		// sibling instance still depends on for syntax highlighting.
		if (didCompleteLiveOnCreate) {
			TSLanguageRegistry.instance.destroy()
		}
		editorViewModel.removeAllFiles()

		// Guarded on pluginEditorProvider (rather than unconditional) so an instance whose onCreate()
		// returned early because it was already finishing (see onCreate()) -- and which therefore
		// never registered a provider of its own -- can't null out a DIFFERENT, actually-live
		// instance's provider out from under it during its own teardown.
		if (pluginEditorProvider != null) {
			IDEApplication.getPluginManager()?.setEditorProvider(null)
		}
		pluginEditorProvider?.dispose()
		pluginEditorProvider = null
	}

	private val floatingTabController by lazy {
		com.itsaky.androidide.editor.floating
			.IdeFloatingTabController(this)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		setupPluginFragmentFactory()
		mBuildEventListener.setActivity(this)
		super.onCreate(savedInstanceState)

		// BaseEditorActivity.onCreate() (just run via super.onCreate() above) may have already called
		// finish() -- e.g. this instance was spun up for a deep link whose project doesn't match what
		// it holds, or with no project path at all -- and returned; finish() doesn't stop execution
		// from continuing here. Without this check, the registrations below would unconditionally
		// clobber process-wide singleton state (ActionContextProvider, the plugin editor provider)
		// away from whatever OTHER, actually-live instance currently owns it, with nothing to ever
		// restore it once this doomed instance is eventually torn down.
		if (isFinishing) {
			return
		}
		didCompleteLiveOnCreate = true

		// Registered here (right after super.onCreate() finishes wiring the toolbar/action registry),
		// not just onResume (see there too), so this instance is discoverable via
		// ActionContextProvider.getLiveActivity() for almost its whole lifetime -- see that
		// function's docs for the redundant-open race a gap between onCreate and onResume otherwise
		// leaves open.
		// Registering before super.onCreate() returns would instead expose a partially-constructed
		// activity (no toolbar/action registry yet) to external callers like a floating
		// EditorPanelDockableContent window, which is explicitly documented to outlive this activity
		// and can act on it at any time.
		ActionContextProvider.setActivity(this)

		supportFragmentManager.registerFragmentLifecycleCallbacks(pluginFontScalingListener, true)
		floatingTabController.start()

		editorViewModel._displayedFile.observe(
			this,
		) { fileIndex ->
			val tabPosition = getTabPositionForFileIndex(fileIndex)
			if (tabPosition >= 0) {
				this.content.editorContainer.displayedChild = tabPosition
			}
			EditorEvents.notifyFileChanged(editorViewModel.getCurrentFile())
		}

		pluginEditorProvider =
			EditorProviderImpl(this).also { provider ->
				IDEApplication.getPluginManager()?.setEditorProvider(provider)
			}
		editorViewModel._startDrawerOpened.observe(this) { opened ->
			this.binding.editorDrawerLayout.apply {
				if (opened) openDrawer(GravityCompat.START) else closeDrawer(GravityCompat.START)
			}
		}

		editorViewModel._filesModified.observe(this) { invalidateOptionsMenu() }
		editorViewModel._filesSaving.observe(this) { invalidateOptionsMenu() }

		editorViewModel.observeFiles(this) {
			// rewrite the cached files index if there are any opened files
			val currentFile =
				getCurrentEditor()?.editor?.file?.absolutePath
					?: run {
						editorViewModel.writeOpenedFiles(null)
						editorViewModel.openedFilesCache = null
						return@observeFiles
					}
			getOpenedFiles().also {
				val cache =
					OpenedFilesCache(
						projectPath = ProjectManagerImpl.getInstance().projectDirPath,
						selectedFile = currentFile,
						allFiles = it,
					)
				editorViewModel.writeOpenedFiles(cache)
				editorViewModel.openedFilesCache = cache
			}
		}

		executeAsync {
			TSLanguageRegistry.instance.registerIfNeeded(JavaLanguage.TS_TYPE, JavaLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(KotlinLanguage.TS_TYPE_KT, KotlinLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(KotlinLanguage.TS_TYPE_KTS, KotlinLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(LogLanguage.TS_TYPE, LogLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(JsonLanguage.TS_TYPE, JsonLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(XMLLanguage.TS_TYPE, XMLLanguage.FACTORY)
			IDEColorSchemeProvider.initIfNeeded()
		}

		optionsMenuInvalidator =
			Runnable {
				prepareOptionsMenu()
			}

		loadPluginTabs()
	}

	/**
	 * Persists which tabs are open (preferences only). Does **not** write project file buffers to disk;
	 * saving is explicit or prompted (e.g. close project).
	 */
	override fun onPause() {
		super.onPause()
		// Record timestamps for all currently open files before saving the cache
		val openFiles = editorViewModel.getOpenedFiles()
		lifecycleScope.launch(Dispatchers.IO) {
			openFiles.forEach { file ->
				// Note: Using the file's absolutePath as the key
				fileTimestamps[file.absolutePath] = file.lastModified()
			}
		}
		if (!isOpenedFilesSaved.get()) {
			saveOpenedFiles()
			saveOpenedPluginTabs()
		}
	}

	private fun saveOpenedPluginTabs() {
		val prefs = (application as BaseApplication).prefManager
		val openPluginTabIds = pluginTabIndices.keys.toList()
		if (openPluginTabIds.isEmpty()) {
			prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, null)
			return
		}
		val json = Gson().toJson(openPluginTabIds)
		prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, json)
		Log.d("EditorHandlerActivity", "Saved open plugin tabs: $openPluginTabIds")
	}

	// Actually performs a pending "close then reopen a different project" hand-off recorded via
	// pendingDeepLinkOpen. Shared by onDestroy() (the normal case -- see its docs for why the
	// hand-off waits until here) and confirmProjectClose's "Save and close" completion (the race
	// case -- see there for why that path can't always rely on onDestroy() running afterward).
	private fun performPendingDeepLinkOpen(pending: DeepLinkOpenRequest) {
		val root = File(pending.projectRoot)
		val ctx = applicationContext
		// The value carried on the request, NOT the live global. Reading the global here was wrong on
		// the plain-switch path: MainActivity.openProject had already overwritten it with the new path
		// before the intent arrived, so previous == new and the receiver saw a same-project no-op.
		// Falling back to the global still helps the deep-link path, where nothing pre-mutates it.
		val previousProjectPath = pending.previousProjectPath ?: IProjectManager.getInstance().projectDirPath
		if (!pending.bookkeepingAlreadyRecorded) {
			recordProjectOpenedBookkeeping(recentProjectRepository, root, project = null, analyticsManager = analyticsManager)
		}

		// Starting an activity from onDestroy() is a background activity start -- and therefore dropped
		// silently by Android 10+ -- only once the finishing activity was the last one in its task.
		// That cannot happen here: this activity is never its task's root. It is reachable only
		// through MainActivity (openProject) or DeepLinkActivity, which routes through MainActivity
		// when no editor is live, and it is not exported, so nothing else can launch it. Verified on
		// an Android 13 device: every androidide task has MainActivity at Hist #0 with this activity
		// above it, so finishing leaves the task non-empty and the app in the foreground. A
		// Beepy -> Aegis1 deep-link switch completed with no dropped start.
		ctx.startActivity(
			Intent(ctx, EditorActivityKt::class.java).apply {
				putExtra(EditorIntentExtras.EXTRA_PROJECT_PATH, pending.projectRoot)
				// The same extra MainActivity.openProject sends, and for the same reason: the receiver
				// falls back to IProjectManager.projectDirPath, which recordProjectOpenedBookkeeping has
				// already overwritten to this very path. Omitting it made a delivery that lands on
				// onNewIntent (rather than a fresh onCreate) compute previousProjectPath == newProjectPath,
				// so switchToProject took its same-project branch and the confirmed switch silently
				// no-opped -- with the process-wide global naming the new project while the editor still
				// showed the old one.
				putExtra(EditorIntentExtras.EXTRA_PREVIOUS_PROJECT_PATH, previousProjectPath)
				pending.fileRequest?.let { putExtra(PendingFileRequest.EXTRA_KEY, it) }
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			},
		)
	}

	// Drains the hand-off THIS instance armed (if any) and performs it -- shared by onDestroy() (the
	// normal case) and confirmProjectClose's "Save and close" completion once isDestroyed confirms
	// onDestroy() already ran (the race case) -- so the one-shot "check, clear, perform" sequence has
	// a single copy instead of being kept in sync by hand across both call sites.
	private fun drainPendingDeepLinkOpen() {
		pendingDeepLinkOpen.drainArmedBy(handoffOwner)?.let(::performPendingDeepLinkOpen)
	}

	override fun onDestroy() {
		super.onDestroy()
		ActionContextProvider.clearActivity(this)
		// Not dismissing this would leak the dialog's window (WindowLeaked) past this activity's
		// death -- e.g. a rotation while the confirm-close dialog is showing.
		//
		// dismiss() alone was not enough: it tears the window down WITHOUT dispatching the negative
		// button or the OnCancelListener, so a dialog still on screen at destroy time died with its
		// decline handling never run -- leaving the intent and the process-wide bookkeeping pointing
		// at a switch the user never confirmed (ADFA-5067 review). cancel() would dispatch it, but
		// cancelOrDecline() can recursively show a fresh dialog for a superseding request, which is
		// exactly what must not happen from here; declineInFlightProjectClose() is its rollback half.
		//
		// The Bundle is handled separately: onSaveInstanceState runs BEFORE onDestroy, so by now it
		// has already been written -- see projectPathForInstanceState for the half of this fix that
		// keeps the un-confirmed project out of it in the first place.
		activeProjectCloseDialog?.let { dialog ->
			if (dialog.isShowing && !closeDialogAnswered) {
				declineInFlightProjectClose()
			}
			dialog.dismiss()
		}
		activeProjectCloseDialog = null

		// Both gated on isFinishing: onDestroy() also runs for a non-finishing recreate (a config
		// change EditorActivityKt's own configChanges doesn't cover - dark mode, locale, display size
		// - or "Don't keep activities"), which can land while the confirm-close dialog above is still
		// showing and pendingCloseCallback is already armed for it (set the moment the dialog opens,
		// not once the user actually chooses an option - see confirmProjectClose). Without this guard,
		// a config change the user never asked for silently confirms that pending close/switch and
		// discards the project it was showing. The two legitimate confirm paths (Close without saving,
		// Save and close) both route through closeProject(), whose deferred finish() must land before
		// onDestroy() can run, so isFinishing is true here by the time onDestroy() drains it.
		if (isFinishing) {
			// The callback drain is additionally gated on closeCommitted: isFinishing alone is also
			// true when the task is swiped out of Recents while the dialog is still showing -- an
			// armed-but-uncommitted pendingCloseCallback then means the user bailed, and running it
			// here would startActivity into a project switch they never confirmed (see the field's
			// docs). When the flag IS set, this drain still matters: a "Close without saving" confirm
			// deliberately leaves confirmCloseInProgress stuck true (see there) since this instance
			// is finishing either way -- a later request that arrived in the window before
			// onDestroy() actually ran got parked here with nothing else left to read it. Run and
			// clear it now instead of silently orphaning it.
			//
			// closeCommitted, NOT closeDialogAnswered: the latter went true the instant "Save and
			// close" was tapped, so a finish() landing while the save was still writing made this
			// line commit the switch and skip the save-failure abort entirely, abandoning the user's
			// edits with no message (ADFA-5067 review). Committing is now the save's own decision.
			if (closeCommitted) {
				pendingCloseCallback?.invoke()
			}
			pendingCloseCallback = null
		}

		// Drain any deep-link-triggered "close then reopen a different project" request this instance
		// recorded. Deliberately waits until onDestroy -- which only runs once the framework has
		// committed to tearing this singleTask instance down -- rather than firing startActivity()
		// synchronously right after finish(), because the two calls racing could otherwise have the
		// new PROJECT_PATH redelivered to this dying instance via onNewIntent (which never reads it)
		// instead of a genuinely new instance's onCreate.
		//
		// Outside the isFinishing guard, and keyed on ownership rather than didCompleteLiveOnCreate.
		// isFinishing was the wrong question: closeProject() arms the hand-off and then defers its
		// finish() into a lifecycleScope coroutine that ON_DESTROY cancels, so a destroy that beat
		// the finish() left a confirmed switch sitting in this Koin `single` -- to fire later against
		// an unrelated project close, since nothing else clears it. A config-change recreate landing
		// mid-save reached here the same way. drainArmedBy answers "did I arm this?" directly, which
		// is what didCompleteLiveOnCreate was standing in for: an instance that took
		// BaseEditorActivity.onCreate's deepLinkTargetsAnotherProject bail armed nothing, so it now
		// drains nothing rather than stealing a live instance's hand-off.
		drainPendingDeepLinkOpen()
	}

	override fun onResume() {
		super.onResume()
		// Re-asserted here too (not just onCreate) so this instance reclaims ActionContextProvider's
		// registration whenever it becomes the foreground-active one again -- e.g. if a different,
		// stale-duplicate instance briefly registered over it (see ActionContextProvider.getLiveActivity()'s
		// docs) and was then destroyed, clearing the reference entirely with nothing left to restore it
		// otherwise. A doomed instance whose onCreate() returned early (isFinishing) never reaches
		// onResume() at all, so this can't re-expose a partially-constructed instance the way doing this
		// unconditionally in onCreate() would.
		ActionContextProvider.setActivity(this)
		isOpenedFilesSaved.set(false)
		checkForExternalFileChanges()
		// Invalidate the options menu to reflect any changes
		invalidateOptionsMenu()
	}

	/**
	 * Reloads disk content into an open editor only when the file changed on disk since the last
	 * [onPause] snapshot **and** the in-memory buffer is still clean ([CodeEditorView.isModified] is
	 * false). A clean buffer may still have undo history after [IDEEditor.markUnmodified] / save; we
	 * reload anyway so external edits are not ignored. Never replaces buffers with unsaved edits.
	 *
	 * @param force If true, reloads even if the buffer is modified or the timestamp hasn't changed.
	 */
	fun checkForExternalFileChanges(force: Boolean = false) {
		val openFiles = editorViewModel.getOpenedFiles()
		if (openFiles.isEmpty() || (fileTimestamps.isEmpty() && !force)) return

		lifecycleScope.launch(Dispatchers.IO) {
			openFiles.forEach { file ->
				val lastKnownTimestamp = fileTimestamps[file.absolutePath] ?: 0L
				val currentTimestamp = file.lastModified()

				if (currentTimestamp > lastKnownTimestamp || force) {
					val newContent = runCatching { file.readText() }.getOrNull() ?: return@forEach
					withContext(Dispatchers.Main) {
						val editorView = getEditorForFile(file) ?: return@withContext
						if (editorView.isModified && !force) return@withContext
						val ideEditor = editorView.editor ?: return@withContext

						ideEditor.setText(newContent)
						editorView.markAsSaved()
						fileTimestamps[file.absolutePath] = currentTimestamp
						updateTabs()
						// Without this, areFilesModified() (a cached flag, only ever recomputed as a side
						// effect of a successful per-file write - see saveResultInternal) can stay
						// stale-true after this reload+markAsSaved: nothing else here reflects that the
						// buffer this loop just cleaned is no longer modified.
						editorViewModel.areFilesModified = hasUnsavedFiles()
					}
				}
			}
		}
	}

	override fun saveOpenedFiles() {
		writeOpenedFilesCache(getOpenedFiles(), getCurrentEditor()?.editor?.file)
	}

	private fun writeOpenedFilesCache(
		openedFiles: List<OpenedFile>,
		selectedFile: File?,
	) {
		val prefs = (application as BaseApplication).prefManager

		if (selectedFile == null || openedFiles.isEmpty()) {
			// If there are no files, clear the saved preference
			prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null)
			log.debug("[onPause] No opened files. Session cache cleared.")
			isOpenedFilesSaved.set(true)
			return
		}

		val cache =
			OpenedFilesCache(
				projectPath = ProjectManagerImpl.getInstance().projectDirPath,
				selectedFile = selectedFile.absolutePath,
				allFiles = openedFiles,
			)

		val jsonCache = Gson().toJson(cache)
		prefs.putString(PREF_KEY_OPEN_FILES_CACHE, jsonCache)

		log.debug("[onPause] Editor session saved to SharedPreferences.")
		isOpenedFilesSaved.set(true)
	}

	override fun onStart() {
		super.onStart()

		lifecycleScope.launch {
			try {
				val prefs = (application as BaseApplication).prefManager
				val jsonCache =
					withContext(Dispatchers.IO) {
						prefs.getString(PREF_KEY_OPEN_FILES_CACHE, null)
					} ?: return@launch

				if (editorViewModel.getOpenedFileCount() > 0) {
					// Returning to an in-memory session (e.g. after onPause/onStop). Replaying the
					// snapshot would be redundant and could interfere with dirty buffers and undo.
					withContext(Dispatchers.IO) { prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null) }
					return@launch
				}

				val cache =
					withContext(Dispatchers.Default) {
						Gson().fromJson(jsonCache, OpenedFilesCache::class.java)
					}
				onReadOpenedFilesCache(cache)

				// Clear the preference so it's only loaded once per cold restore
				withContext(Dispatchers.IO) { prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: JsonSyntaxException) {
				log.error("Failed to reopen recently opened files", err)
			}
		}

		restoreOpenedPluginTabs()
		syncPluginUiFontSize()
	}

	/**
	 * Restores the plugin tabs cached from the previous session, running the
	 * SharedPreferences IO and Gson decode off the main thread to avoid a startup UI stall.
	 */
	private fun restoreOpenedPluginTabs() {
		lifecycleScope.launch {
			try {
				val prefs = (application as BaseApplication).prefManager
				val json =
					withContext(Dispatchers.IO) {
						prefs.getString(PREF_KEY_OPEN_PLUGIN_TABS, null)
					} ?: return@launch

				// Decoding the cached JSON off the main thread avoids a UI stall on startup.
				val tabIds =
					withContext(Dispatchers.Default) {
						Gson().fromJson(json, Array<String>::class.java)?.toList()
					} ?: return@launch
				Log.d("EditorHandlerActivity", "Restoring plugin tabs: $tabIds")

				// Tab selection touches UI state, so keep it on the main thread.
				tabIds.forEach { tabId ->
					if (!pluginTabIndices.containsKey(tabId)) {
						selectPluginTabById(tabId)
					}
				}

				withContext(Dispatchers.IO) { prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, null) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: JsonSyntaxException) {
				Log.e("EditorHandlerActivity", "Failed to restore plugin tabs", e)
			}
		}
	}

	private fun onReadOpenedFilesCache(cache: OpenedFilesCache?) {
		cache ?: return

		val currentProjectPath = ProjectManagerImpl.getInstance().projectDirPath
		if (cache.projectPath.isNotEmpty() && cache.projectPath != currentProjectPath) {
			log.debug("[onStart] Discarding stale tab cache from project: {}", cache.projectPath)
			return
		}

		lifecycleScope.launch(Dispatchers.IO) {
			val existingFiles = cache.allFiles.filter { File(it.filePath).exists() }
			val selectedFileExists = File(cache.selectedFile).exists()

			if (existingFiles.isEmpty()) return@launch

			withContext(Dispatchers.Main) {
				if (contentOrNull == null) return@withContext
				existingFiles.forEach { file ->
					openFile(File(file.filePath), file.selection)
				}

				if (selectedFileExists) {
					openFile(File(cache.selectedFile))
				}
			}
		}
	}

	/**
	 * [PluginToolbarHost] entry point. Lets plugin services (via IdeUIService) request a
	 * toolbar rebuild so dynamic [com.itsaky.androidide.plugins.extensions.ToolbarAction]
	 * providers (icon/enabled/visible) are re-evaluated. Marshalled to the UI thread.
	 */
	override fun refreshPluginToolbarActions() {
		runOnUiThread { prepareOptionsMenu() }
	}

	fun prepareOptionsMenu() {
		val registry = getInstance() as DefaultActionsRegistry
		val data = createToolbarActionData()
		content.projectActionsToolbar.clearMenu()

		// Sort by (order, id) so a plugin's ToolbarAction.order positions its icon among the
		// built-in actions. The 13 built-ins are registered with contiguous order 0..12, so
		// this is a visual no-op for them.
		val actions =
			getInstance()
				.getActions(EDITOR_TOOLBAR)
				.values
				.sortedWith(compareBy({ it.order }, { it.id }))
		val hiddenIds =
			PluginBuildActionManager.getInstance().getHiddenActionIds() +
				PluginUiActionManager.getHiddenActionIds()
		actions.forEachIndexed { index, action ->
			val isLast = index == actions.size - 1

			action.prepare(data)

			if (action.id in hiddenIds) return@forEachIndexed

			// Plugin toolbar actions opt into real visibility handling: remove them entirely
			// when not applicable, instead of the legacy grey-out used by built-in actions.
			if (action.honorVisibility && !action.visible) return@forEachIndexed

			action.icon?.apply {
				colorFilter = action.createColorFilter(data)
				alpha = if (action.enabled) 255 else 76
			}

			content.projectActionsToolbar.addMenuItem(
				icon = action.icon,
				hint = getToolbarContentDescription(action, data),
				onClick = { if (action.enabled) registry.executeAction(action, data) },
				onLongClick = {
					TooltipManager.showTooltip(
						context = this,
						anchorView = content.projectActionsToolbar,
						category = action.retrieveTooltipCategory(),
						tag = action.retrieveTooltipTag(false),
					)
				},
				onHover = { anchor ->
					TooltipManager.cancelScheduledDismiss()
					TooltipManager.showTooltip(
						context = this@EditorHandlerActivity,
						anchorView = anchor,
						category = action.retrieveTooltipCategory(),
						tag = action.retrieveTooltipTag(false),
						requestFocus = false,
					)
				},
				onHoverExit = {
					TooltipManager.scheduleActiveTooltipDismiss()
				},
				shouldAddMargin = !isLast,
			)
		}
	}

	private fun createToolbarActionData(): ActionData {
		val data = ActionData.create(this)
		val currentEditor = getCurrentEditor()

		data.put(CodeEditorView::class.java, currentEditor)

		if (currentEditor != null) {
			data.put(IDEEditor::class.java, currentEditor.editor)
			data.put(File::class.java, currentEditor.file)
		}
		return data
	}

	private fun getToolbarContentDescription(
		action: ActionItem,
		data: ActionData,
	): String {
		val buildInProgress =
			with(com.itsaky.androidide.actions.build.AbstractCancellableRunAction) {
				this@EditorHandlerActivity.isBuildInProgress()
			}
		if (action.id == QuickRunAction.ID && buildInProgress) {
			return getString(string.cd_toolbar_cancel_build)
		}
		val resId =
			when (action.id) {
				QuickRunAction.ID -> {
					string.cd_toolbar_quick_run
				}

				"ide.editor.syncProject" -> {
					string.cd_toolbar_sync_project
				}

				"ide.editor.build.debug" -> {
					string.cd_toolbar_start_debugger
				}

				"ide.editor.build.runTasks" -> {
					string.cd_toolbar_run_gradle_tasks
				}

				"ide.editor.code.text.undo" -> {
					string.cd_toolbar_undo
				}

				"ide.editor.code.text.redo" -> {
					string.cd_toolbar_redo
				}

				"ide.editor.files.saveAll" -> {
					string.cd_toolbar_save
				}

				"ide.editor.previewLayout" -> {
					string.cd_toolbar_preview_layout
				}

				"ide.editor.find" -> {
					string.cd_toolbar_find
				}

				"ide.editor.find.inFile" -> {
					string.cd_toolbar_find_in_file
				}

				"ide.editor.find.inProject" -> {
					string.cd_toolbar_find_in_project
				}

				"ide.editor.launchInstalledApp" -> {
					string.cd_toolbar_launch_app
				}

				"ide.editor.service.logreceiver.disconnectSenders" -> {
					string.cd_toolbar_disconnect_log_senders
				}

				"ide.editor.generatexml" -> {
					string.cd_toolbar_image_to_layout
				}

				else -> {
					null
				}
			}
		return if (resId != null) getString(resId) else action.label
	}

	override fun getCurrentEditor(): CodeEditorView? =
		if (editorViewModel.getCurrentFileIndex() != -1) {
			getEditorAtIndex(editorViewModel.getCurrentFileIndex())
		} else {
			null
		}

	override fun getEditorAtIndex(index: Int): CodeEditorView? {
		val tabPosition = getTabPositionForFileIndex(index)
		if (tabPosition < 0) return null
		val child = _binding?.content?.editorContainer?.getChildAt(tabPosition) ?: return null
		return if (child is CodeEditorView) child else null
	}

	/** Undock the file tab at [fileIndex] into a floating window over other apps. */
	fun undockFileTab(fileIndex: Int) {
		floatingTabController.undock(fileIndex)
	}

	/** Undock the plugin tab [tabId] (at [position]) into a floating window over other apps. */
	fun undockPluginTab(
		tabId: String,
		position: Int,
	) {
		val title =
			PluginEditorTabManager
				.getInstance()
				.getPluginTab(tabId)
				?.title ?: tabId
		floatingTabController.floatPluginTab(tabId, title) { closePluginTab(position) }
	}

	override fun openFileAndSelect(
		file: File,
		selection: Range?,
	) {
		lifecycleScope.launch {
			// A copy here too, not just in the postInLifecycle block below. openFile hands this straight
			// to CodeEditorView's constructor, whose content-load pipeline calls selection.validate()
			// and ideEditor.validateRange(selection) -- both of which clamp start/end in place on the
			// caller's own object. The caller is often not ours to mutate: IDEEditor.showDocument passes
			// the Range out of an LSP ShowDocumentParams via IDELanguageClientImpl.openFileAndSelect, so
			// clamping it corrupts the language server's own Location.
			val editorView = openFile(file, selection?.let { Range(it) })

			editorView?.editor?.also { editor ->
				editor.postInLifecycle {
					if (selection == null) {
						editor.setSelection(0, 0)
						return@postInLifecycle
					}
					// EditorFeatures.validateRange mutates Position in place. For a file that was
					// just opened (new CodeEditorView), that same `selection` instance was also handed
					// to the view's constructor, whose own async content-load pipeline calls
					// validateRange/setSelection on it again once the file finishes reading. If this
					// call runs first -- while the document is still the freshly-constructed empty
					// one line -- it clamps the shared Position down to (0,0) *before* the real
					// content loads, permanently corrupting the value the constructor's own pipeline
					// later relies on. Validate/apply a defensive copy here instead, so this call can
					// never corrupt the shared instance regardless of which side runs first.
					val safeSelection = Range(selection)
					editor.validateRange(safeSelection)
					editor.setSelection(safeSelection)
				}
			}
		}
	}

	override suspend fun openFile(
		file: File,
		selection: Range?,
	): CodeEditorView? =
		withContext(Dispatchers.Main) {
			// Not the shared Range.NONE/Position.NONE singleton -- openFileAndGetIndex below hands this
			// straight to CodeEditorView's constructor, whose async content-load pipeline calls
			// validateRange/setSelection on it (the identical hazard openFileAndSelect's own selection
			// != null path already guards against with a defensive copy). Position has mutable var
			// line/column and overrides equals() structurally, so mutating the actual Range.NONE/
			// Position.NONE instance in place would permanently corrupt every future `== Range.NONE`/
			// `== Position.NONE` "nothing found" sentinel check elsewhere in the app (e.g.
			// GoToDefinition, FindUsages, OrganizeImportsAction) for the rest of the process.
			val range = selection ?: Range(Range.NONE)
			val isImage = withContext(Dispatchers.IO) { ImageUtils.isImage(file) }
			if (isImage) {
				openImage(this@EditorHandlerActivity, file)
				return@withContext null
			}

			val pluginHandled = IDEApplication.getPluginManager()?.delegateFileOpen(file) ?: false
			if (pluginHandled) {
				return@withContext null
			}

			val fileIndex = openFileAndGetIndex(file, range)
			if (fileIndex < 0) return@withContext null

			editorViewModel.startDrawerOpened = false
			editorViewModel.displayedFileIndex = fileIndex

			val tabPosition = getTabPositionForFileIndex(fileIndex)
			val tab = content.tabs.getTabAt(tabPosition)
			if (tab != null && !tab.isSelected) {
				tab.select()
			}

			return@withContext try {
				getEditorAtIndex(fileIndex)
			} catch (th: Throwable) {
				log.error("Unable to get editor at file index {}", fileIndex, th)
				null
			}
		}

	fun openFileAsync(
		file: File,
		selection: Range? = null,
		onResult: (CodeEditorView?) -> Unit,
	) {
		lifecycleScope.launch {
			onResult(openFile(file, selection))
		}
	}

	override fun openFileAndGetIndex(
		file: File,
		selection: Range?,
	): Int {
		val safeContent = contentOrNull ?: return -1
		val totalTabs = safeContent.tabs.tabCount
		val openedFileIndex = findIndexOfEditorByFile(file)
		if (openedFileIndex != -1) {
			return openedFileIndex
		}

		if (!file.exists()) {
			return -1
		}

		val fileIndex = editorViewModel.getOpenedFileCount()
		val tabPosition = getNextFileTabPosition()
		if (tabPosition < 0) return -1

		log.info("Opening file at file index {} tab position {} file:{}", fileIndex, tabPosition, file)

		// A copy, and a real Range rather than `!!`. CodeEditorView's async content-load pipeline calls
		// selection.validate() and ideEditor.validateRange(selection), both of which clamp start/end in
		// place -- on whatever object the CALLER owns. openFileAndSelect and openFile each copy before
		// reaching here, but this is the third entry point IEditorHandler advertises and a caller can
		// use it directly. The interface also declares `selection: Range?`, so `!!` turned a documented
		// null into a KotlinNullPointerException for any caller honouring that nullability; no in-app
		// caller passes null today, which is the only reason it had not fired.
		val editor = CodeEditorView(this, file, selection?.let { Range(it) } ?: Range(Range.NONE))
		editor.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

		if (tabPosition >= totalTabs) {
			safeContent.tabs.addTab(safeContent.tabs.newTab())
			safeContent.editorContainer.addView(editor)
		} else {
			safeContent.tabs.addTab(safeContent.tabs.newTab(), tabPosition)
			safeContent.editorContainer.addView(editor, tabPosition)
			shiftPluginIndices(tabPosition, 1)
		}

		editorViewModel.addFile(file)
		editorViewModel.setCurrentFile(fileIndex, file)

		updateTabs()

		IDEApplication.getPluginManager()?.notifyFileOpened(file)

		return fileIndex
	}

	private fun getNextFileTabPosition(): Int {
		val safeContent = contentOrNull ?: return -1
		val totalTabs = safeContent.tabs.tabCount

		var lastFileTabPos = -1
		for (i in 0 until totalTabs) {
			if (!isPluginTab(i)) {
				lastFileTabPos = i
			}
		}
		return lastFileTabPos + 1
	}

	private fun shiftPluginIndices(
		fromPosition: Int,
		delta: Int,
	) {
		val shifted = mutableMapOf<String, Int>()
		pluginTabIndices.forEach { (id, index) ->
			val newIndex = if (index >= fromPosition) index + delta else index
			if (newIndex >= 0) {
				shifted[id] = newIndex
			}
		}

		pluginTabIndices.clear()
		pluginTabIndices.putAll(shifted)

		tabIndexToPluginId.clear()
		shifted.forEach { (id, index) ->
			tabIndexToPluginId[index] = id
		}

		Log.d("EditorHandlerActivity", "Updated plugin indices after shift: $pluginTabIndices")
	}

	override fun getEditorForFile(file: File): CodeEditorView? {
		val content = contentOrNull ?: return null
		for (i in 0 until content.editorContainer.childCount) {
			val child = content.editorContainer.getChildAt(i)
			if (child is CodeEditorView && file == child.file) {
				return child
			}
		}
		return null
	}

	override fun findIndexOfEditorByFile(file: File?): Int {
		if (file == null) {
			log.error("Cannot find index of a null file.")
			return -1
		}

		for (i in 0 until editorViewModel.getOpenedFileCount()) {
			val opened: File = editorViewModel.getOpenedFile(i)
			if (opened == file) {
				return i
			}
		}

		return -1
	}

	override fun saveAllAsync(
		notify: Boolean,
		requestSync: Boolean,
		processResources: Boolean,
		progressConsumer: ((Int, Int) -> Unit)?,
		runAfter: ((Boolean) -> Unit)?,
	) {
		// Not lifecycleScope: NonCancellable protects the body only once it has started running, and
		// a launch on the IO dispatcher can still be queued when onDestroy() cancels the activity's
		// scope -- in which case the body never starts and runAfter never runs, losing a confirmed
		// deep-link project switch exactly as if the guard that used to skip it were still there
		// (found in review). The application scope has no such window. The activity is retained for
		// the duration of the save, which is what NonCancellable already implied.
		//
		// That retention is real and deliberate: this lambda captures the activity, so a save still in
		// flight after onDestroy holds the binding, the tabs and every editor buffer alive from an
		// app-lifetime root -- tens of MB with many files open, and indefinitely if a write blocks on
		// stuck SAF/FUSE storage. The narrower design is to move only what must outlive the activity
		// (arming pendingDeepLinkOpen, a ~200-byte request) off the activity, and leave the save on
		// lifecycleScope. That is a restructure of this method's contract with its callers, not a
		// tweak, so it is left as a known cost rather than half-done here.
		appScope.launch(Dispatchers.IO) {
			// The whole body -- not just saveAll() -- runs NonCancellable. onDestroy() cancels the
			// activity's Job as soon as it runs; leaving NonCancellable partway through (e.g.
			// right before invoking runAfter) would let that cancellation surface at the next
			// suspension point and drop runAfter entirely instead of running it. Callers rely on it
			// always running (e.g. confirmProjectClose's onClosed, which arms a pending deep-link
			// project switch and would otherwise vanish with no error if this activity is torn down
			// while the save is still in flight).
			withContext(NonCancellable) {
				val saveSucceeded =
					try {
						saveAll(notify, requestSync, processResources, progressConsumer)
						true
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						// A write failure here (e.g. CodeEditorView.save()'s IOException) must not skip
						// runAfter below -- callers rely on it always running to know the save attempt is
						// over, successful or not (e.g. confirmProjectClose's confirmCloseInProgress guard,
						// which would otherwise stay stuck true and permanently block closing this activity).
						log.error("saveAll failed", e)
						false
					}
				withContext(Dispatchers.Main) {
					// NonCancellable above means this whole block, including this Main-dispatcher hop,
					// keeps running even after onDestroy() -- unlike before this method wrapped the
					// entire body in NonCancellable, when that hop was simply dropped on teardown.
					//
					// runAfter is invoked unconditionally, teardown included. A liveness check here used
					// to skip it wholesale, which silently dropped the *non-UI* half of a callback's
					// work: confirmProjectClose's onClosed arms a process-wide pending deep-link switch
					// (ADFA-5067) that has to outlive this instance, so losing it means a confirmed
					// "Save and close" never opens the project the link asked for, with nothing logged.
					// Each callback decides for itself what needs a live window -- see the teardown
					// branches at the two call sites in this file, and GitBottomSheetFragment's own
					// _binding check.
					runAfter?.invoke(saveSucceeded)
				}
			}
		}
	}

	override suspend fun saveAll(
		notify: Boolean,
		requestSync: Boolean,
		processResources: Boolean,
		progressConsumer: ((Int, Int) -> Unit)?,
	): Boolean {
		val result = saveAllResult(progressConsumer)

		// don't bother to switch the context if we don't need to
		if (notify || (result.gradleSaved && requestSync)) {
			withContext(Dispatchers.Main) {
				if (contentOrNull == null) return@withContext
				if (notify) {
					flashSuccess(string.all_saved)
				}

				if (result.gradleSaved && requestSync) {
					editorViewModel.isSyncNeeded = true
				}
			}
		}

		if (processResources) {
			ProjectManagerImpl.getInstance().generateSources()
		}

		return result.gradleSaved
	}

	override suspend fun saveAllResult(progressConsumer: ((Int, Int) -> Unit)?): SaveResult =
		// IO: saveEditorInternal stats the file, and callers reach here on their own
		// dispatchers (SaveFileAction runs off-main, AbstractModuleAssemblerAction does not
		// promise one).
		withContext(Dispatchers.IO) {
			performFileSave {
				val result = SaveResult()
				for (i in 0 until editorViewModel.getOpenedFileCount()) {
					saveResultInternal(i, result)
					progressConsumer?.invoke(i + 1, editorViewModel.getOpenedFileCount())
				}

				return@performFileSave result
			}
		}

	override suspend fun saveResult(
		index: Int,
		result: SaveResult,
	) {
		// IO for the same reason as saveAllResult - and this one is reached from
		// EditorProviderImpl.saveCurrentFile, which launches on lifecycleScope's main
		// dispatcher.
		withContext(Dispatchers.IO) {
			performFileSave {
				saveResultInternal(index, result)
			}
		}
	}

	/**
	 * Saves the buffer for [file] regardless of which tab has focus, and reports whether the
	 * bytes reached disk. Returns `false` when no open editor holds [file].
	 *
	 * A clean buffer counts as saved: [CodeEditorView.save] reports "nothing to do" and
	 * "write failed" with the same `false`, so the two are separated here.
	 *
	 * The editor is handed to the write as a view, never as a tab index: an index is only
	 * valid until the first suspension, and resolving [file] twice through two different
	 * indexing schemes could save a different open buffer than the one that was checked.
	 *
	 * [outcome] receives the verdict from *inside* the save's `NonCancellable` section, so it
	 * survives a caller whose timeout elapses mid-write: the return value cannot report that
	 * (the resume throws [CancellationException] first), but [outcome] still says what reached
	 * disk. Read it instead of the return value when the await may be cut short.
	 */
	internal suspend fun saveFileResult(
		file: File,
		outcome: AtomicReference<FileSaveOutcome> = AtomicReference(FileSaveOutcome.FAILED),
	): Boolean {
		try {
			// Off-main: debug builds install StrictMode's detectDiskReads on the main thread.
			val existedBefore = withContext(Dispatchers.IO) { file.exists() }

			val (view, alreadyClean) =
				withContext(Dispatchers.Main.immediate) {
					val editor = getEditorForFile(file)
					editor to (editor != null && !editor.isModified && existedBefore)
				}
			if (view == null) {
				outcome.set(FileSaveOutcome.NOT_OPEN)
				return false
			}

			// Nothing to write. Returning before [performFileSave] keeps the saving flag from
			// flapping true/false for a no-op, which SaveFileAction observes to enable itself.
			if (alreadyClean) {
				outcome.set(FileSaveOutcome.ALREADY_CLEAN)
				return true
			}

			// IO because the write path stats the file ([CodeEditorView.save]'s own pre-check
			// and the timestamp bookkeeping), and this is reachable from a plugin coroutine
			// running on Main.
			//
			// NonCancellable spans the write *and* the follow-ups it implies: cancelled
			// between them, a Gradle script would land on disk with no sync prompt and a
			// layout with no regenerated R fields - the very failures the block below exists
			// to prevent, reached by the cancellation path instead.
			withContext(Dispatchers.IO + NonCancellable) {
				val result = SaveResult()
				val saved = performFileSave { saveEditorInternal(view, result) }
				// Every claim that the content is on disk is checked against disk. Covers the
				// corners where CodeEditorView.save returns false without writing and without
				// the buffer being clean either - an archive extension, a null text.
				outcome.set(
					if (saved.reachedDisk && !file.exists()) FileSaveOutcome.FAILED else saved,
				)
				if (outcome.get() != FileSaveOutcome.WRITTEN) return@withContext

				// The same follow-ups the UI save paths run (see [saveAll] and
				// SaveFileAction.postExec). Without them a plugin that edits a Gradle script
				// gets no sync prompt, and one that edits a layout gets no R fields for the
				// resources it just added, so the next build fails on unresolved references.
				if (result.gradleSaved) {
					withContext(Dispatchers.Main.immediate) { editorViewModel.isSyncNeeded = true }
				}
				if (result.xmlSaved) {
					ProjectManagerImpl.getInstance().generateSources()
				}
			}
			return outcome.get().reachedDisk
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// ContentReadWrite.writeTo reports a failed write by throwing; that must not escape
			// into the plugin coroutine awaiting this call.
			log.error("Failed to save {}", file.name, err)
			outcome.set(FileSaveOutcome.FAILED)
			return false
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)

		val safeContent = contentOrNull ?: return
		for (i in 0 until safeContent.editorContainer.childCount) {
			(safeContent.editorContainer.getChildAt(i) as? CodeEditorView)?.reapplyEditorDisplayPreferences()
		}

		getCurrentEditor()?.editor?.apply {
			doOnNextLayout {
				cursor?.let { c -> ensurePositionVisible(c.leftLine, c.leftColumn, true) }
			}
		}
	}

	private suspend fun saveResultInternal(
		index: Int,
		result: SaveResult,
	): Boolean {
		if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
			return false
		}

		// getEditorAtIndex walks the editor container, so it is resolved on Main; callers
		// reach here from Dispatchers.IO (see saveAllAsync).
		val frag = withContext(Dispatchers.Main.immediate) { getEditorAtIndex(index) } ?: return false
		// Only a write counts here, preserving what this returned before it reported outcomes.
		return saveEditorInternal(frag, result) == FileSaveOutcome.WRITTEN
	}

	/**
	 * Saves [frag]'s buffer and records what kind of file it was in [result].
	 *
	 * Takes the view rather than a tab index because an index only stays valid until the first
	 * suspension: [CodeEditorView.save] hops to the editor's write thread, and a tab closed
	 * while it runs shifts every index after it. The tab to unmark is re-resolved from the
	 * file afterwards for the same reason.
	 *
	 * Call it off the main thread - it stats the file and delegates to [CodeEditorView.save],
	 * which marshals its own UI work. Editor state is read back on Main.
	 *
	 * The write and the bookkeeping that follows it are `NonCancellable`: [CodeEditorView.save]
	 * is not cancellation-atomic, so cut between `writeTo` and its `markUnmodified()` - or
	 * before the tab below loses its asterisk - it would leave the bytes on disk with the
	 * buffer still flagged dirty. Scoped per file, so a multi-file save can still stop between
	 * files.
	 */
	private suspend fun saveEditorInternal(
		frag: CodeEditorView,
		result: SaveResult,
	): FileSaveOutcome {
		// Editor state lives on the view: read it on Main, and read isModified before
		// frag.save() clears it.
		val (savedFile, modified) =
			withContext(Dispatchers.Main.immediate) {
				frag.file?.let { it to frag.isModified }
			} ?: return FileSaveOutcome.NOT_OPEN
		val fileName = savedFile.name

		return withContext(NonCancellable) {
			val wrote =
				try {
					frag.save()
				} catch (err: IllegalStateException) {
					// The only IllegalStateException [CodeEditorView.save] can raise is its
					// `binding` getter's "Binding has been destroyed", and the two calls that
					// go through it - markUnmodified() and notifySaved() - both run *after* the
					// write (the write section itself uses the nullable `_binding?`). So a tab
					// closed mid-save loses its bookkeeping, not its bytes.
					log.warn("Editor for {} was disposed after its write completed", fileName, err)
					return@withContext if (savedFile.exists()) {
						FileSaveOutcome.WRITTEN
					} else {
						FileSaveOutcome.FAILED
					}
				}

			if (!wrote) {
				// save() reports "nothing to do" and "the write failed" with the same false.
				// The buffer was sampled clean moments ago on Main, so an unmodified buffer here
				// is the former - typically a concurrent UI save-all wrote this same content and
				// marked it unmodified.
				return@withContext if (modified) FileSaveOutcome.FAILED else FileSaveOutcome.ALREADY_CLEAN
			}

			fileTimestamps[savedFile.absolutePath] = savedFile.lastModified()

			val isGradle = fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")
			val isXml: Boolean = fileName.endsWith(".xml")
			if (!result.gradleSaved) {
				result.gradleSaved = modified && isGradle
			}

			if (!result.xmlSaved) {
				result.xmlSaved = modified && isXml
			}

			withContext(Dispatchers.Main) {
				val content = contentOrNull ?: return@withContext
				// Computed here rather than in an earlier hop: this block is queued, and a
				// snapshot taken before it ran would clobber an edit made in another tab
				// meanwhile - leaving Save greyed out over a dirty buffer.
				editorViewModel.areFilesModified = hasUnsavedFiles()

				// set tab as unmodified
				val tabPosition = getTabPositionForFileIndex(findIndexOfEditorByFile(savedFile))
				if (tabPosition < 0) return@withContext
				val tab = content.tabs.getTabAt(tabPosition) ?: return@withContext
				val text = tab.text?.toString() ?: return@withContext
				if (text.startsWith('*')) {
					tab.text = text.substring(1)
				}
			}

			FileSaveOutcome.WRITTEN
		}
	}

	private fun hasUnsavedFiles() =
		editorViewModel.getOpenedFiles().any { file ->
			getEditorForFile(file)?.isModified == true
		}

	/**
	 * Like [hasUnsavedFiles], but excludes files [CodeEditorView.save] never actually writes (an
	 * [ARCHIVE_EXTENSIONS] extension, opened read-only) -- those can never leave the "modified"
	 * state through a save, so counting them as a save failure would block "Save and close" forever.
	 *
	 * @param files The files to check -- defaults to every currently open file, appropriate for a
	 * whole-project close like [confirmProjectClose]. A narrower close (e.g. [closeFile]'s single
	 * tab, via [notifyFilesUnsaved]) must scope this to just the file(s) actually being closed, or
	 * an unrelated, still-open file's save failure would block a close it has nothing to do with.
	 */
	private fun hasFilesThatFailedToSave(files: List<File> = editorViewModel.getOpenedFiles()): Boolean {
		// Fail closed once the view binding is gone. [getEditorForFile] resolves through
		// contentOrNull and returns null for EVERY file the moment it is null, so the per-file test
		// below would report "nothing failed" for a whole set of genuinely modified buffers. Every
		// caller uses this to decide whether it is safe to close, discard or commit, so an answer
		// that cannot be computed must read as "unsafe" -- the direction the ViewModel-backed
		// areFilesModified this replaced happened to fail in.
		//
		// areFilesModified is the fallback rather than a bare `true` because it is retained across
		// activity recreation and is only ever recomputed while the binding is alive (see
		// saveResult's contentOrNull-guarded block), so it holds the last state actually observed
		// rather than a stale-by-construction guess. It is coarser -- one flag for all open files,
		// not per-file -- which can over-report for a narrowed [files] set; over-reporting blocks a
		// close, under-reporting loses the buffer.
		contentOrNull ?: return editorViewModel.areFilesModified
		return files.any { file ->
			getEditorForFile(file)?.isModified == true && file.extension.lowercase() !in ARCHIVE_EXTENSIONS
		}
	}

	/**
	 * Runs [action] with the "files are saving" flag raised.
	 *
	 * Counted rather than a plain boolean: a plugin-thread save can overlap a UI save, and the
	 * first one to finish must not clear the flag while the other is still writing.
	 */
	private suspend inline fun <T : Any?> performFileSave(crossinline action: suspend () -> T): T {
		try {
			// Inside the try, not before it: [beginFileSave]'s block is guaranteed to run (its
			// context's job is NonCancellable), but `withContext` still honours prompt
			// cancellation on *resume* when the caller is off-main - so it can increment the
			// count and then throw. Outside the try that increment never gets its decrement,
			// and areFilesSaving latches on for the life of the retained ViewModel.
			beginFileSave()
			return action()
		} finally {
			endFileSave()
		}
	}

	/**
	 * Raises the saving flag for one save.
	 *
	 * The count moves in the same main-thread section as the flag it guards, so the counter's
	 * ordering *is* the flag's ordering. Bumping the count off-main instead let a finished
	 * off-main save's queued `false` land after a main-thread save had already written `true`
	 * inline (`Main.immediate` skips the queue when it is already on main), leaving
	 * [EditorViewModel.areFilesSaving] false while that save was still writing.
	 *
	 * NonCancellable: a cancelled save (e.g. a plugin-side timeout) must still reach its
	 * matching [endFileSave], or SaveFileAction stays disabled for the rest of the session.
	 */
	@VisibleForTesting
	internal suspend fun beginFileSave() {
		withContext(NonCancellable + Dispatchers.Main.immediate) {
			editorViewModel.beginFileSave()
		}
	}

	/** Lowers the saving flag once the last in-flight save finishes. See [beginFileSave]. */
	@VisibleForTesting
	internal suspend fun endFileSave() {
		withContext(NonCancellable + Dispatchers.Main.immediate) {
			editorViewModel.endFileSave()
		}
	}

	override fun areFilesModified(): Boolean = editorViewModel.areFilesModified

	override fun hasUnsavedWritableFiles(): Boolean = hasFilesThatFailedToSave()

	override fun areFilesSaving(): Boolean = editorViewModel.areFilesSaving

	override fun closeFile(
		index: Int,
		runAfter: () -> Unit,
	) {
		if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
			log.error("Invalid file index. Cannot close.")
			return
		}

		val opened = editorViewModel.getOpenedFile(index)
		log.info("Closing file: {}", opened)

		val editor = getEditorAtIndex(index)
		if (editor?.isModified == true) {
			log.info("File has been modified: {}", opened)
			notifyFilesUnsaved(listOf(editor)) {
				closeFile(index, runAfter)
			}
			return
		}

		IDEApplication.getPluginManager()?.notifyFileClosed(opened)

		editor?.close() ?: run {
			log.error("Cannot save file before close. Editor instance is null")
		}

		val tabPosition = getTabPositionForFileIndex(index)
		editorViewModel.removeFile(index)

		if (tabPosition >= 0) {
			content.tabs.removeTabAt(tabPosition)
			content.editorContainer.removeViewAt(tabPosition)
			shiftPluginIndices(tabPosition + 1, -1)
		}

		editorViewModel.areFilesModified = hasUnsavedFiles()
		updateTabs()
		runAfter()
	}

	override fun closeOthers() {
		if (editorViewModel.getOpenedFileCount() == 0) {
			return
		}

		val unsavedFiles =
			editorViewModel
				.getOpenedFiles()
				.map(::getEditorForFile)
				.filter { it != null && it.isModified }

		if (unsavedFiles.isNotEmpty()) {
			notifyFilesUnsaved(unsavedFiles) { closeOthers() }
			return
		}

		val file = editorViewModel.getCurrentFile()
		var index = 0

		// keep closing the file at index 0
		// if openedFiles[0] == file, then keep closing files at index 1
		while (editorViewModel.getOpenedFileCount() != 1) {
			val editor = getEditorAtIndex(index)

			if (editor == null) {
				log.error("Unable to save file at index {}", index)
				continue
			}

			// Index of files changes as we keep close files
			// So we compare the files instead of index
			if (file != editor.file) {
				closeFile(index)
			} else {
				index = 1
			}
		}
	}

	override fun openFAQActivity(htmlData: String) {
		val intent = Intent(this, FAQActivity::class.java)
		intent.putExtra(CONTENT_KEY, htmlData)
		startActivity(intent)
	}

	override fun closeAll(runAfter: () -> Unit) {
		val unsavedFiles =
			editorViewModel
				.getOpenedFiles()
				.map(this::getEditorForFile)
				.filter { it != null && it.isModified }

		if (unsavedFiles.isNotEmpty()) {
			// If there are unsaved files, show the confirmation dialog.
			notifyFilesUnsaved(unsavedFiles) { closeAll(runAfter) }
			return
		}

		// If there are NO unsaved files, just perform the close action directly. This action
		// doesn't exit the activity by itself (performCloseAllFiles never finishes; only
		// closeProject does).
		performCloseAllFiles()
		runAfter()
	}

	override fun getOpenedFiles() =
		editorViewModel.getOpenedFiles().mapNotNull {
			val editor = getEditorForFile(it)?.editor ?: return@mapNotNull null
			OpenedFile(it.absolutePath, editor.cursorLSPRange)
		}

	fun closeCurrentFile() {
		val tabPosition = content.tabs.selectedTabPosition

		if (isPluginTab(tabPosition)) {
			closePluginTab(tabPosition)
			return
		}

		val fileIndex = getFileIndexForTabPosition(tabPosition)
		if (fileIndex >= 0) {
			closeFile(fileIndex) {
				invalidateOptionsMenu()
			}
		}
	}

	private fun closePluginTab(tabPosition: Int) {
		val pluginId = tabIndexToPluginId[tabPosition] ?: return

		try {
			val fragment = supportFragmentManager.findFragmentByTag("plugin_tab_$pluginId")
			if (fragment != null) {
				supportFragmentManager
					.beginTransaction()
					.remove(fragment)
					.commitAllowingStateLoss()
			}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.closeTab(pluginId)
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Error cleaning up plugin tab $pluginId", e)
		}

		content.tabs.removeTabAt(tabPosition)
		content.editorContainer.removeViewAt(tabPosition)

		pluginTabIndices.remove(pluginId)
		tabIndexToPluginId.remove(tabPosition)

		shiftPluginIndices(tabPosition + 1, -1)
		updateTabVisibility()

		invalidateOptionsMenu()
		Log.d("EditorHandlerActivity", "Successfully closed plugin tab: $pluginId")
	}

	private fun notifyFilesUnsaved(
		unsavedEditors: List<CodeEditorView?>,
		invokeAfter: Runnable,
	) {
		if (isDestroying) {
			// Do not show unsaved files dialog if the activity is being destroyed
			// TODO Use a service to save files and to avoid file content loss
			for (editor in unsavedEditors) {
				editor?.markUnmodified()
			}
			invokeAfter.run()
			return
		}

		val mapped = unsavedEditors.mapNotNull { it?.file?.absolutePath }
		val builder =
			showConfirmationDialog(
				context = this,
				title = getString(string.title_files_unsaved),
				message = getString(string.msg_files_unsaved, TextUtils.join("\n", mapped)),
				positiveClickListener = { dialog, _ ->
					dialog.dismiss()
					saveAllAsync(
						notify = true,
						runAfter = { succeeded ->
							runOnUiThread {
								// Nothing in this tail survives teardown usefully: flashError needs a live
								// window, and invokeAfter closes tabs on a binding that is going away. The
								// write itself already completed in saveAllAsync.
								if (isFinishing || isDestroyed) return@runOnUiThread
								// Matches confirmProjectClose's identical check: saveAllAsync's succeeded
								// only means saveAll() didn't throw, not that every file's write actually
								// landed (a silent per-file failure, e.g. disk full, leaves isModified
								// true without succeeded going false) -- proceeding to invokeAfter (which
								// closes/discards these files) on that alone risks silent data loss.
								// Scoped to unsavedEditors (not every open file, unlike confirmProjectClose's
								// whole-project close) -- this call can be for a single tab (closeFile), and
								// an unrelated, still-open file's save failure must not block it.
								if (!succeeded || hasFilesThatFailedToSave(unsavedEditors.mapNotNull { it?.file })) {
									flashError(getString(string.save_failed))
									return@runOnUiThread
								}
								invokeAfter.run()
							}
						},
					)
				},
			) { dialog, _ ->
				dialog.dismiss()
				// Mark all the files as saved, then try to close them all
				for (editor in unsavedEditors) {
					editor?.markAsSaved()
				}
				invokeAfter.run()
			}
		builder.show()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onFileRenamed(event: FileRenameEvent) {
		val content = contentOrNull ?: return
		val index = findIndexOfEditorByFile(event.file)
		if (index < 0 || index >= content.tabs.tabCount) {
			return
		}

		val editor = getEditorAtIndex(index) ?: return
		editorViewModel.updateFile(index, event.newFile)
		editor.updateFile(event.newFile)

		updateTabs()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (contentOrNull == null) return

		val fileIndex = findIndexOfEditorByFile(event.file.toFile())
		if (fileIndex == -1) return

		// The editor recomputes its modified flag on every content change, so a change may
		// have returned the file to its saved state (e.g. the user undid all their edits).
		val isModified = getEditorAtIndex(fileIndex)?.isModified ?: false
		editorViewModel.areFilesModified = hasUnsavedFiles()

		val tabPosition = getTabPositionForFileIndex(fileIndex)
		if (tabPosition < 0) return

		val tab = content.tabs.getTabAt(tabPosition) ?: return
		val hasIndicator = tab.text?.startsWith('*') == true
		if (isModified == hasIndicator) return

		val baseName = tab.text?.removePrefix("*") ?: return
		tab.text = if (isModified) "*$baseName" else baseName
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onPluginCrashed(event: PluginCrashedEvent) {
		if (event.wasDisabled) {
			tearDownDisabledPluginContributions(event.pluginId)
		}
		showPluginCrashDialog(event)
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onPreferenceChanged(event: PreferenceChangeEvent) {
		if (event.key == EditorPreferences.FONT_SIZE) {
			syncPluginUiFontSize()
		}
	}

	private fun syncPluginUiFontSize() {
		val scale = EditorPreferences.editorFontScale
		if (scale == lastAppliedPluginFontScale) {
			return
		}
		lastAppliedPluginFontScale = scale

		val pluginFragments = mutableListOf<Fragment>()
		collectPluginFragments(supportFragmentManager, pluginFragments)
		pluginFragments.forEach { fragment ->
			fragment.view?.let { applyPluginFontScale(it, scale) }
		}
	}

	private fun isPluginFragment(fragment: Fragment): Boolean = fragment.javaClass.classLoader !== javaClass.classLoader

	private fun applyPluginFontScale(
		root: View,
		scale: Float,
	) {
		root.forEachViewRecursively { view ->
			if (view is TextView) {
				val baseSize = pluginTextBaseSizes.getOrPut(view) { view.textSize }
				view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize * scale)
			}
		}
	}

	private fun collectPluginFragments(
		manager: FragmentManager,
		into: MutableList<Fragment>,
	) {
		manager.fragments.forEach { fragment ->
			if (isPluginFragment(fragment)) {
				into.add(fragment)
			} else {
				collectPluginFragments(fragment.childFragmentManager, into)
			}
		}
	}

	private fun showPluginCrashDialog(event: PluginCrashedEvent) {
		val dialogView = layoutInflater.inflate(R.layout.dialog_plugin_crash, null)
		dialogView.findViewById<TextView>(R.id.plugin_crash_message).text =
			if (event.wasDisabled) {
				getString(string.msg_plugin_crash_disabled, event.pluginName)
			} else {
				getString(string.msg_plugin_crash, event.pluginName, event.crashCount)
			}

		val builder =
			newMaterialDialogBuilder(this)
				.setTitle(string.title_plugin_crashed)
				.setView(dialogView)
				.setPositiveButton(string.dismiss, null)

		if (event.wasDisabled) {
			builder.setNegativeButton(string.plugin_manager) { _, _ ->
				startActivity(Intent(this, PluginManagerActivity::class.java))
			}
		}

		builder.show()

		dialogView.findViewById<View>(R.id.plugin_crash_view_logs).setOnClickListener {
			showPluginCrashLogDialog(event)
		}
	}

	private fun showPluginCrashLogDialog(event: PluginCrashedEvent) {
		newMaterialDialogBuilder(this)
			.setTitle(getString(string.title_plugin_crash_log, event.pluginName))
			.setMessage(event.stackTrace)
			.setPositiveButton(string.close, null)
			.setNeutralButton(string.copy) { _, _ ->
				val clipboard = getSystemService(ClipboardManager::class.java)
				clipboard?.setPrimaryClip(
					ClipData.newPlainText(
						getString(string.title_plugin_crash_log, event.pluginName),
						event.stackTrace,
					),
				)
				flashSuccess(string.msg_crash_log_copied)
			}.show()
	}

	private fun tearDownDisabledPluginContributions(pluginId: String) {
		runCatching {
			val pluginManager = IDEApplication.getPluginManager() ?: return
			val tabManager = PluginEditorTabManager.getInstance()

			val tabsToClose =
				pluginTabIndices.keys.toList().filter { tabId ->
					tabManager.getPluginIdForTab(tabId) == pluginId
				}
			tabsToClose.forEach { tabId ->
				val index = pluginTabIndices[tabId] ?: return@forEach
				closePluginTab(index)
			}

			tabManager.loadPluginTabs(pluginManager)

			val registry = getInstance()
			registry.clearActions(ActionItem.Location.EDITOR_SIDEBAR)
			EditorSidebarActions.registerActions(this)
			(supportFragmentManager.findFragmentById(R.id.drawer_sidebar) as? EditorSidebarFragment)
				?.let { EditorSidebarActions.setup(it) }

			EditorActivityActions.register(this)

			invalidateOptionsMenu()

			Log.i("EditorHandlerActivity", "Tore down contributions for disabled plugin: $pluginId")
		}.onFailure { e ->
			Log.e("EditorHandlerActivity", "Failed to tear down contributions for disabled plugin: $pluginId", e)
		}
	}

	private fun updateTabs() {
		editorActivityScope.launch {
			val files = editorViewModel.getOpenedFiles()
			val dupliCount = mutableMapOf<String, Int>()
			val names = MutableIntObjectMap<Pair<String, Int>>()
			val nameBuilder = UniqueNameBuilder<File>("", File.separator)

			files.forEach {
				var count = dupliCount[it.name] ?: 0
				dupliCount[it.name] = ++count
				nameBuilder.addPath(it, it.path)
			}

			for (tabPos in 0 until content.tabs.tabCount) {
				if (isPluginTab(tabPos)) continue
				val fileIndex = getFileIndexForTabPosition(tabPos)
				if (fileIndex < 0) continue
				val file = files.getOrNull(fileIndex) ?: continue
				val count = dupliCount[file.name] ?: 0

				val isModified = getEditorAtIndex(fileIndex)?.isModified ?: false
				var name = if (count > 1) nameBuilder.getShortPath(file) else file.name
				if (isModified) {
					name = "*$name"
				}

				names[tabPos] = name to FileExtension.Factory.forFile(file, file.isDirectory).icon
			}

			withContext(Dispatchers.Main) {
				val content = contentOrNull ?: return@withContext
				names.forEach { index, (name, iconId) ->
					val tab = content.tabs.getTabAt(index) ?: return@forEach
					tab.icon = ResourcesCompat.getDrawable(resources, iconId, theme)
					tab.text = name
					tab.view.setOnLongClickListener {
						TooltipManager.showIdeCategoryTooltip(
							context = this@EditorHandlerActivity,
							anchorView = tab.view,
							tag = TooltipTag.PROJECT_FILENAME,
						)
						true
					}
				}
			}
		}
	}

	/**
	 * Adds a one-time listener that will be invoked when the current build process finishes.
	 * The listener will be automatically removed after being called.
	 */
	fun addOneTimeBuildResultListener(listener: Consumer<BuildResult>) {
		singleBuildListeners.add(listener)
	}

	fun removeOneTimeBuildResultListener(listener: Consumer<BuildResult>) {
		singleBuildListeners.remove(listener)
	}

	/**
	 * Called by [EditorBuildEventListener] to notify all registered listeners of the build result.
	 */
	fun notifyBuildResult(result: BuildResult) {
		// Ensure this runs on the main thread if UI updates are needed from listeners
		runOnUiThread {
			singleBuildListeners.forEach { it.accept(result) }
			singleBuildListeners.clear()
		}
	}

	fun selectPluginTabById(tabId: String): Boolean {
		// Check if the tab already exists
		val existingTabIndex = pluginTabIndices[tabId]
		if (existingTabIndex != null) {
			val tab = content.tabs.getTabAt(existingTabIndex)
			if (tab != null && !tab.isSelected) {
				tab.select()
			}
			return true
		}

		return createPluginTab(tabId)
	}

	private fun createPluginTab(tabId: String): Boolean {
		try {
			val pluginManager =
				IDEApplication.getPluginManager() ?: run {
					Log.w("EditorHandlerActivity", "Plugin manager not available")
					return false
				}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.loadPluginTabs(pluginManager)

			val pluginTabs = tabManager.getAllPluginTabs()
			val pluginTab =
				pluginTabs.find { it.id == tabId } ?: run {
					return false
				}

			runOnUiThread {
				val content = contentOrNull ?: return@runOnUiThread

				val tab = content.tabs.newTab()
				tab.text = pluginTab.title

				val iconRes = pluginTab.icon
				if (iconRes != null) {
					val pluginId = tabManager.getPluginIdForTab(pluginTab.id)
					tab.icon = PluginDrawableResolver.resolve(iconRes, pluginId, this@EditorHandlerActivity)
						?: ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_info_details, theme)
				}

				val tabIndex = content.tabs.tabCount

				pluginTabIndices[pluginTab.id] = tabIndex
				tabIndexToPluginId[tabIndex] = pluginTab.id

				val containerView =
					android.widget.FrameLayout(this@EditorHandlerActivity).apply {
						id = android.view.View.generateViewId()
						layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
					}
				content.editorContainer.addView(containerView)

				val fragment = tabManager.getOrCreateTabFragment(pluginTab.id)
				if (fragment != null) {
					supportFragmentManager
						.beginTransaction()
						.add(containerView.id, fragment, "plugin_tab_${pluginTab.id}")
						.commitNowAllowingStateLoss()
					Log.d("EditorHandlerActivity", "Plugin fragment added to container for tab: ${pluginTab.id}")
				} else {
					Log.w("EditorHandlerActivity", "Failed to create fragment for plugin tab: ${pluginTab.id}")
				}

				content.tabs.addTab(tab)

				if (!tab.isSelected) {
					tab.select()
				}
				editorViewModel.displayedFileIndex = -1
				updateTabVisibility()

				pluginTabIndices.forEach {
					val tab = content.tabs.getTabAt(it.value) ?: return@forEach
					tab.view.setOnLongClickListener {
						TooltipManager.showIdeCategoryTooltip(
							context = this@EditorHandlerActivity,
							anchorView = tab.view,
							tag = TooltipTag.PROJECT_PLUGIN_TAB,
						)
						true
					}
				}
			}

			return true
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to create plugin tab $tabId", e)
			return false
		}
	}

	private fun setupPluginFragmentFactory() {
		try {
			val defaultFactory = supportFragmentManager.fragmentFactory
			supportFragmentManager.fragmentFactory = PluginFragmentFactory(defaultFactory)
			Log.d("EditorHandlerActivity", "PluginFragmentFactory installed")
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to setup PluginFragmentFactory", e)
		}
	}

	fun loadPluginTabs() {
		try {
			val pluginManager =
				IDEApplication.getPluginManager() ?: run {
					Log.w("EditorHandlerActivity", "Plugin manager not available, skipping plugin tab loading")
					return
				}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.loadPluginTabs(pluginManager)

			val pluginTabs = tabManager.getAllPluginTabs()

			if (pluginTabs.isEmpty()) {
				Log.d("EditorHandlerActivity", "No plugin tabs to load")
				return
			}
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to load plugin tabs", e)
		}
	}

	fun isPluginTab(position: Int): Boolean {
		val safeContent = contentOrNull ?: return false
		val totalTabs = safeContent.tabs.tabCount

		if (position !in 0..<totalTabs) {
			return false
		}
		return tabIndexToPluginId.containsKey(position)
	}

	fun getPluginTabId(position: Int): String? = tabIndexToPluginId[position]

	private fun canClosePluginTab(position: Int): Boolean {
		val pluginId = tabIndexToPluginId[position] ?: return false
		val tabManager = PluginEditorTabManager.getInstance()
		return tabManager.canCloseTab(pluginId)
	}

	fun updateTabVisibility() {
		val safeContent = contentOrNull ?: return
		val hasFiles = editorViewModel.getOpenedFileCount() > 0
		val hasPluginTabs = pluginTabIndices.isNotEmpty()

		safeContent.apply {
			if (!hasFiles && !hasPluginTabs) {
				tabs.visibility = View.GONE
				viewContainer.displayedChild = 1
			} else {
				tabs.visibility = View.VISIBLE
				viewContainer.displayedChild = 0
			}
		}
	}

	/**
	 * Converts tab position to actual file index, accounting for plugin tabs.
	 * Plugin tabs don't have corresponding file indices.
	 */
	fun getFileIndexForTabPosition(tabPosition: Int): Int {
		if (isPluginTab(tabPosition)) {
			return -1 // Plugin tabs don't have file indices
		}

		// Count how many plugin tabs come before this position
		var pluginTabsBefore = 0
		for (i in 0 until tabPosition) {
			if (isPluginTab(i)) {
				pluginTabsBefore++
			}
		}

		// The file index is the tab position minus the plugin tabs before it
		return tabPosition - pluginTabsBefore
	}

	fun showPluginTabPopup(tab: TabLayout.Tab) {
		val anchorView = tab.view ?: return

		// Check if this plugin tab can actually be closed
		val position = tab.position
		if (!canClosePluginTab(position)) {
			return
		}

		val binding =
			FileActionPopupWindowBinding.inflate(
				android.view.LayoutInflater.from(this),
				null,
				false,
			)

		val popupWindow =
			android.widget
				.PopupWindow(
					binding.root,
					LayoutParams.WRAP_CONTENT,
					LayoutParams.WRAP_CONTENT,
				).apply {
					elevation = 2f
					isOutsideTouchable = true
				}

		val closeItem =
			FileActionPopupWindowItemBinding
				.inflate(
					android.view.LayoutInflater.from(this),
					null,
					false,
				).root

		closeItem.apply {
			text = "Close Tab"
			setOnClickListener {
				val position = tab.position
				if (isPluginTab(position)) {
					closePluginTab(position)
				}
				popupWindow.dismiss()
			}
		}

		binding.root.addView(closeItem)

		val undockItem =
			FileActionPopupWindowItemBinding
				.inflate(
					android.view.LayoutInflater.from(this),
					null,
					false,
				).root
		undockItem.apply {
			text = getString(string.undock)
			setOnLongClickListener {
				TooltipManager.showIdeCategoryTooltip(
					context = this@EditorHandlerActivity,
					anchorView = anchorView,
					tag = TooltipTag.WINDOW_UNDOCK,
				)
				popupWindow.dismiss()
				true
			}
			setOnClickListener {
				val pos = tab.position
				val tabId = getPluginTabId(pos)
				if (tabId != null) {
					undockPluginTab(tabId, pos)
				}
				popupWindow.dismiss()
			}
		}
		binding.root.addView(undockItem)

		popupWindow.showAsDropDown(anchorView, 0, 0)
	}

	override fun doConfirmProjectClose() {
		confirmProjectClose()
	}

	private fun performCloseAllFiles() {
		val pluginManager = IDEApplication.getPluginManager()
		val fileCount = editorViewModel.getOpenedFileCount()
		for (i in 0 until fileCount) {
			pluginManager?.notifyFileClosed(editorViewModel.getOpenedFile(i))
			getEditorAtIndex(i)?.close()
		}

		// Close all plugin tabs
		val pluginTabIds = this.pluginTabIndices.keys.toList()
		for (pluginId in pluginTabIds) {
			val tabIndex = this.pluginTabIndices[pluginId]
			if (tabIndex != null) {
				this.closePluginTab(tabIndex)
			}
		}

		editorViewModel.removeAllFiles()
		content.apply {
			tabs.removeAllTabs()
			editorContainer.removeAllViews()
		}
	}

	// [onClosed] (e.g. arming a pending deep-link project switch -- see confirmProjectClose) runs
	// synchronously here, before the deferred finish() above can land: onDestroy()'s drain reads
	// what it arms, and onDestroy() cannot run before finish() does.
	private fun closeProject(
		saveFloatingFiles: Boolean,
		onClosed: (() -> Unit)? = null,
	) {
		performCloseAllFiles()
		lifecycleScope.launch {
			floatingTabController.closeAll(save = saveFloatingFiles)
			finish()
		}
		onClosed?.invoke()
	}

	// Tracked so onDestroy() can dismiss it (avoiding a leaked window) and so a confirm-close flow
	// already in progress -- dialog showing, or its "Save and close" still writing files -- can
	// reject a second, overlapping confirmProjectClose call rather than either stacking a second
	// dialog or silently swapping out the one the user is already looking at. The two flows this
	// guards between are the plain manual close (back button, sidebar action, onClosed == null) and
	// the deep-link close-then-reopen (onClosed sets pendingDeepLinkOpen) -- letting one hijack the
	// other's dialog would mean a user tapping "Close without saving" on what looks like an ordinary
	// close ends up with an unrelated deep-linked project opened instead, or vice versa.
	private var activeProjectCloseDialog: AlertDialog? = null
	private var confirmCloseInProgress = false

	// The onClosed to actually run once the in-flight confirm-close flow resolves. Read at
	// resolution time rather than captured per-call, so a THIRD overlapping request (e.g. a deep
	// link C arriving while confirmCloseInProgress is already true for an earlier B) can supersede
	// B by overwriting this field, instead of being silently dropped by the confirmCloseInProgress
	// guard below with no way to ever apply it.
	private var pendingCloseCallback: (() -> Unit)? = null

	// True only once the user actually answered the confirm-close dialog with one of its two
	// confirm options ("Close without saving" / "Save and close"). onDestroy() gates its
	// pendingCloseCallback drain on this rather than on isFinishing alone: isFinishing is true for
	// ANY finish -- including the task being swiped out of Recents while the dialog is still up --
	// and pendingCloseCallback is armed the moment the dialog opens, so without this flag that
	// swipe ran the callback and performed a project switch the user never confirmed, out of
	// onDestroy(), with the previous project's buffers never saved or closed. Reset whenever a
	// fresh dialog is shown; a cancel/decline never sets it.
	private var closeDialogAnswered = false

	// True once the close is actually being PERFORMED, which is a later moment than
	// closeDialogAnswered for "Save and close": that option only commits after its save comes back
	// clean, so between the tap and the save resolving the close is answered but not yet committed.
	// onDestroy() gates its pendingCloseCallback drain on this, because a finish() arriving inside
	// that window used to commit the switch on the answered flag alone and skip the
	// `!saveSucceeded || hasFilesThatFailedToSave()` abort entirely -- discarding the user's unsaved
	// edits with nothing shown (ADFA-5067 review). "Close without saving" sets it immediately: there
	// is no write to wait on and closeProject() runs in the same breath. Reset with the dialog.
	private var closeCommitted = false

	// Identity token for this instance's entry in the process-wide PendingDeepLinkOpen, so an
	// instance only ever drains the hand-off it armed itself. A plain Any() rather than `this`: the
	// token is stored in a Koin `single` that outlives every activity, and parking an Activity
	// reference there -- even one only ever compared by identity -- is the kind of thing that turns
	// into a leak the first time someone dereferences it.
	private val handoffOwner = Any()

	// Captured in onNewIntent, right before setIntent() replaces the intent, whenever the incoming
	// intent targets a genuinely different project -- restored by cancelOrDecline()/the "Save and
	// close" failure branch below if that switch attempt doesn't end up completing, so the staying
	// project's own still-pending file request (if any) isn't silently lost.
	private var pendingFileRequestBeforeSwitch: PendingFileRequest? = null

	// True once pendingFileRequestBeforeSwitch has captured the ORIGINAL staying project's request.
	// Without this, a second overlapping project-switch intent arriving before the first is
	// resolved/declined would re-capture from getIntent() -- which by then holds the FIRST switch
	// attempt's intent, not the original -- clobbering the real value with whatever (usually
	// nothing) that intermediate intent happened to carry.
	private var capturedPendingFileRequestBeforeSwitch = false

	// The project that is actually staying open if the in-flight switch is cancelled or declined,
	// snapshotted when the switch is detected. Not re-derived at restore time: on the plain-switch
	// path MainActivity.openProject's bookkeeping has already overwritten
	// IProjectManager.projectDirPath to the NEW project before the intent is even delivered here, so
	// reading that global on decline restored PROJECT_PATH to the project the user just refused --
	// and the next config-change recreate then loaded it, with the open buffers still belonging to
	// the project that stayed. onNewIntent computes this same value for isProjectSwitchIntent; this
	// keeps it for the decline path, which runs long after that local has gone.
	private var stayingProjectPathBeforeSwitch: String? = null

	// Tracked so a slower, older deep-link resolve (still in flight when a second, faster-resolving
	// deep link arrives via onNewIntent) can tell it's been superseded -- mirrors
	// MainActivity.latestDeepLinkRequest's identical race on the cold-open path.
	private var latestDeepLinkRequest: DeepLinkRequest? = null

	// While a switch is proposed but unconfirmed, IProjectManager's global already names the INCOMING
	// project (MainActivity.openProject's bookkeeping runs before the intent is even delivered), so
	// the base implementation would persist a project the user has not agreed to open -- and
	// onSaveInstanceState runs before onDestroy, so onDestroy's decline rollback below cannot undo it.
	// The snapshot is null except in exactly that window, so this is the global everywhere else.
	override val projectPathForInstanceState: String
		get() = stayingProjectPathBeforeSwitch ?: super.projectPathForInstanceState

	// The state-rollback half of confirmProjectClose's cancelOrDecline, without its other half --
	// re-confirming a superseding request, which shows a NEW dialog and must never run from
	// onDestroy(), where the window it would attach to is already going away.
	private fun declineInFlightProjectClose() {
		confirmCloseInProgress = false
		val abandoned = pendingCloseCallback
		pendingCloseCallback = null
		// Only a switch (onClosed != null) put a foreign PROJECT_PATH on the intent and moved the
		// process-wide bookkeeping; a plain manual close has nothing to roll back, and touching the
		// intent for one would corrupt whatever legitimate pending state it holds.
		if (abandoned != null) {
			restoreIntentToStayingProject()
		}
	}

	private fun restoreIntentToStayingProject() {
		// Guarded here, not at the call sites. Only one of the three had this check, and the other two
		// (cancelOrDecline and the save-failure branch) are reachable with no capture: onNewIntent and
		// switchToProject answer "is this a switch?" with different predicates -- the former via
		// isDeepLinkTargetOfOpenProject (normalised name plus canonicalised parent), the latter via raw
		// string equality -- so they disagree whenever the open project's stored path reaches the same
		// directory by another string (/sdcard vs /storage/emulated/0, a symlinked alias). With no
		// capture taken, the `restore == null` arm below drains the staying project's own
		// carried-forward file request, destroying a navigation it was never asked to touch.
		if (!capturedPendingFileRequestBeforeSwitch) {
			return
		}
		// Reset unconditionally, before the blank-path bail below: a blank projectDirPath (e.g. a
		// post-process-death recreate with no PROJECT_PATH) must not leave these permanently set --
		// every later switch's capture guard would otherwise stay false forever, silently losing the
		// staying project's pending file request on every subsequent decline for the rest of this
		// instance's life.
		val restore = pendingFileRequestBeforeSwitch
		val staying = stayingProjectPathBeforeSwitch
		pendingFileRequestBeforeSwitch = null
		capturedPendingFileRequestBeforeSwitch = false
		stayingProjectPathBeforeSwitch = null

		// The snapshot taken when the switch was detected, not the live global: on the plain-switch
		// path the global already holds the NEW project by the time this runs.
		val stayingProjectPath = staying ?: IProjectManager.getInstance().projectDirPath
		if (stayingProjectPath.isBlank()) return

		// Roll the process-wide bookkeeping back too, not just the intent. MainActivity.openProject
		// writes both of these before this dialog can be answered, so a decline otherwise leaves the
		// app pointing at the project the user just refused: applyDeepLinkFileRequest resolves file
		// paths against projectDirPath, so a later deep-link file navigation would look inside the
		// wrong project, and lastOpenedProject would reopen it on the next cold start. Recents and
		// the analytics event are deliberately left alone -- the user did ask to open it, and an
		// insert that already happened is not wrong, merely early.
		if (IProjectManager.getInstance().projectDirPath != stayingProjectPath) {
			ProjectManagerImpl.getInstance().projectPath = stayingProjectPath
			GeneralPreferences.lastOpenedProject = stayingProjectPath
		}
		intent.putExtra(EditorIntentExtras.EXTRA_PROJECT_PATH, stayingProjectPath)
		// The abandoned switch's own file request (if any) is drained first -- durably, so a
		// post-process-death redelivery can't resurrect the navigation the user just declined --
		// then the staying project's still-pending request, if any, is re-armed over it.
		drainPendingFileRequest()
		restore?.let { armPendingFileRequest(intent, it) }
	}

	private fun confirmProjectClose(onClosed: (() -> Unit)? = null) {
		val content = contentOrNull ?: return
		if (confirmCloseInProgress) {
			// A plain close (onClosed == null, e.g. back button/sidebar) must not erase an
			// already-armed deep-link switch -- only a request that carries its own callback
			// supersedes the pending one.
			if (onClosed != null) {
				pendingCloseCallback = onClosed
			}
			flashError(getString(string.msg_project_close_in_progress))
			return
		}
		confirmCloseInProgress = true
		pendingCloseCallback = onClosed
		closeDialogAnswered = false
		closeCommitted = false

		val builder = newMaterialDialogBuilder(this)
		builder.setTitle(string.title_confirm_project_close)
		builder.setMessage(string.msg_confirm_project_close)

		// If a later, superseding request (e.g. a second deep link arriving while this dialog was
		// already showing) overwrote pendingCloseCallback, cancelling *this* dialog must not
		// silently drop that superseding request too -- give it its own confirmation instead.
		// confirmCloseInProgress is reset first so the recursive call starts a fresh dialog rather
		// than hitting the "already in progress" guard above.
		fun cancelOrDecline() {
			confirmCloseInProgress = false
			val superseding = pendingCloseCallback
			pendingCloseCallback = null
			if (superseding !== onClosed) {
				confirmProjectClose(superseding)
			} else if (onClosed != null) {
				// onNewIntent/handlePlainProjectSwitch already called setIntent() with the abandoned
				// switch's target (PROJECT_PATH/PendingFileRequest) before this dialog could even show
				// -- a genuine decline of that switch (onClosed != null, nothing superseding it) must
				// restore the intent to reflect the project that's actually staying open, or a later
				// process-death recreate would read the abandoned target from getIntent() and silently
				// reopen it instead of resuming this one (see BaseEditorActivity.onCreate's PROJECT_PATH
				// fallback). A plain manual close (onClosed == null, e.g. the sidebar's "Close Project")
				// never went through onNewIntent's setIntent() in the first place -- there's nothing to
				// restore, and touching the intent here would instead corrupt whatever legitimate
				// pending state it already holds (e.g. an original cold-open's still-unconsumed file
				// request, mid-sync).
				restoreIntentToStayingProject()
			}
		}

		builder.setOnCancelListener { cancelOrDecline() }

		builder.setNegativeButton(string.cancel_project_text) { dialog, _ ->
			dialog.dismiss()
			cancelOrDecline()
		}

		// OPTION 1: Close without saving
		builder.setNeutralButton(string.close_without_saving) { dialog, _ ->
			dialog.dismiss()
			closeDialogAnswered = true
			// Committed in the same breath: there is no write to wait on, and closeProject() runs
			// below unconditionally. Only "Save and close" has a window between answered and
			// committed.
			closeCommitted = true

			for (i in 0 until editorViewModel.getOpenedFileCount()) {
				(content.editorContainer.getChildAt(i) as? CodeEditorView)?.editor?.markUnmodified()
			}

			// Activity is finishing either way (closeProject defers the finish() until the floating
			// tabs are closed, but nothing can cancel it); no need to reset confirmCloseInProgress.
			// Null out pendingCloseCallback now so a later request arriving before onDestroy()
			// actually runs (confirmCloseInProgress stays stuck true) parks its own callback instead
			// of this already-consumed one being read and invoked again by onDestroy()'s drain below.
			val onClosedNow = pendingCloseCallback
			pendingCloseCallback = null
			closeProject(saveFloatingFiles = false, onClosed = onClosedNow)
		}

		// OPTION 2: Save and close
		builder.setPositiveButton(string.save_and_close) { dialog, _ ->
			dialog.dismiss()
			closeDialogAnswered = true

			saveAllAsync(notify = false) { saveSucceeded ->
				runOnUiThread {
					confirmCloseInProgress = false

					// The save outcome is decided FIRST, before any teardown branching. It used to be
					// checked only after the two returns below, so a finish() or a config-change
					// recreate landing while the write was still in flight committed the close without
					// ever consulting it -- abandoning the user's unsaved edits with nothing shown
					// (ADFA-5067 review). A failed write must abort the close on every path.
					//
					// Answerable during teardown now that hasFilesThatFailedToSave() falls back to the
					// retained ViewModel's areFilesModified once the binding is gone; it previously
					// resolved every file through the binding and so reported "nothing failed" for a
					// whole set of unwritten buffers.
					if (!saveSucceeded || hasFilesThatFailedToSave()) {
						// closeCommitted stays false, so onDestroy's drain leaves the callback alone.
						val superseding = pendingCloseCallback
						pendingCloseCallback = null
						if (isFinishing || isDestroyed) {
							// No window for a Flashbar and no instance left to re-confirm on. The
							// buffers are still on disk unchanged and the switch simply does not
							// happen. Logged because the branch is otherwise invisible from the UI.
							log.warn(
								"Save failed during teardown (isFinishing={}, isDestroyed={}); abandoning the " +
									"confirmed close rather than switching projects over unwritten changes.",
								isFinishing,
								isDestroyed,
							)
							return@runOnUiThread
						}
						// Routed through the String overload (indefinite duration, must-dismiss) rather
						// than flashError(Int) (a ~1s auto-dismissing toast) -- a user who looks away
						// right after tapping "Save and close" must not miss that the close was aborted
						// and the activity is still open with unsaved changes.
						flashError(getString(string.save_failed))
						// A later, superseding request (e.g. a third deep link arriving while this save
						// was in flight) must not be silently dropped just because THIS attempt's save
						// failed -- give it its own confirmation, mirroring cancelOrDecline()'s handling
						// of the identical race on the cancel path.
						if (superseding !== onClosed) {
							confirmProjectClose(superseding)
						} else if (onClosed != null) {
							// Mirrors cancelOrDecline()'s identical restoration -- this failed "Save and
							// close" is itself a decline of the switch, and nothing superseded it.
							restoreIntentToStayingProject()
						}
						return@runOnUiThread
					}

					// The save landed, so the close is now genuinely committed and onDestroy may act
					// on the callback if this instance is torn down before the branches below finish.
					closeCommitted = true

					// Teardown: no window for a message, and no point re-confirming a superseded request
					// on an instance that is going away -- but the handoff below is process-wide state
					// that gets drained by owner, so it must still happen. Mirrors the contentOrNull ==
					// null branch further down, which exists for the same reason.
					// isDestroyed WITHOUT isFinishing is not teardown at all: it is a config-change
					// recreate (dark mode, locale, density -- none in EditorActivityKt's configChanges),
					// where a successor instance for this same project is already coming up. This
					// continuation survives it only because saveAllAsync now runs on the process-wide
					// appScope rather than lifecycleScope.
					//
					// This used to return here and "leave the close callback for the successor
					// instance" -- but nothing handed it over: pendingCloseCallback is a per-instance
					// field on the dying instance, the hand-off was never armed, and onNewIntent had
					// already recorded the deep-link request consumed and stripped it from the intent,
					// with the consumed mark persisted through onSaveInstanceState. The successor
					// therefore had no way to learn a switch had been confirmed, and re-tapping the
					// same URL was gated out as value-equal, so the switch was lost permanently and
					// silently (ADFA-5067 review). Arm and drain it here instead. The successor may
					// flash up on the old project for a moment before the new one replaces it; that is
					// a strictly better outcome than dropping a switch the user explicitly confirmed.
					if (!isFinishing && isDestroyed) {
						log.info(
							"Save completed during a config-change recreate; performing the confirmed close " +
								"here rather than leaving it for a successor that cannot recover it.",
						)
						val onClosedDuringRecreate = pendingCloseCallback
						pendingCloseCallback = null
						onClosedDuringRecreate?.invoke()
						// onDestroy has already run for this instance (isDestroyed), so its own
						// ownership-keyed drain is behind us and nothing else will fire this.
						drainPendingDeepLinkOpen()
						return@runOnUiThread
					}
					if (isFinishing) {
						val onClosedDuringTeardown = pendingCloseCallback
						pendingCloseCallback = null
						// Logged, not silent: this branch is the one that used to lose the switch, and it
						// is invisible from the UI -- the only symptom was a project that never opened.
						log.info(
							"Save completed during teardown (isFinishing={}, isDestroyed={}); running the close callback anyway: {}.",
							isFinishing,
							isDestroyed,
							onClosedDuringTeardown != null,
						)
						onClosedDuringTeardown?.invoke()
						// isDestroyed too: onDestroy has already run and drained, so nothing else will.
						// While it has not, onDestroy's own drain is still ahead of us and doing it here
						// would fire the handoff twice.
						if (isDestroyed) drainPendingDeepLinkOpen()
						return@runOnUiThread
					}
					recentProjectsViewModel.updateProjectModifiedDate(
						editorViewModel.getProjectName(),
					)
					// Captured then nulled before use, mirroring the neutral-button handler above --
					// otherwise onDestroy()'s own unconditional pendingCloseCallback?.invoke() would fire
					// this same callback a second time.
					val onClosedNow = pendingCloseCallback
					pendingCloseCallback = null
					// contentOrNull can already be null here if the binding was torn down while the
					// save was in flight -- closeProject's performCloseAllFiles would NPE on the view
					// manipulation it does, but onClosedNow (e.g. arming a pending deep-link project
					// switch) has no such dependency and must still run, or a confirmed close silently
					// drops it.
					if (contentOrNull != null) {
						closeProject(saveFloatingFiles = true, onClosed = onClosedNow)
					} else {
						onClosedNow?.invoke()
						// contentOrNull also goes null via isDestroying, which onPause() sets from
						// isFinishing -- well before onDestroy() actually runs -- so it is NOT reliable
						// proof onDestroy()'s one-shot drain already happened. Only isDestroyed (the real
						// Activity flag, true only once onDestroy() has actually been called) means that.
						// If onDestroy() hasn't run yet, it still will (isFinishing guarantees it
						// eventually does) and will drain whatever pendingCloseCallback just armed itself
						// -- draining it here instead would risk redelivering the new PROJECT_PATH to
						// this still-alive singleTask instance via onNewIntent rather than a genuinely new
						// instance, the exact race onDestroy()'s deferred design exists to avoid.
						if (isDestroyed) {
							drainPendingDeepLinkOpen()
						}
					}
				}
			}
		}

		activeProjectCloseDialog = builder.show()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)

		val deepLinkRequest =
			IntentCompat.getParcelableExtra(intent, DeepLinkRequest.EXTRA_KEY, DeepLinkRequest::class.java)

		// Only true for an intent that ISN'T itself requesting a switch to a genuinely *different*
		// project -- e.g. some other explicit re-launch of this activity, or a deep link/PROJECT_PATH
		// intent that re-targets the project already loading. Gating the carry-forward below on this
		// prevents a still-loading project's own stale file request from getting attached to an
		// unrelated switch to a different project, while still preserving it when the incoming intent
		// turns out to be for the SAME project: a same-project deep link with no file target of its
		// own (or a bare Recents re-tap) would otherwise silently lose the original cold-open's still-
		// pending request, since neither switchToProject's nor handlePlainProjectSwitch's same-project
		// branch reads the carried-forward extra itself -- they only apply whatever fileRequest THIS
		// intent carries, which is often none. Comparing the deep link's project name against the
		// currently-loading project's directory name (mirroring BaseEditorActivity.onCreate's own
		// deepLinkTargetsAnotherProject check) is a synchronous, disk-free way to tell same from
		// different without waiting on the deep-link path's own async resolve.
		// EditorIntentExtras.EXTRA_PREVIOUS_PROJECT_PATH, when present, is what IProjectManager.projectDirPath held before
		// MainActivity.openProject's bookkeeping call overwrote it to the NEW path -- by the time this
		// intent arrives here, the global itself already reads as the new path regardless of whether
		// this is actually a switch, so re-reading it for the comparison below would never detect one.
		val previousProjectPath =
			intent.getStringExtra(EditorIntentExtras.EXTRA_PREVIOUS_PROJECT_PATH) ?: IProjectManager.getInstance().projectDirPath
		val isProjectSwitchIntent =
			(
				deepLinkRequest != null &&
					!isDeepLinkTargetOfOpenProject(
						IProjectManager.getInstance().projectDirPath,
						deepLinkRequest.projectName,
						projectsRoot(),
					)
			) ||
				intent.getStringExtra(EditorIntentExtras.EXTRA_PROJECT_PATH)?.let { it != previousProjectPath } == true

		// Preserve a not-yet-applied file-navigation request from the previous intent -- postProjectInit
		// reads it lazily once a sync completes, and setIntent() below would otherwise silently drop it
		// if this onNewIntent call is for something unrelated to that pending request.
		if (!isProjectSwitchIntent && !intent.hasExtra(PendingFileRequest.EXTRA_KEY)) {
			IntentCompat
				.getParcelableExtra(getIntent(), PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
				?.let { armPendingFileRequest(intent, it) }
		}
		// The reverse case: this IS a switch to a genuinely different project, so the carry-forward
		// above is skipped and the old intent's own still-pending file request (for the project
		// that's actually staying open if this switch gets cancelled/declined) would otherwise be
		// lost the moment setIntent() below replaces it. restoreIntentToStayingProject() puts it back
		// if that turns out to be what happens.
		// Guarded on capturedPendingFileRequestBeforeSwitch so a SECOND overlapping switch intent,
		// arriving before the first is resolved/declined, doesn't re-capture from getIntent() -- by
		// then holding the first switch's own intent, not the original staying project's -- and
		// clobber the real value with whatever (usually nothing) that intermediate intent carries.
		if (isProjectSwitchIntent && !capturedPendingFileRequestBeforeSwitch) {
			pendingFileRequestBeforeSwitch =
				IntentCompat.getParcelableExtra(getIntent(), PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
			capturedPendingFileRequestBeforeSwitch = true
			stayingProjectPathBeforeSwitch = previousProjectPath
		}
		setIntent(intent)

		val request = deepLinkRequest
		if (request == null) {
			// Not a deep link -- a plain project-switch intent from MainActivity.openProject (Recents,
			// Clone, Template creation) redelivered here via onNewIntent because this singleTask
			// instance is already alive for a different project. Without this, the user taps a
			// different project elsewhere in the app and nothing visibly happens.
			handlePlainProjectSwitch(intent)
			return
		}

		// This is the request's only chance to be consumed: whether it's applied immediately,
		// deferred via pendingDeepLinkOpen, or dropped because the user cancels the close-project
		// dialog below, it must not linger on the intent setIntent() just stored. Android redelivers
		// that same intent verbatim to onCreate() if this process dies and gets recreated later, and
		// BaseEditorActivity.onCreate() would then wrongly compare a live, unrelated project against
		// this stale request's projectName and bounce the user out of it. The removeExtra only
		// scrubs this process's Intent object, not the parceled copy that post-process-death
		// redelivery is made from -- consumedDeepLinkRequests (persisted via onSaveInstanceState) is
		// what makes the consumption stick there, gating onCreate's read.
		consumedDeepLinkRequests.add(request)
		intent.removeExtra(DeepLinkRequest.EXTRA_KEY)

		// Tracked so a second deep link delivered moments later doesn't have its resolve complete
		// out of order with this one -- mirrors MainActivity.latestDeepLinkRequest's identical race.
		latestDeepLinkRequest = request

		lifecycleScope.launch(Dispatchers.IO) {
			val lookup = resolveDeepLinkProject(projectsRoot(), request.projectName)
			if (lookup !is DeepLinkProjectLookup.Found) {
				// Mirrors MainActivity's identical branch. Without it this path emitted RECEIVED and
				// then nothing, which reads exactly like the silent drop-off the paired events exist
				// to expose -- a missing instrument masquerading as the bug it was added to find.
				analyticsManager.trackDeepLink(
					DeepLinkMetric(
						request.depth(),
						if (lookup is DeepLinkProjectLookup.NotFound) {
							DeepLinkOutcome.PROJECT_NOT_FOUND
						} else {
							DeepLinkOutcome.PROJECT_UNVERIFIABLE
						},
						request.projectName,
					),
				)
				// No such project, so the switch this intent announced is never going to happen. Without
				// this the capture above is stranded: setIntent() has already dropped the staying
				// project's pending file request, nothing puts it back, and
				// capturedPendingFileRequestBeforeSwitch stays true for the life of the instance -- so
				// the next genuine switch skips its own capture and a later decline restores this stale
				// one instead. The two lifecycle early-returns below deliberately don't do this: a
				// finishing instance has nothing to restore into, and a superseded request must leave
				// the capture alone for the newer switch that is still relying on it.
				withContext(Dispatchers.Main) {
					// Two further conditions, both matching the sibling call sites.
					//
					// capturedPendingFileRequestBeforeSwitch: only restore what this path actually
					// captured. With nothing captured, restoreIntentToStayingProject's `restore == null`
					// arm runs intent.removeExtra(PendingFileRequest.EXTRA_KEY) and deletes a
					// carried-forward request belonging to the staying project -- the corruption the
					// other two call sites' `else if (onClosed != null)` guard exists to avoid.
					//
					// latestDeepLinkRequest === request: a slow, failing link must not clear a capture
					// that a newer link's own decline path is still relying on.
					// The capture check now lives inside restoreIntentToStayingProject; what stays here is
					// the supersession check, which is specific to this async path: a slow, failing link
					// must not clear a capture a newer link's decline still depends on.
					if (!isFinishing && !isDestroyed && latestDeepLinkRequest === request) {
						restoreIntentToStayingProject()
					}
				}
				return@launch
			}
			withContext(Dispatchers.Main) {
				// The activity may have started finishing while resolveDeepLinkProject was still
				// scanning disk -- lifecycleScope only cancels at ON_DESTROY, not the moment isFinishing
				// first flips true, so this continuation can otherwise still run and try to show the
				// confirm-close dialog on a dying window.
				if (isFinishing || isDestroyed) return@withContext
				// A newer deep link's onNewIntent call already superseded this one -- switching to
				// this stale target now would undo the newer request the user actually tapped.
				if (latestDeepLinkRequest !== request) return@withContext
				switchToProject(lookup.projectDir.absolutePath, request.fileRequest)
			}
		}
	}

	override fun postProjectInit(
		isSuccessful: Boolean,
		failure: TaskExecutionResult.Failure?,
	) {
		super.postProjectInit(isSuccessful, failure)

		// Covers requirement #1 (cold open + file) and the tail of requirement #3 (a fresh
		// EditorActivityKt instance always runs the normal init pipeline, whether started by
		// MainActivity.openProject or by this activity's own onDestroy() hand-off).
		//
		// Drained regardless of outcome, not just on success -- otherwise a failed sync leaves the
		// extra armed, and it fires later on the next unrelated *successful* sync/variant switch,
		// silently yanking the editor back to this stale request instead of never reapplying. (Two
		// init failures return before this is ever called and drain at their own early returns
		// instead -- see ProjectHandlerActivity.initializeProject.) The drain also records the
		// request in consumedFileRequests, which is what makes it stick across process death: a
		// recreate is handed the parceled intent with the extra still on it, and without the durable
		// marker the restored editor would be yanked back to this file/line with no user action.
		// drainPendingFileRequest returns null for exactly that redelivered-but-already-consumed case.
		val request = drainPendingFileRequest() ?: return
		if (!isSuccessful) return
		applyDeepLinkFileRequest(request)
	}

	// Handles a plain project-switch intent from MainActivity.openProject (Recents, Clone, or
	// Template creation) redelivered here via onNewIntent because this singleTask instance is
	// already alive -- mirrors the deep-link "different project" handling in onNewIntent above
	// (same no-op-if-already-open check, same confirm-close-then-reopen handoff), just without a
	// project name to resolve first since the caller already supplies an absolute path directly.
	private fun handlePlainProjectSwitch(intent: Intent) {
		// Deliberately no isFinishing/isDestroyed early-return here (unlike the deep-link path): this
		// instance may already be finishing because it just armed pendingDeepLinkOpen for an earlier
		// request and called finish() (switchToProject's isBlank() branch), awaiting its own
		// onDestroy(). Dropping this request outright would be strictly worse than letting it
		// supersede the earlier one -- MainActivity.openProject already synchronously recorded THIS
		// project as opened everywhere (ProjectManagerImpl, lastOpenedProject, Recents, analytics)
		// before redelivering this intent, so silently ignoring it here would leave every persisted
		// "last opened project" record pointing at a project the app never actually opens. Letting the
		// later request win (matching pendingCloseCallback's/askProjectOpenPermission's same
		// last-request-wins pattern elsewhere in this file) keeps behavior consistent with bookkeeping.
		val newProjectPath = intent.getStringExtra(EditorIntentExtras.EXTRA_PROJECT_PATH)?.takeIf { it.isNotBlank() } ?: return
		val fileRequest =
			IntentCompat.getParcelableExtra(intent, PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
		// No unconditional drain here (unlike this method's earlier version): switchToProject's own
		// same-project branch now drains this only once the request is actually applied or
		// intentionally dropped, so a request arriving mid-sync stays armed for postProjectInit's
		// deferred retry instead of being silently lost. The other branches (isFinishing, blank path,
		// different project) don't read the intent's own copy at all -- they thread fileRequest
		// through DeepLinkOpenRequest to a brand-new intent instead.

		// See onNewIntent's identical read: MainActivity.openProject's bookkeeping call already
		// overwrote the live global to newProjectPath before this intent arrived.
		val previousProjectPath =
			intent.getStringExtra(EditorIntentExtras.EXTRA_PREVIOUS_PROJECT_PATH) ?: IProjectManager.getInstance().projectDirPath
		// bookkeepingAlreadyRecorded: this intent came from MainActivity.openProject, which recorded
		// the open before sending it.
		switchToProject(newProjectPath, fileRequest, previousProjectPath, bookkeepingAlreadyRecorded = true)
	}

	/**
	 * Shared three-way dispatch for switching this singleTask instance to [newProjectPath]: no
	 * project loaded yet, the same project already open, or a different project requiring the
	 * confirm-close-then-reopen handoff. Used by both the deep-link path (onNewIntent, once the
	 * project name is resolved to a path) and the plain project-switch path ([handlePlainProjectSwitch],
	 * which already has an absolute path from its caller) -- previously duplicated in both places.
	 *
	 * [previousProjectPath] defaults to the live [IProjectManager] global, which is accurate for the
	 * deep-link caller (nothing pre-mutates it before onNewIntent runs there); the plain-switch caller
	 * passes its own pre-mutation snapshot instead, since by the time its intent arrives,
	 * MainActivity.openProject's bookkeeping has already overwritten that global to [newProjectPath].
	 */
	private fun switchToProject(
		newProjectPath: String,
		fileRequest: PendingFileRequest?,
		previousProjectPath: String = IProjectManager.getInstance().projectDirPath,
		bookkeepingAlreadyRecorded: Boolean = false,
	) {
		val currentProjectPath = previousProjectPath
		when {
			// This instance is already finishing (e.g. it just armed pendingDeepLinkOpen for an
			// earlier switch and called finish() below, awaiting its own onDestroy()) -- comparing
			// newProjectPath against currentProjectPath below would be comparing against
			// ProjectManagerImpl's process-wide path, which a *different*, unrelated instance's
			// MainActivity.openProject() can overwrite in the meantime, making this look like a
			// same-project no-op when it isn't. Superseding the earlier pending open (last request
			// wins, matching handlePlainProjectSwitch's own reasoning) is unconditionally correct
			// here since this instance can't do anything else with a new request anyway.
			isFinishing -> {
				pendingDeepLinkOpen.arm(
					handoffOwner,
					DeepLinkOpenRequest(
						newProjectPath,
						fileRequest,
						bookkeepingAlreadyRecorded,
						previousProjectPath,
					),
				)
			}

			// Either no project has actually finished initializing in this instance yet (e.g. it was
			// recreated after process death without a PROJECT_PATH extra), or contentOrNull is
			// already null (binding torn down) -- either way, confirmProjectClose below would
			// silently no-op, dropping the request with no error shown. Route through the same
			// onDestroy()-deferred handoff used for a confirmed project switch instead of showing (or
			// trying to show) a close dialog that can't work either way.
			currentProjectPath.isBlank() || contentOrNull == null -> {
				pendingDeepLinkOpen.arm(
					handoffOwner,
					DeepLinkOpenRequest(
						newProjectPath,
						fileRequest,
						bookkeepingAlreadyRecorded,
						previousProjectPath,
					),
				)
				finish()
			}

			// projectDirPath is set as soon as a project starts opening -- unlike workspace, which
			// stays null for the whole duration of a Gradle sync -- so this correctly matches the
			// "already in this project" case even mid-sync, instead of falling through to the
			// disruptive close-and-reopen confirmation below for a no-op.
			newProjectPath == currentProjectPath -> {
				// Gates both applying now and draining the intent's own copy below: a request
				// arriving while the project is still syncing (workspace == null) must stay armed for
				// postProjectInit's deferred retry once that sync completes, or it's lost for good --
				// applyDeepLinkFileRequest resolves against files a still-in-progress sync may not
				// have settled yet.
				val projectReady = IProjectManager.getInstance().workspace != null
				if (confirmCloseInProgress) {
					// A close-confirmation dialog for a *different* project switch is already
					// showing -- navigating underneath it now would just get silently discarded if
					// the user goes on to confirm that close.
					flashError(getString(string.msg_project_close_in_progress))
					// Drop only THIS request's own copy of the extra (the plain-switch path arms it
					// on the intent before this runs). The extra can instead be an older,
					// still-unapplied request for this staying project -- armed by the mid-sync
					// branch below on an earlier call -- which has nothing to do with the in-flight
					// close and which postProjectInit must still apply if the user cancels it;
					// draining unconditionally here destroyed that older request along with this one.
					val armedRequest =
						IntentCompat.getParcelableExtra(intent, PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
					if (fileRequest != null && fileRequest == armedRequest) {
						drainPendingFileRequest()
					}
				} else if (projectReady) {
					fileRequest?.let {
						applyDeepLinkFileRequest(it)
						// This request supersedes whatever onNewIntent's carry-forward guard just
						// re-armed onto the intent from the PREVIOUS, still-unconsumed request --
						// leaving it in place would have postProjectInit silently jump back to that
						// stale target once the current sync completes, discarding this newer
						// navigation.
						drainPendingFileRequest()
					}
				} else {
					// Mid-sync: arm the request on the intent so postProjectInit's deferred retry
					// finds it once the sync completes -- this branch is the "must stay armed"
					// case the projectReady comment above describes, and without the put the
					// request dies with this call while onNewIntent's carry-forward has already
					// re-armed the PREVIOUS, still-unconsumed request, sending the editor to that
					// stale target instead (ADFA-5067 review). The arm also supersedes that
					// carried-forward value, so this branch needs no drain.
					fileRequest?.let { armPendingFileRequest(intent, it) }
				}
			}

			else -> {
				// A different project is open. Reuse the existing, unmodified confirm-close dialog;
				// only record the pending open if the user actually confirms -- see onDestroy() for
				// why the reopen itself waits until this instance is torn down.
				confirmProjectClose {
					pendingDeepLinkOpen.arm(
						handoffOwner,
						DeepLinkOpenRequest(
							newProjectPath,
							fileRequest,
							bookkeepingAlreadyRecorded,
							previousProjectPath,
						),
					)
				}
			}
		}
	}

	/**
	 * Applies a deep-link file/line/column request to the *currently open* project. [request]'s
	 * file path is attacker-controllable URL input, so it's resolved through
	 * [resolveWithinDirectory] rather than a bare [File] constructor -- see that function's docs for
	 * why a lexical `..` check alone isn't enough.
	 *
	 * [resolveWithinDirectory]'s ancestor-symlink walk and the [File.isFile] check both hit disk, so
	 * -- like [openFile]'s own image check -- this runs off [Dispatchers.IO] rather than blocking the
	 * main thread the two call sites (`onNewIntent`, [postProjectInit]) invoke this from.
	 */
	private fun applyDeepLinkFileRequest(request: PendingFileRequest) {
		lifecycleScope.launch(Dispatchers.IO) {
			val projectDir = File(IProjectManager.getInstance().projectDirPath)
			val file =
				try {
					resolveWithinDirectory(projectDir, request.filePath)?.takeIf { it.isFile }
				} catch (e: CancellationException) {
					throw e
				} catch (e: SecurityException) {
					// resolveWithinDirectory's toRealPath()/Files.exists() walk and the chained
					// File.isFile() check both hit disk -- resolveDeepLinkProject already treats this
					// as a real risk for the same kind of I/O one call away.
					log.error("Failed to resolve deep-link file request for {}", request.filePath, e)
					withContext(Dispatchers.Main) {
						if (!isFinishing && !isDestroyed) {
							flashError(getString(string.msg_deeplink_scan_failed))
						}
					}
					return@launch
				}

			withContext(Dispatchers.Main) {
				// The activity may have started finishing while resolveWithinDirectory was still
				// hitting disk -- lifecycleScope only cancels at ON_DESTROY, not the moment isFinishing
				// first flips true, so this continuation can otherwise still run and touch a dying
				// window. Same race onNewIntent already guards against.
				if (isFinishing || isDestroyed) return@withContext
				if (file == null) {
					flashError(getString(string.msg_deeplink_file_not_found, request.filePath))
					return@withContext
				}

				// URL line/column are 1-based; internal Position is 0-based.
				val (line, lineInvalidRaw) = zeroBasedOrInvalid(request.lineRaw)
				val (column, columnInvalidRaw) = zeroBasedOrInvalid(request.columnRaw)

				// A dangling keyword (a trailing line/column segment with no value after it) is
				// reported as raw = "" -- show a readable placeholder instead of literal empty quotes.
				fun shown(raw: String) = raw.ifEmpty { getString(string.msg_deeplink_no_value) }
				// At most one Flashbar here -- a malformed URL can have both line and column invalid
				// at once, and showing both would stack two indefinite-duration bars instead of one.
				when {
					lineInvalidRaw != null -> flashError(getString(string.msg_deeplink_invalid_line, shown(lineInvalidRaw)))
					columnInvalidRaw != null -> flashError(getString(string.msg_deeplink_invalid_column, shown(columnInvalidRaw)))
				}

				// Range.pointRange, not Range(pos, pos): the factory already exists for exactly this, and
				// it hands back two distinct Positions -- passing one instance as both ends gives
				// EditorFeatures.validateRange a Range whose clamps move each other.
				openFileAndSelect(file, Range.pointRange(Position(line, column)))
			}
		}
	}

	/**
	 * Converts a 1-based deep-link line/column value to 0-based, paired with the raw value if it
	 * was present but invalid (fails [String.toIntOrNull] or non-positive) -- a `null` [raw]
	 * (segment absent from the URL) is never reported, only a present-but-invalid one. See
	 * [PendingFileRequest]'s docs for why those two cases are distinguished upstream.
	 */
	private fun zeroBasedOrInvalid(raw: String?): Pair<Int, String?> {
		raw ?: return 0 to null
		val parsed = raw.toIntOrNull()
		return if (parsed == null || parsed <= 0) 0 to raw else (parsed - 1) to null
	}
}
