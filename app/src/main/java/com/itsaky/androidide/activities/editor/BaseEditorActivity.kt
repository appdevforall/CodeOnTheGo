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

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.LeadingMarginSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.annotation.GravityInt
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.Tab
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.R.string
import com.itsaky.androidide.actions.build.DebugAction
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.projectsRoot
import com.itsaky.androidide.adapters.DiagnosticsAdapter
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.databinding.LayoutDiagnosticInfoBinding
import com.itsaky.androidide.deeplink.ConsumedRequests
import com.itsaky.androidide.events.InstallationEvent
import com.itsaky.androidide.fragments.debug.DebuggerFragment
import com.itsaky.androidide.fragments.output.ShareableOutputFragment
import com.itsaky.androidide.fragments.sidebar.FileTreeFragment
import com.itsaky.androidide.handlers.EditorActivityLifecyclerObserver
import com.itsaky.androidide.handlers.LspHandler.registerLanguageServers
import com.itsaky.androidide.handlers.SnippetHandler.loadPluginSnippets
import com.itsaky.androidide.handlers.SnippetHandler.loadUserSnippets
import com.itsaky.androidide.handlers.SnippetHandler.refreshPluginSnippets
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.interfaces.DiagnosticClickListener
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.models.DeepLinkRequest
import com.itsaky.androidide.models.DiagnosticGroup
import com.itsaky.androidide.models.EditorIntentExtras
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.PendingFileRequest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.plugins.extensions.FileTabMenuItem
import com.itsaky.androidide.plugins.manager.ui.PluginEditorTabManager
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.services.debug.DebuggerService
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.tasks.mainThreadHandler
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.ui.ContentTranslatingDrawerLayout
import com.itsaky.androidide.ui.MetricsCarouselController
import com.itsaky.androidide.ui.SwipeRevealLayout
import com.itsaky.androidide.uidesigner.UIDesignerActivity
import com.itsaky.androidide.utils.ActionMenuUtils.showPopupWindow
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.FileUtils
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.InstallationResultHandler.onResult
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.StringsInjectionException
import com.itsaky.androidide.utils.StringsXmlInjector
import com.itsaky.androidide.utils.applyBottomSheetAnchorForOrientation
import com.itsaky.androidide.utils.applyImmersiveModeInsets
import com.itsaky.androidide.utils.applyResponsiveAppBarInsets
import com.itsaky.androidide.utils.applyRootSystemInsetsAsPadding
import com.itsaky.androidide.utils.dpToPx
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashMessage
import com.itsaky.androidide.utils.getOrStoreInitialPadding
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.isDeepLinkTargetOfOpenProject
import com.itsaky.androidide.viewmodel.ApkInstallationViewModel
import com.itsaky.androidide.viewmodel.AppLogsCoordinator
import com.itsaky.androidide.viewmodel.AppLogsViewModel
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import com.itsaky.androidide.viewmodel.EditorViewModel
import com.itsaky.androidide.viewmodel.FileManagerViewModel
import com.itsaky.androidide.viewmodel.FileOpResult
import com.itsaky.androidide.viewmodel.MetricsViewModel
import com.itsaky.androidide.viewmodel.RecentProjectsViewModel
import com.itsaky.androidide.viewmodel.WADBConnectionViewModel
import com.itsaky.androidide.xml.resources.ResourceTableRegistry
import com.itsaky.androidide.xml.versions.ApiVersionsRegistry
import com.itsaky.androidide.xml.widgets.WidgetTableRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Base class for EditorActivity which handles most of the view related things.
 *
 * @author Akash Yadav
 */
@Suppress("MemberVisibilityCanBePrivate")
abstract class BaseEditorActivity :
	EdgeToEdgeIDEActivity(),
	TabLayout.OnTabSelectedListener,
	DiagnosticClickListener {
	protected var mLifecycleObserver: EditorActivityLifecyclerObserver? = null
	protected var diagnosticInfoBinding: LayoutDiagnosticInfoBinding? = null
	protected var filesTreeFragment: FileTreeFragment? = null
	protected var editorBottomSheet: BottomSheetBehavior<out View?>? = null
	private var drawerToggle: ActionBarDrawerToggle? = null
	private var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback? = null
	private val metricsViewModel by viewModels<MetricsViewModel>()

	/**
	 * Sample history lives in [MetricsViewModel] so it survives configuration changes and activity
	 * recreation rather than depending on this activity's configChanges declaration (ADFA-5486).
	 */
	protected val memoryUsageWatcher get() = metricsViewModel.memoryUsageWatcher

	protected val networkUsageWatcher get() = metricsViewModel.networkUsageWatcher

	private val metricsCarousel by lazy {
		MetricsCarouselController(
			memoryUsageWatcher = memoryUsageWatcher,
			networkUsageWatcher = networkUsageWatcher,
			lineColorFor = ::getMemUsageLineColorFor,
		)
	}

	private val fileManagerViewModel by viewModels<FileManagerViewModel>()
	private var feedbackButtonManager: FeedbackButtonManager? = null
	private var fullscreenManager: FullscreenManager? = null
	private val topEdgeThreshold by lazy { dpToPx(TOP_EDGE_SWIPE_THRESHOLD_DP) }

	var isDestroying = false
		protected set

	/**
	 * Editor activity's [CoroutineScope] for executing tasks in the background.
	 */
	protected val editorActivityScope = CoroutineScope(Dispatchers.Default)

	var uiDesignerResultLauncher: ActivityResultLauncher<Intent>? = null
	val editorViewModel by viewModels<EditorViewModel>()

	val recentProjectsViewModel by viewModels<RecentProjectsViewModel>()
	val debuggerViewModel by viewModels<DebuggerViewModel>()
	val bottomSheetViewModel by viewModels<BottomSheetViewModel>()
	val apkInstallationViewModel by viewModels<ApkInstallationViewModel>()
	val wadbConnectionViewModel by viewModels<WADBConnectionViewModel>()

	val appLogsViewModel by viewModels<AppLogsViewModel>()
	var appLogsCoordinator: AppLogsCoordinator? = null

	// Mirrors EditorHandlerActivity's/ProjectHandlerActivity's own same-named, independently-tracked
	// flags: set only once onCreate reaches its end without bailing out early (the "no matching
	// project" doomed-duplicate-instance branch above returns before this runs). preDestroy() checks
	// it before touching the process-wide singletons this onCreate registers this instance with
	// (BuildOutputProvider, the plugin snippet-refresh listener) -- a doomed instance never actually
	// registered as their owner, so clearing them on its teardown would wipe out whatever a
	// genuinely live sibling instance set up instead.
	private var didCompleteLiveOnCreate = false

	// The editor-side counterpart of MainActivity.consumedDeepLinkRequests, for the two deep-link
	// extras this activity consumes from its intent (DeepLinkRequest in onCreate/onNewIntent,
	// PendingFileRequest in postProjectInit): intent.removeExtra only mutates this process's Intent
	// object, so after process death the system re-creates this activity from the *parceled* intent
	// with the extras still on it, and the drained request would fire again with no user action --
	// yanking the editor back to a file/line the user navigated away from before the kill. Persisted
	// via onSaveInstanceState (restored at the top of onCreate) so consumption survives exactly the
	// recreate paths removeExtra cannot cover.
	protected val consumedDeepLinkRequests = ConsumedRequests<DeepLinkRequest>()
	protected val consumedFileRequests = ConsumedRequests<PendingFileRequest>()

	/**
	 * Arms [request] on [target] for [postProjectInit]'s deferred read, un-marking it as consumed
	 * first: a deliberately re-armed request (e.g. the same file/line navigation requested a second
	 * time, parked mid-sync) must not be skipped by the consumed-check just because an equal-by-value
	 * request was applied earlier. Every putExtra of this key onto a live activity's own intent goes
	 * through here so arming and the consumed bookkeeping cannot drift apart.
	 */
	protected fun armPendingFileRequest(
		target: Intent,
		request: PendingFileRequest,
	) {
		consumedFileRequests.remove(request)
		target.putExtra(PendingFileRequest.EXTRA_KEY, request)
	}

	/**
	 * Removes the pending file request from this activity's intent and records it as consumed (see
	 * [consumedFileRequests] for why removal alone is not durable). Returns the request when it was
	 * still pending, or `null` when there was none or it had already been consumed -- i.e. the intent
	 * carrying it is a post-process-death redelivery, not a new navigation.
	 */
	protected fun drainPendingFileRequest(): PendingFileRequest? {
		val request =
			IntentCompat.getParcelableExtra(intent, PendingFileRequest.EXTRA_KEY, PendingFileRequest::class.java)
				?: return null
		val alreadyConsumed = request in consumedFileRequests
		consumedFileRequests.add(request)
		intent.removeExtra(PendingFileRequest.EXTRA_KEY)
		return request.takeUnless { alreadyConsumed }
	}

	@Suppress("ktlint:standard:backing-property-naming")
	internal var _binding: ActivityEditorBinding? = null
	val binding: ActivityEditorBinding
		get() =
			_binding
				?: throw IllegalStateException("Activity destroyed; binding not accessible")
	val content: ContentEditorBinding
		get() = binding.content

	override val subscribeToEvents: Boolean
		get() = true

	protected val contentOrNull: ContentEditorBinding?
		get() {
			if (_binding == null || isDestroyed || isDestroying) {
				return null
			}
			return _binding!!.content
		}

	private val onBackPressedCallback: OnBackPressedCallback =
		object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				when {
					binding.editorDrawerLayout.isDrawerOpen(GravityCompat.START) -> {
						binding.editorDrawerLayout.closeDrawer(GravityCompat.START)
					}

					bottomSheetViewModel.sheetBehaviorState != STATE_COLLAPSED -> {
						bottomSheetViewModel.setSheetState(sheetState = STATE_COLLAPSED)
					}

					binding.swipeReveal.isOpen -> {
						binding.swipeReveal.close()
					}

					else -> {
						doConfirmProjectClose()
					}
				}
			}
		}

	private val shizukuBinderReceivedListener =
		Shizuku.OnBinderReceivedListener {
			invalidateOptionsMenu()
		}

	private var isImeVisible = false
	private var contentCardRealHeight: Int? = null
	private var isDebuggerStarting = false
		@UiThread set(value) {
			field = value
			onUpdateProgressBarVisibility()
		}

	private var debuggerService: DebuggerService? = null
	private var debuggerPostConnectionAction: (suspend () -> Unit)? = null
	private val debuggerServiceConnection =
		object : ServiceConnection {
			override fun onServiceConnected(
				name: ComponentName,
				service: IBinder,
			) {
				debuggerService = (service as DebuggerService.Binder).getService()
				debuggerService!!.showOverlay()

				isDebuggerStarting = false
				activityScope.launch(Dispatchers.Main.immediate) {
					doSetStatus(getString(string.debugger_started))
					debuggerPostConnectionAction?.invoke()
				}
			}

			override fun onServiceDisconnected(name: ComponentName?) {
				debuggerService = null
				isDebuggerStarting = false
			}
		}

	private val debuggerServiceStopHandler = Handler(Looper.getMainLooper())
	private val debuggerServiceStopRunnable =
		Runnable {
			if (debuggerService != null && debuggerViewModel.connectionState.value < DebuggerConnectionState.ATTACHED) {
				unbindDebuggerService()
			}
		}

	private fun unbindDebuggerService() {
		try {
			unbindService(debuggerServiceConnection)
		} catch (e: Throwable) {
			if (e !is IllegalArgumentException) {
				log.error("Failed to stop debugger service", e)
			}
		}
	}

	private fun startDebuggerAndDo(action: suspend () -> Unit) {
		activityScope.launch(Dispatchers.Main.immediate) {
			if (debuggerService != null) {
				action()
			} else {
				debuggerPostConnectionAction = action
				ensureDebuggerServiceBound()
			}
		}
	}

	fun ensureDebuggerServiceBound() {
		if (debuggerService != null) return

		if (isDebuggerStarting) {
			log.info("Debugger service is already starting, ignoring...")
			return
		}

		isDebuggerStarting = true

		val intent =
			Intent(this, DebuggerService::class.java)
				.apply {
					val currentOrDefaultDisplay =
						ContextCompat.getDisplayOrDefault(this@BaseEditorActivity)
					putExtra(DebuggerService.EXTRA_DISPLAY_ID, currentOrDefaultDisplay.displayId)
				}

		if (bindService(intent, debuggerServiceConnection, BIND_AUTO_CREATE)) {
			postStopDebuggerServiceIfNotConnected()
			doSetStatus(getString(string.debugger_starting))
		} else {
			isDebuggerStarting = false
			log.error("Debugger service doesn't exist or the IDE is not allowed to access it.")
			doSetStatus(getString(string.debugger_starting_failed))
		}
	}

	private fun postStopDebuggerServiceIfNotConnected() {
		debuggerServiceStopHandler.removeCallbacks(debuggerServiceStopRunnable)
		debuggerServiceStopHandler.postDelayed(
			debuggerServiceStopRunnable,
			DEBUGGER_SERVICE_STOP_DELAY_MS,
		)
	}

	protected var optionsMenuInvalidator: Runnable? = null

	// Collapses the bottom sheet after its initial onboarding "peek" on first launch.
	private val bottomSheetOnboardingCollapseRunnable =
		Runnable {
			bottomSheetViewModel.setSheetState(STATE_COLLAPSED)
			app.prefManager.putBoolean(KEY_BOTTOM_SHEET_SHOWN, true)
		}

	private var gestureDetector: GestureDetector? = null
	private val flingDistanceThreshold by lazy { dpToPx(100f) }
	private val flingVelocityThreshold by lazy { dpToPx(100f) }

	private var editorAppBarInsetTop: Int = 0

	companion object {
		const val DEBUGGER_SERVICE_STOP_DELAY_MS: Long = 60 * 1000

		@JvmStatic
		protected val PROC_IDE = "IDE"

		@JvmStatic
		protected val PROC_GRADLE_TOOLING = "Gradle Tooling"

		@JvmStatic
		protected val PROC_GRADLE_DAEMON = "Gradle Daemon"

		@JvmStatic
		protected val log: Logger = LoggerFactory.getLogger(BaseEditorActivity::class.java)

		private const val OPTIONS_MENU_INVALIDATION_DELAY = 150L
		private const val TOP_EDGE_SWIPE_THRESHOLD_DP = 60f

		const val EDITOR_CONTAINER_SCALE_FACTOR = 0.87f
		const val KEY_BOTTOM_SHEET_SHOWN = "editor_bottomSheetShown"
		const val KEY_PROJECT_PATH = "saved_projectPath"
		private const val KEY_CONSUMED_DEEP_LINK_REQUESTS = "saved_consumedDeepLinkRequests"
		private const val KEY_CONSUMED_FILE_REQUESTS = "saved_consumedFileRequests"
	}

	protected abstract fun provideCurrentEditor(): CodeEditorView?

	protected abstract fun provideEditorAt(index: Int): CodeEditorView?

	internal abstract fun doOpenFile(
		file: File,
		selection: Range?,
	)

	protected abstract fun doDismissSearchProgress()

	protected abstract fun getOpenedFiles(): List<OpenedFile>

	internal abstract fun doConfirmProjectClose()

	internal abstract fun doOpenHelp()

	protected open fun preDestroy() {
		if (didCompleteLiveOnCreate) {
			BuildOutputProvider.clearBottomSheet()

			IDEApplication.getPluginManager()?.setSnippetRefreshListener(null)
		}

		Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
		if (isAtLeastR()) wadbConnectionViewModel.stop(this)

		appLogsCoordinator?.also(lifecycle::removeObserver)
		appLogsCoordinator = null

		drawerToggle?.let { binding.editorDrawerLayout.removeDrawerListener(it) }
		drawerToggle = null
		bottomSheetCallback?.let { editorBottomSheet?.removeBottomSheetCallback(it) }
		bottomSheetCallback = null

		runCatching { onBackPressedCallback.remove() }
		runCatching { debuggerServiceStopHandler.removeCallbacks(debuggerServiceStopRunnable) }
		optionsMenuInvalidator?.also { mainThreadHandler.removeCallbacks(it) }
		optionsMenuInvalidator = null
		mainThreadHandler.removeCallbacks(bottomSheetOnboardingCollapseRunnable)

		apkInstallationViewModel.destroy(this)

		feedbackButtonManager = null
		mLifecycleObserver?.let {
			runCatching { lifecycle.removeObserver(it) }
		}
		mLifecycleObserver = null

		diagnosticInfoBinding = null
		filesTreeFragment = null
		editorBottomSheet = null
		gestureDetector = null

		fullscreenManager?.destroy()
		fullscreenManager = null

		metricsCarousel.unbind()
		_binding = null

		if (isDestroying) {
			// Sampling itself is stopped by MetricsViewModel.onCleared; the history has to outlive a
			// recreation, so it must not be torn down whenever this activity goes away.
			memoryUsageWatcher.listener = null
			networkUsageWatcher.listener = null
			editorActivityScope.cancelIfActive("Activity is being destroyed")

			unbindDebuggerService()
		}
	}

	protected open fun postDestroy() {
		// didCompleteLiveOnCreate as well as isDestroying, for the same reason preDestroy above and
		// both of ProjectHandlerActivity's teardown hooks carry it -- and it matters more here, because
		// everything below is process-wide rather than per-instance. An instance whose onCreate took the
		// deep-link `deepLinkTargetsAnotherProject` bail (finish() + return) never registered any of
		// this, but it *is* finishing, so isDestroying alone let it unregister the Lookup and clear all
		// three registries out from under the live sibling that did register them.
		if (didCompleteLiveOnCreate && isDestroying) {
			Lookup.getDefault().unregisterAll()
			ApiVersionsRegistry.getInstance().clear()
			ResourceTableRegistry.getInstance().clear()
			WidgetTableRegistry.getInstance().clear()
		}
	}

	override fun bindLayout(): View {
		this._binding = ActivityEditorBinding.inflate(layoutInflater)
		this.diagnosticInfoBinding = this.content.diagnosticInfo
		return this.binding.root
	}

	override fun onApplyWindowInsets(insets: WindowInsetsCompat) {
		super.onApplyWindowInsets(insets)

		val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

		applyStandardInsets(systemBars)

		applyImmersiveModeInsets(systemBars)

		handleKeyboardInsets(imeInsets, systemBars)
	}

	private fun applyStandardInsets(systemBars: Insets) {
		val root = _binding?.root ?: return
		val initial = root.getOrStoreInitialPadding()
		root.updatePadding(bottom = initial.bottom + systemBars.bottom)
	}

	private fun applyImmersiveModeInsets(systemBars: Insets) {
		_binding?.content?.applyImmersiveModeInsets(systemBars)
	}

	private fun handleKeyboardInsets(
		imeInsets: Insets,
		systemBars: Insets,
	) {
		val isImeVisible = imeInsets.bottom > 0
		_binding?.content?.bottomSheet?.setImeVisible(isImeVisible)

		_binding?.contentCard?.apply {
			when {
				isImeVisible -> {
					contentCardRealHeight?.let { baseHeight ->
						updateLayoutParams<ViewGroup.LayoutParams> {
							val diff = (imeInsets.bottom - systemBars.bottom).coerceAtLeast(0)
							height = (baseHeight - diff).coerceAtLeast(0)
						}
					}
				}

				else -> {
					updateLayoutParams<ViewGroup.LayoutParams> {
						height = ViewGroup.LayoutParams.MATCH_PARENT
					}
					post { contentCardRealHeight = measuredHeight }
				}
			}
		}

		if (this.isImeVisible != isImeVisible) {
			this.isImeVisible = isImeVisible

			if (editorViewModel.isFullscreen) {
				// Hide the bottom sheet if in fullscreen mode, but only collapse it if keyboard
				// is open so the symbol input view is visible
				val targetState = if (isImeVisible) STATE_COLLAPSED else STATE_HIDDEN
				editorBottomSheet?.state = targetState
			}

			onSoftInputChanged()
		}
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		super.onApplySystemBarInsets(insets)
		editorAppBarInsetTop = insets.top
	}

	@Subscribe(threadMode = MAIN)
	open fun onInstallationResult(event: InstallationEvent.InstallationResultEvent) {
		val intent = event.intent
		if (isDestroying) {
			return
		}

		val packageName = onResult(this, intent) ?: return
		val isDebugging = event.intent.getBooleanExtra(DebugAction.ID, false)
		if (!isDebugging) {
			doLaunchApp(packageName)
			return
		}

		startDebuggerAndDo {
			withContext(Dispatchers.Main.immediate) {
				doLaunchApp(
					packageName = packageName,
					debug = true,
				)
			}
		}
	}

	private fun doLaunchApp(
		packageName: String,
		debug: Boolean = false,
	) {
		val context = this
		val performLaunch = {
			activityScope.launch {
				debuggerViewModel.debugeePackage = packageName
				IntentUtils.launchApp(
					context = context,
					packageName = packageName,
					debug = debug,
				)
			}
		}

		if (BuildPreferences.launchAppAfterInstall) {
			performLaunch()
			return
		}

		val builder = newMaterialDialogBuilder(this)
		builder.setTitle(string.msg_action_open_title_application)
		builder.setMessage(string.msg_action_open_application)
		builder.setPositiveButton(string.yes) { dialog, _ ->
			dialog.dismiss()
			performLaunch()
		}
		builder.setNegativeButton(string.no, null)
		builder.show()
	}

	/**
	 * Restores the project path on recreation (saved state, launch intent, or last opened
	 * project) and routes back to MainActivity if none is available, rather than crashing while
	 * building the editor UI.
	 */
	override fun onCreate(savedInstanceState: Bundle?) {
		// Restored before the deep-link reads below: a savedInstanceState means this is a recreate
		// (config change or post-process-death), and post-process-death the intent read next still
		// carries every extra this task already consumed -- see consumedDeepLinkRequests' docs.
		consumedDeepLinkRequests.restore(
			savedInstanceState?.let {
				BundleCompat.getParcelableArrayList(it, KEY_CONSUMED_DEEP_LINK_REQUESTS, DeepLinkRequest::class.java)
			},
		)
		consumedFileRequests.restore(
			savedInstanceState?.let {
				BundleCompat.getParcelableArrayList(it, KEY_CONSUMED_FILE_REQUESTS, PendingFileRequest::class.java)
			},
		)

		// DeepLinkActivity routes a deep link to this activity's class only when it believes a live
		// singleTask instance already exists to handle it via onNewIntent (see
		// ActionContextProvider.getLiveActivity()'s docs on how that check can still be stale) -- if
		// Android instead spins up a genuinely new instance, this onCreate runs and onNewIntent
		// never does, so this is EXTRA_KEY's only other reader on the editor side. An
		// already-consumed request is treated as absent: it can only be here again because a
		// post-process-death recreate redelivered the parceled intent verbatim, and acting on it
		// again would bounce the user back into a project switch they already performed (or
		// abandoned) before the kill.
		val deepLinkRequest =
			IntentCompat
				.getParcelableExtra(intent, DeepLinkRequest.EXTRA_KEY, DeepLinkRequest::class.java)
				?.takeUnless { it in consumedDeepLinkRequests }

		// The OS can recreate EditorActivity after process death without routing through
		// MainActivity, leaving the ProjectManagerImpl singleton's lateinit projectPath unset.
		// Restore it from the saved state or the launch intent; only fall back to the last opened
		// project when there's no pending deep link -- otherwise this would silently open the wrong
		// project instead of the one the link actually requested.
		val explicitProjectPath =
			savedInstanceState?.getString(KEY_PROJECT_PATH)?.takeIf { it.isNotBlank() }
				?: intent?.getStringExtra(EditorIntentExtras.EXTRA_PROJECT_PATH)?.takeIf { it.isNotBlank() }
		val restoredProjectPath =
			explicitProjectPath
				?: if (deepLinkRequest == null) {
					GeneralPreferences.lastOpenedProject
						.takeIf { it.isNotBlank() && it != GeneralPreferences.NO_OPENED_PROJECT }
				} else {
					null
				}
		if (restoredProjectPath != null) {
			ProjectManagerImpl.getInstance().projectPath = restoredProjectPath
		}
		super.onCreate(savedInstanceState)

		// If we still have no project path after every fallback, we cannot safely build the
		// editor UI (setupToolbar -> getProjectName dereferences the project path). Route the
		// user back to MainActivity instead of crashing -- forwarding a pending deep link along so
		// MainActivity can still resolve and open the requested project, instead of silently
		// dropping it here.
		//
		// A deep link also forces this even when a project path IS already loaded: DeepLinkActivity
		// routes here only when it believes a live instance already exists to handle the request via
		// onNewIntent, but that check can be stale (see ActionContextProvider.getLiveActivity()'s docs)
		// -- Android may spin up this genuinely new instance instead, which inherits whatever project
		// ProjectManagerImpl's process-wide singleton was last holding, not necessarily the one this
		// deep link actually targets. Comparing against the project directory's name (matching how
		// projects live directly under Environment.PROJECTS_DIR) catches that mismatch without an
		// extra disk scan.
		val projectDirPath = ProjectManagerImpl.getInstance().projectDirPath
		val deepLinkTargetsAnotherProject =
			deepLinkRequest != null &&
				!isDeepLinkTargetOfOpenProject(projectDirPath, deepLinkRequest.projectName, projectsRoot())
		if (projectDirPath.isBlank() || deepLinkTargetsAnotherProject) {
			log.warn("No matching project available in EditorActivity.onCreate(); returning to MainActivity")
			startActivity(
				Intent(this, MainActivity::class.java).apply {
					deepLinkRequest?.let {
						putExtra(DeepLinkRequest.EXTRA_KEY, it)
						// Marks this as a re-delivery rather than a fresh tap, so MainActivity applies its
						// consumed gate here and only here.
						putExtra(EditorIntentExtras.EXTRA_REFORWARDED_DEEP_LINK, true)
					}
					// This branch is reachable far more often now (any deepLinkTargetsAnotherProject
					// mismatch, not just a rare cold process-death recreate) -- without CLEAR_TOP, a
					// MainActivity instance already lower in the back stack (Main -> Open Project ->
					// Editor) would get a stacked duplicate instead of being reused, leaving back-press
					// landing on the stale earlier instance instead of exiting.
					addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
				},
			)
			finish()
			return
		}

		// The deep link's project already matches what's loaded (a stale liveness check spun up this
		// new instance instead of redelivering via onNewIntent) -- forward its file/line/column
		// request through the normal PendingFileRequest pipeline so postProjectInit still applies it
		// once the project finishes initializing, instead of silently dropping it here.
		deepLinkRequest?.fileRequest?.let { armPendingFileRequest(intent, it) }
		// Consumed here -- mirror EditorHandlerActivity.onNewIntent's own drain of this same extra,
		// so a launch intent redelivered verbatim after process death doesn't re-navigate to the
		// same file/line a second time. Recorded in consumedDeepLinkRequests too: the removeExtra
		// alone doesn't survive process death (see that field's docs).
		deepLinkRequest?.let {
			consumedDeepLinkRequests.add(it)
			intent.removeExtra(DeepLinkRequest.EXTRA_KEY)
		}

		editorViewModel.isBuildInProgress = false
		editorViewModel.isInitializing = false

		mLifecycleObserver = EditorActivityLifecyclerObserver()

		Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
		if (isAtLeastR() && !Shizuku.pingBinder()) {
			lifecycleScope.launch { wadbConnectionViewModel.start(this@BaseEditorActivity) }
		}

		appLogsCoordinator =
			AppLogsCoordinator(appLogsViewModel)
				.also(lifecycle::addObserver)

		this.optionsMenuInvalidator = Runnable { super.invalidateOptionsMenu() }

		loadUserSnippets()
		loadPluginSnippets()
		IDEApplication.getPluginManager()?.setSnippetRefreshListener { pluginId ->
			refreshPluginSnippets(pluginId)
		}
		registerLanguageServers()

		onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
		mLifecycleObserver?.let {
			lifecycle.addObserver(it)
		}

		setupToolbar()
		setupDrawers()
		content.tabs.addOnTabSelectedListener(this)

		setupStateObservers()
		setupFullscreenObserver()
		setupBottomSheetObserver()
		setupViews()

		fullscreenManager =
			FullscreenManager(
				contentBinding = content,
				bottomSheetBehavior = editorBottomSheet!!,
				closeDrawerAction = {
					binding.editorDrawerLayout.closeDrawer(GravityCompat.START)
				},
				onFullscreenToggleRequested = {
					editorViewModel.toggleFullscreen()
				},
			).also {
				it.bind()
				it.render(editorViewModel.isFullscreen, animate = false)
			}

		setupContainers()
		setupDiagnosticInfo()

		uiDesignerResultLauncher =
			registerForActivityResult(
				StartActivityForResult(),
				this::handleUiDesignerResult,
			)

		content.bottomSheet.binding.buildStatus.buildStatusLayout.setOnLongClickListener {
			showTooltip(tag = TooltipTag.EDITOR_BUILD_STATUS)
			true
		}

		feedbackButtonManager =
			FeedbackButtonManager(
				activity = this,
				feedbackFab = binding.fabFeedback.root,
				getLogContent = ::getLogContent,
			)
		feedbackButtonManager?.setupDraggableFab()

		setupMetricsCarousel()
		watchMemory()
		observeFileOperations()

		setupGestureDetector()

		didCompleteLiveOnCreate = true
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		window?.decorView?.let { ViewCompat.requestApplyInsets(it) }
		reapplySystemBarInsetsFromRoot()
		_binding?.content?.applyBottomSheetAnchorForOrientation(newConfig.orientation)
		fullscreenManager?.render(editorViewModel.isFullscreen, animate = false)
	}

	private fun reapplySystemBarInsetsFromRoot() {
		val root = _binding?.root ?: return
		val rootInsets = ViewCompat.getRootWindowInsets(root)
		if (rootInsets == null) {
			// Insets can be temporarily unavailable right after a configuration change.
			root.post { reapplySystemBarInsetsFromRoot() }
			return
		}

		val systemBars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars())
		applyStandardInsets(systemBars)
		applyImmersiveModeInsets(systemBars)
	}

	private fun setupToolbar() {
		// Set the project name in the title TextView
		content.root.findViewById<TextView>(R.id.title_text)?.apply {
			text = editorViewModel.getProjectName()
		}

		content.editorAppBarLayout.applyResponsiveAppBarInsets(content.editorAppbarContent)

		// Set up the drawer toggle on the title toolbar (where the hamburger menu should be)
		content.titleToolbar.apply {
			val toggle =
				object : ActionBarDrawerToggle(
					this@BaseEditorActivity,
					binding.editorDrawerLayout,
					this,
					string.cd_drawer_open,
					string.cd_drawer_close,
				) {
					override fun onDrawerOpened(drawerView: View) {
						super.onDrawerOpened(drawerView)
						// Hide the keyboard when the drawer opens.
						closeKeyboard()
						// Dismiss autocomplete and other editor windows
						provideCurrentEditor()?.editor?.ensureWindowsDismissed()
						// Reflect files changed while the drawer was closed (e.g. by a plugin).
						getFileTreeFragment()?.refreshExpandedNodes()
					}
				}

			drawerToggle = toggle
			binding.editorDrawerLayout.addDrawerListener(toggle)
			toggle.syncState()

			// Set up long click listener for tooltip on the navigation icon
			post {
				var navButton: android.widget.ImageButton? = null
				for (i in 0 until childCount) {
					val child = getChildAt(i)
					if (child is android.widget.ImageButton && child.contentDescription == navigationContentDescription) {
						navButton = child
						break
					}
				}
				navButton?.apply {
					// Remove top padding to align with TextView
					setPadding(paddingLeft, 0, paddingRight, paddingBottom)
					setOnLongClickListener {
						showTooltip(tag = TooltipTag.EDITOR_TOOLBAR_NAV_ICON)
						true
					}
				}
			}
		}
	}

	private fun onSwipeRevealDragProgress(progress: Float) {
		_binding?.apply {
			contentCard.progress = progress
			val insetsTop = systemBarInsets?.top ?: 0
			val topInset = (insetsTop * (1f - progress)).roundToInt()

			val isLandscape =
				resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

			if (isLandscape) {
				content.editorAppbarContent.updatePadding(top = topInset)
			} else {
				content.editorAppBarLayout.updatePadding(top = topInset)
			}

			metricsCarousel.pager?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = (insetsTop * progress).roundToInt()
			}
		}
	}

	private fun setupMetricsCarousel() {
		metricsCarousel.bind(binding.memUsageView)
	}

	private fun watchMemory() {
		memoryUsageWatcher.watchProcess(Process.myPid(), PROC_IDE)
		resetMemUsageChart()
	}

	/**
	 * Rebuilds the memory chart for the currently watched processes. Call after starting or stopping
	 * watching a process.
	 */
	protected fun resetMemUsageChart() {
		metricsCarousel.onWatchedProcessesChanged()
	}

	private fun getMemUsageLineColorFor(proc: MemoryUsageWatcher.ProcessMemoryInfo): Int =
		when (proc.pname) {
			PROC_IDE -> Color.BLUE
			PROC_GRADLE_TOOLING -> Color.RED
			PROC_GRADLE_DAEMON -> Color.GREEN
			else -> throw IllegalArgumentException("Unknown process: $proc")
		}

	override fun onPause() {
		super.onPause()
		// Sampling continues while backgrounded so the history has no gaps; the x axis assumes
		// evenly spaced samples and would otherwise misreport their age (ADFA-5486). Only the
		// carousel goes, so nothing updates a chart nobody is looking at.
		metricsCarousel.unbind()

		this.isDestroying = isFinishing
		getFileTreeFragment()?.saveTreeState()
	}

	override fun onResume() {
		super.onResume()
		invalidateOptionsMenu()

		val displayId = ContextCompat.getDisplayOrDefault(this).displayId
		runCatching {
			debuggerService?.maybeMoveOverlayToDisplay(displayId)
		}.onFailure { err ->
			log.warn("Unable to move debugger overlay to display {}", displayId, err)
		}

		_binding?.let { metricsCarousel.bind(it.memUsageView) }
		if (!memoryUsageWatcher.isWatching) {
			memoryUsageWatcher.startWatching()
		}
		if (!networkUsageWatcher.isWatching) {
			networkUsageWatcher.startWatching()
		}

		// Draw whatever was sampled while we were away, rather than waiting for the next tick.
		metricsCarousel.refresh()

		apkInstallationViewModel.reloadStatus(this)

		try {
			getFileTreeFragment()?.listProjectFiles()
		} catch (th: Throwable) {
			log.error("Failed to update files list", th)
			flashError(string.msg_failed_list_files)
		}

		feedbackButtonManager?.loadFabPosition()
	}

	override fun onStop() {
		super.onStop()
		checkIsDestroying()
	}

	override fun onDestroy() {
		checkIsDestroying()
		preDestroy()
		super.onDestroy()
		postDestroy()
	}

	/**
	 * The project path [onSaveInstanceState] persists, and therefore the project a recreate reopens.
	 *
	 * Open because the live [IProjectManager] global is not always the right answer: while a project
	 * switch is proposed but not yet confirmed, `MainActivity.openProject`'s bookkeeping has already
	 * moved that global to the *incoming* project, so saving it would hand the successor a project
	 * the user has not agreed to open -- against a retained ViewModel still holding the previous
	 * project's tabs and buffers. [EditorHandlerActivity] overrides this to name the project that is
	 * actually staying open (ADFA-5067 review).
	 */
	protected open val projectPathForInstanceState: String
		get() = IProjectManager.getInstance().projectDirPath

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString(KEY_PROJECT_PATH, projectPathForInstanceState)
		// See consumedDeepLinkRequests' docs: a post-process-death recreate is handed the parceled
		// intent with already-drained extras still on it, and these are what stop them re-firing.
		outState.putParcelableArrayList(KEY_CONSUMED_DEEP_LINK_REQUESTS, consumedDeepLinkRequests.toSavedList())
		outState.putParcelableArrayList(KEY_CONSUMED_FILE_REQUESTS, consumedFileRequests.toSavedList())
		super.onSaveInstanceState(outState)
	}

	override fun invalidateOptionsMenu() {
		val mainHandler = mainThreadHandler
		optionsMenuInvalidator?.also {
			mainHandler.removeCallbacks(it)
			mainHandler.postDelayed(it, OPTIONS_MENU_INVALIDATION_DELAY)
		}
	}

	override fun onTabSelected(tab: Tab) {
		val position = tab.position

		content.editorContainer.displayedChild = position

		if (this is EditorHandlerActivity && isPluginTab(position)) {
			val pluginTabId = getPluginTabId(position)
			if (pluginTabId != null) {
				val tabManager = PluginEditorTabManager.getInstance()
				tabManager.onTabSelected(pluginTabId)
				invalidateOptionsMenu()
				return
			}
		}

		val fileIndex =
			if (this is EditorHandlerActivity) {
				getFileIndexForTabPosition(position)
			} else {
				position
			}

		if (fileIndex == -1) {
			invalidateOptionsMenu()
			return
		}

		editorViewModel.displayedFileIndex = fileIndex

		val editorView =
			if (this is EditorHandlerActivity) {
				provideEditorAt(fileIndex)
			} else {
				provideEditorAt(position)
			}

		if (editorView == null) {
			invalidateOptionsMenu()
			return
		}

		editorView.onEditorSelected()
		editorViewModel.setCurrentFile(fileIndex, editorView.file)
		refreshSymbolInput(editorView)
		invalidateOptionsMenu()
	}

	override fun onTabUnselected(tab: Tab) {}

	override fun onTabReselected(tab: Tab) {
		val position = tab.position
		if (this is EditorHandlerActivity && isPluginTab(position)) {
			(this as EditorHandlerActivity).showPluginTabPopup(tab)
			return
		}

		val pluginMenuItems =
			if (this is EditorHandlerActivity) {
				val self = this
				val fileIndex = getFileIndexForTabPosition(position)
				if (fileIndex >= 0) {
					val file = editorViewModel.getOpenedFile(fileIndex)
					val pluginItems =
						IDEApplication.getPluginManager()?.getFileTabMenuItems(file) ?: emptyList()
					listOf(
						FileTabMenuItem(
							id = "ide.floating.undock",
							title = getString(R.string.undock),
							tooltipTag = TooltipTag.WINDOW_UNDOCK,
						) { self.undockFileTab(fileIndex) },
					) + pluginItems
				} else {
					emptyList()
				}
			} else {
				emptyList()
			}

		showPopupWindow(
			context = this,
			anchorView = tab.view,
			pluginMenuItems = pluginMenuItems,
		)
	}

	override fun onGroupClick(group: DiagnosticGroup?) {
		if (group?.file?.exists() == true && FileUtils.isUtf8(group.file)) {
			doOpenFile(group.file, null)
			hideBottomSheet()
		}
	}

	override fun onDiagnosticClick(
		file: File,
		diagnostic: DiagnosticItem,
	) {
		doOpenFile(file, diagnostic.range)
		hideBottomSheet()
	}

	@JvmOverloads
	open fun handleSearchResults(
		map: Map<File, List<SearchResult>>?,
		dismissProgress: Boolean = true,
	) {
		val results = map ?: emptyMap()
		editorViewModel.onSearchResultsReady(results)

		bottomSheetViewModel.setSheetState(
			sheetState = BottomSheetBehavior.STATE_HALF_EXPANDED,
			currentTab = BottomSheetViewModel.TAB_SEARCH_RESULT,
		)
		// A pending plugin-search fan-out keeps the progress indicator up until it resolves,
		// so a query the built-in text search misses does not flash a terminal empty state.
		if (dismissProgress) {
			doDismissSearchProgress()
		}
	}

	open fun setSearchResultAdapter(adapter: SearchListAdapter) {
		content.bottomSheet.setSearchResultAdapter(adapter)
	}

	open fun setDiagnosticsAdapter(adapter: DiagnosticsAdapter) {
		content.bottomSheet.setDiagnosticsAdapter(adapter)
	}

	open fun hideBottomSheet() {
		bottomSheetViewModel.setSheetState(sheetState = STATE_COLLAPSED)
	}

	private fun updateBottomSheetState(state: BottomSheetViewModel.SheetState = BottomSheetViewModel.SheetState.EMPTY) {
		when (state.sheetState) {
			BottomSheetBehavior.STATE_DRAGGING, BottomSheetBehavior.STATE_SETTLING -> return
		}
		log.debug("updateSheetState: {}", state)
		content.bottomSheet.setCurrentTab(state.currentTab)
		if (editorBottomSheet?.state != state.sheetState) {
			editorBottomSheet?.state = state.sheetState
		}
	}

	open fun handleDiagnosticsResultVisibility(errorVisible: Boolean) {
		content.bottomSheet.handleDiagnosticsResultVisibility(errorVisible)
	}

	open fun handleSearchResultVisibility(errorVisible: Boolean) {
		content.bottomSheet.handleSearchResultVisibility(errorVisible)
	}

	open fun showFirstBuildNotice() {
		newMaterialDialogBuilder(this)
			.setPositiveButton(android.R.string.ok, null)
			.setTitle(string.title_first_build)
			.setMessage(string.msg_first_build)
			.setCancelable(false)
			.create()
			.show()
	}

	open fun getFileTreeFragment(): FileTreeFragment? {
		if (filesTreeFragment?.isAdded != true) {
			filesTreeFragment = findFileTreeFragment(supportFragmentManager)
		}
		return filesTreeFragment
	}

	private fun findFileTreeFragment(manager: FragmentManager): FileTreeFragment? {
		for (fragment in manager.fragments) {
			if (fragment is FileTreeFragment) {
				return fragment
			}
			findFileTreeFragment(fragment.childFragmentManager)?.let { return it }
		}
		return null
	}

	fun doSetStatus(
		text: CharSequence,
		@GravityInt gravity: Int = Gravity.CENTER,
	) {
		editorViewModel.statusText = text
		editorViewModel.statusGravity = gravity
	}

	fun refreshSymbolInput() {
		provideCurrentEditor()?.also { refreshSymbolInput(it) }
	}

	fun refreshSymbolInput(editor: CodeEditorView) {
		content.bottomSheet.refreshSymbolInput(editor)
	}

	private fun checkIsDestroying() {
		if (!isDestroying && isFinishing) {
			isDestroying = true
		}
	}

	private fun handleUiDesignerResult(result: ActivityResult) {
		if (result.resultCode != RESULT_OK || result.data == null) {
			log.warn(
				"UI Designer returned invalid result: resultCode={}, data={}",
				result.resultCode,
				result.data,
			)
			return
		}

		val data = result.data!!
		val generatedXml = data.getStringExtra(UIDesignerActivity.RESULT_GENERATED_XML)

		if (TextUtils.isEmpty(generatedXml)) {
			log.warn("UI Designer returned blank generated XML code")
			return
		}

		editorActivityScope.launch {
			val injectionSuccess = handleStringsInjection(data)

			if (injectionSuccess) {
				withContext(Dispatchers.Main) { applyGeneratedXmlToEditor(generatedXml!!) }
			} else {
				log.warn("Aborting layout update due to string injection failure.")
			}
		}
	}

	private suspend fun handleStringsInjection(data: Intent): Boolean {
		val stringsXml = data.getStringExtra(UIDesignerActivity.EXTRA_GENERATED_STRINGS)
		val layoutFilePath = data.getStringExtra(UIDesignerActivity.EXTRA_LAYOUT_FILE_PATH)

		if (stringsXml.isNullOrBlank()) return true

		if (layoutFilePath.isNullOrBlank()) {
			log.warn("Skipping string injection: generated strings present but layout file path is missing.")
			return false
		}

		val result = StringsXmlInjector.inject(layoutFilePath, stringsXml)

		result.onFailure { error ->
			log.error("String injection failed", error)
			withContext(Dispatchers.Main) {
				val message =
					when (error) {
						is StringsInjectionException -> getString(error.messageRes)
						else -> getString(string.msg_strings_injection_failed)
					}
				flashError(message)
			}
		}

		return result.isSuccess
	}

	private fun applyGeneratedXmlToEditor(generatedXml: String) {
		val view = provideCurrentEditor()
		val text =
			view?.editor?.text ?: run {
				log.warn("No file opened to append UI designer result")
				return
			}

		val endLine = text.lineCount - 1
		text.replace(0, 0, endLine, text.getColumnCount(endLine), generatedXml)
	}

	private fun setupDrawers() {
		// Note: Drawer toggle is now set up in setupToolbar() on the title toolbar
		// This method only sets up the drawer layout behavior
		binding.apply {
			editorDrawerLayout.apply {
				childId = contentCard.id
				translationBehaviorStart =
					ContentTranslatingDrawerLayout.TranslationBehavior.FULL
				translationBehaviorEnd =
					ContentTranslatingDrawerLayout.TranslationBehavior.FULL
				setScrimColor(Color.TRANSPARENT)
			}
			drawerSidebar.applyRootSystemInsetsAsPadding(applyTop = true)
		}
	}

	private fun onUpdateProgressBarVisibility() {
		log.debug(
			"onBuildStatusChanged: isInitializing: ${editorViewModel.isInitializing}, isBuildInProgress: ${editorViewModel.isBuildInProgress}",
		)
		val visible =
			editorViewModel.isBuildInProgress || editorViewModel.isInitializing || isDebuggerStarting
		content.progressIndicator.visibility = if (visible) View.VISIBLE else View.GONE
		invalidateOptionsMenu()
	}

	private fun setupStateObservers() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.CREATED) {
				launch {
					// should be active from CREATED through DESTROYED because
					// debugger connection updates can happen in the background
					// which won't be reported if we use Lifecycle.State.STARTED
					debuggerViewModel.connectionState.collectLatest { state ->
						onDebuggerConnectionStateChanged(state)
					}
				}

				launch {
					debuggerViewModel.debugeePackageFlow.collectLatest { newPackage ->
						debuggerService?.targetPackage = newPackage
					}
				}
			}
		}

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					bottomSheetViewModel.sheetState.collectLatest { state ->
						updateBottomSheetState(state = state)
					}
				}

				if (isAtLeastR()) {
					launch {
						wadbConnectionViewModel.status.collectLatest { status ->
							onUpdateWadbConnectionStatus(status)
						}
					}
				}
			}
		}

		editorViewModel._isBuildInProgress.observe(this) { onUpdateProgressBarVisibility() }
		editorViewModel._isInitializing.observe(this) { onUpdateProgressBarVisibility() }
		editorViewModel._statusText.observe(this) {
			content.bottomSheet.setStatus(
				it.first,
				it.second,
			)
		}

		editorViewModel.observeFiles(this) { files ->
			if (this is EditorHandlerActivity) {
				this.updateTabVisibility()
			} else {
				content.apply {
					if (files.isNullOrEmpty()) {
						tabs.visibility = View.GONE
						viewContainer.displayedChild = 1
					} else {
						tabs.visibility = View.VISIBLE
						viewContainer.displayedChild = 0
					}
				}
			}

			invalidateOptionsMenu()
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun onUpdateWadbConnectionStatus(status: WADBConnectionViewModel.ConnectionStatus) {
		when (status) {
			// Unknown status, do nothing
			WADBConnectionViewModel.ConnectionStatus.Unknown -> {
				Unit
			}

			// Pairing process has started, do nothing
			WADBConnectionViewModel.ConnectionStatus.Pairing -> {
				Unit
			}

			WADBConnectionViewModel.ConnectionStatus.Paired -> {
				// show debugger UI after pairing is successful
				bottomSheetViewModel.setSheetState(
					sheetState = BottomSheetBehavior.STATE_EXPANDED,
					currentTab = BottomSheetViewModel.TAB_DEBUGGER,
				)

				debuggerViewModel.currentView = DebuggerFragment.VIEW_WADB_PAIRING
			}

			WADBConnectionViewModel.ConnectionStatus.PairingFailed -> {
				flashError(getString(string.notification_adb_pairing_failed_title))
			}

			// These are handled by WADBPermissionFragment
			is WADBConnectionViewModel.ConnectionStatus.SearchingConnectionPort -> {
				Unit
			}

			WADBConnectionViewModel.ConnectionStatus.ConnectionPortNotFound -> {
				Unit
			}

			is WADBConnectionViewModel.ConnectionStatus.Connecting -> {
				Unit
			}

			is WADBConnectionViewModel.ConnectionStatus.ConnectionFailed -> {
				Unit
			}

			WADBConnectionViewModel.ConnectionStatus.Connected -> {
				debuggerViewModel.currentView = DebuggerFragment.VIEW_DEBUGGER
			}
		}
	}

	private fun setupFullscreenObserver() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				editorViewModel.uiState.collectLatest { uiState ->
					fullscreenManager?.render(uiState.isFullscreen, animate = true)
					updateSwipeRevealDragState()
				}
			}
		}
	}

	private fun setupBottomSheetObserver() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				bottomSheetViewModel.sheetState.collectLatest { _ ->
					updateSwipeRevealDragState()
				}
			}
		}
	}

	private fun updateSwipeRevealDragState() {
		val isFullscreen = editorViewModel.isFullscreen
		val isBottomSheetOpen =
			bottomSheetViewModel.sheetBehaviorState != STATE_COLLAPSED &&
				bottomSheetViewModel.sheetBehaviorState != STATE_HIDDEN
		binding.swipeReveal.setVerticalDragEnabled(!isFullscreen && !isBottomSheetOpen)
	}

	private fun setupViews() {
		setupNoEditorView()
		setupBottomSheet()

		if (!app.prefManager.getBoolean(
				KEY_BOTTOM_SHEET_SHOWN,
			) &&
			bottomSheetViewModel.sheetBehaviorState != BottomSheetBehavior.STATE_EXPANDED
		) {
			bottomSheetViewModel.setSheetState(BottomSheetBehavior.STATE_EXPANDED)
			mainThreadHandler.removeCallbacks(bottomSheetOnboardingCollapseRunnable)
			mainThreadHandler.postDelayed(bottomSheetOnboardingCollapseRunnable, 1500)
		}

		binding.contentCard.progress = 0f
		binding.swipeReveal.dragListener =
			object : SwipeRevealLayout.OnDragListener {
				override fun onDragStateChanged(
					swipeRevealLayout: SwipeRevealLayout,
					state: Int,
				) {
				}

				override fun onDragProgress(
					swipeRevealLayout: SwipeRevealLayout,
					progress: Float,
				) {
					onSwipeRevealDragProgress(progress)
				}
			}
	}

	protected open fun onDebuggerConnectionStateChanged(state: DebuggerConnectionState) {
		log.debug("onDebuggerConnectionStateChanged: {}", state)
		if (state == DebuggerConnectionState.ATTACHED) {
			ensureDebuggerServiceBound()
		}

		debuggerService?.onConnectionStateUpdated(newState = state)
		if (state == DebuggerConnectionState.ATTACHED) {
			// if a VM was just attached, make sure the debugger fragment is visible
			bottomSheetViewModel.setSheetState(
				sheetState = BottomSheetBehavior.STATE_HALF_EXPANDED,
				currentTab = BottomSheetViewModel.TAB_DEBUGGER,
			)
		}

		if (state == DebuggerConnectionState.AWAITING_BREAKPOINT) {
			// breakpoint hit, ensure IDE is in foreground
			debuggerViewModel.switchToIde(context = this)
		}

		postStopDebuggerServiceIfNotConnected()
	}

	private fun setupNoEditorView() {
		content.noEditorLayout.setOnLongClickListener {
			showTooltip(tag = TooltipTag.EDITOR_PROJECT_OVERVIEW)
			true
		}
		content.noEditorSummary.movementMethod = LinkMovementMethod()
		val sb = SpannableStringBuilder()
		val indentParent = 80
		val indentChild = 140

		fun appendHierarchicalText(textRes: Int) {
			val text = getString(textRes)
			text.split("\n").forEach { line ->
				val trimmed = line.trimStart()

				val margin =
					when {
						trimmed.startsWith("-") -> indentChild
						trimmed.startsWith("•") -> indentParent
						else -> 0
					}

				val spannable = SpannableString("$trimmed\n")

				if (margin > 0) {
					spannable.setSpan(
						LeadingMarginSpan.Standard(margin, margin),
						0,
						spannable.length,
						Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
					)
				}

				sb.append(spannable)
			}
		}

		appendHierarchicalText(string.msg_drawer_for_files)
		sb.append("\n")
		appendHierarchicalText(string.msg_swipe_for_output)
		sb.append("\n")
		appendHierarchicalText(string.msg_help_hint)

		content.noEditorSummary.apply {
			text = sb
			setOnLongClickListener {
				content.noEditorLayout.performLongClick()
				true
			}
		}
	}

	private fun setupBottomSheet() {
		editorBottomSheet = BottomSheetBehavior.from<View>(content.bottomSheet)
		BuildOutputProvider.setBottomSheet(content.bottomSheet)
		val cb =
			object : BottomSheetBehavior.BottomSheetCallback() {
				override fun onStateChanged(
					bottomSheet: View,
					newState: Int,
				) {
					// update the sheet state so that the ViewModel is in sync
					bottomSheetViewModel.setSheetState(sheetState = newState)
					if (newState == BottomSheetBehavior.STATE_EXPANDED) {
						provideCurrentEditor()?.editor?.ensureWindowsDismissed()
					}
				}

				override fun onSlide(
					bottomSheet: View,
					slideOffset: Float,
				) {
					content.apply {
						val safeOffset = slideOffset.coerceAtLeast(0f)
						val editorScale = 1 - safeOffset * (1 - EDITOR_CONTAINER_SCALE_FACTOR)
						this.bottomSheet.onSlide(slideOffset)
						this.viewContainer.scaleX = editorScale
						this.viewContainer.scaleY = editorScale
					}
				}
			}

		bottomSheetCallback = cb
		editorBottomSheet?.addBottomSheetCallback(cb)

		val observer: OnGlobalLayoutListener =
			object : OnGlobalLayoutListener {
				override fun onGlobalLayout() {
					contentCardRealHeight = binding.contentCard.height
					content.also {
						it.realContainer.pivotX = it.realContainer.width.toFloat() / 2f
						it.realContainer.pivotY =
							(it.realContainer.height.toFloat() / 2f) + (
								systemBarInsets?.run { bottom - top }
									?: 0
							)
						it.viewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
					}
				}
			}

		content.apply {
			viewContainer.viewTreeObserver.addOnGlobalLayoutListener(observer)
			applyBottomSheetAnchorForOrientation(resources.configuration.orientation)
		}
	}

	private fun setupDiagnosticInfo() {
		val gd = GradientDrawable()
		gd.shape = GradientDrawable.RECTANGLE
		gd.setColor(-0xdededf)
		gd.setStroke(1, -0x1)
		gd.cornerRadius = 8f
		diagnosticInfoBinding?.root?.background = gd
		diagnosticInfoBinding?.root?.visibility = View.GONE
	}

	private fun setupContainers() {
		handleDiagnosticsResultVisibility(true)
		handleSearchResultVisibility(true)
	}

	private fun onSoftInputChanged() {
		if (!isDestroying) {
			invalidateOptionsMenu()
			content.bottomSheet.onSoftInputChanged()
		}
	}

	fun setEditorSearchModeActive(isActive: Boolean) {
		contentOrNull?.bottomSheet?.setSearchModeActive(isActive)
	}

	private fun observeFileOperations() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				fileManagerViewModel.operationResult.collect { result ->
					when (result) {
						is FileOpResult.Success -> {
							flashMessage(
								result.messageRes,
								FlashType.SUCCESS,
							)
						}

						is FileOpResult.Error -> {
							flashMessage(result.messageRes, FlashType.ERROR)
						}
					}
				}
			}
		}
	}

	private fun setupGestureDetector() {
		gestureDetector =
			GestureDetector(
				this,
				object : GestureDetector.SimpleOnGestureListener() {
					override fun onFling(
						e1: MotionEvent?,
						e2: MotionEvent,
						velocityX: Float,
						velocityY: Float,
					): Boolean {
						if (e1 == null) return false

						val diffX = e2.x - e1.x
						val diffY = e2.y - e1.y

						val isVerticalSwipe = abs(diffY) > abs(diffX)
						val isHorizontalSwipe = abs(diffX) > abs(diffY)

						val hasDownFlingDistance = diffY > flingDistanceThreshold
						val hasUpFlingDistance = diffY < -flingDistanceThreshold

						val hasVerticalVelocity = abs(velocityY) > flingVelocityThreshold
						val hasHorizontalVelocity = abs(velocityX) > flingVelocityThreshold
						val hasRightFlingDistance = diffX > flingDistanceThreshold

						val screenHeight = resources.displayMetrics.heightPixels
						val bottomEdgeThreshold = screenHeight - topEdgeThreshold

						val startedNearTopEdge = e1.y < topEdgeThreshold
						val startedNearBottomEdge = e1.y > bottomEdgeThreshold
						val isTopEdgeDismissFling =
							isVerticalSwipe &&
								hasVerticalVelocity &&
								startedNearTopEdge &&
								hasDownFlingDistance
						val isBottomEdgeDismissFling =
							isVerticalSwipe &&
								hasVerticalVelocity &&
								startedNearBottomEdge &&
								hasUpFlingDistance
						val isDrawerOpenFling =
							hasRightFlingDistance &&
								hasHorizontalVelocity &&
								isHorizontalSwipe

						val isBottomSheetOpen =
							bottomSheetViewModel.sheetBehaviorState != STATE_COLLAPSED &&
								bottomSheetViewModel.sheetBehaviorState != STATE_HIDDEN

						if (isBottomSheetOpen) {
							return false
						}

						if (isTopEdgeDismissFling && editorViewModel.isFullscreen) {
							editorViewModel.exitFullscreen()
							return true
						}

						if (isBottomEdgeDismissFling && editorViewModel.isFullscreen) {
							editorViewModel.exitFullscreen()
							return true
						}

						// Preserve the editor interaction area; drawer gestures are only enabled on the empty state.
						val noFilesOpen = content.viewContainer.displayedChild == 1
						if (!noFilesOpen) {
							return false
						}

						// Filter out diagonal flings so only an intentional right swipe opens the drawer.
						// A horizontal fling that started on the bottom-sheet tab strip is the user
						// scrolling tabs, not asking for the drawer; one that started on the metrics
						// carousel is the user paging it backwards.
						if (isDrawerOpenFling &&
							!isTouchOnBottomSheetTabs(e1) &&
							!isTouchOnMetricsCarousel(e1)
						) {
							binding.editorDrawerLayout.openDrawer(GravityCompat.START)
							return true
						}

						return false
					}
				},
			)
	}

	override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
		// Pass the event to our gesture detector first
		if (ev != null) {
			gestureDetector?.onTouchEvent(ev)
		}
		// Then, let the default dispatching happen
		return super.dispatchTouchEvent(ev)
	}

	private fun isTouchOnBottomSheetTabs(ev: MotionEvent): Boolean {
		val tabs = contentOrNull?.bottomSheet?.binding?.tabs ?: return false
		return containsTouch(tabs, ev)
	}

	private fun isTouchOnMetricsCarousel(ev: MotionEvent): Boolean {
		val binding = _binding ?: return false

		// The carousel is laid out at the top of the reveal even while the content card covers it,
		// and siblings do not clip each other, so getGlobalVisibleRect reports it visible either
		// way. Without this check the drawer gesture would be dead over the top of a closed editor.
		if (binding.swipeReveal.dragProgress <= 0f) {
			return false
		}

		return containsTouch(binding.memUsageView.root, ev)
	}

	private fun containsTouch(
		view: View,
		ev: MotionEvent,
	): Boolean {
		val rect = Rect()
		if (!view.getGlobalVisibleRect(rect)) return false
		return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
	}

	private fun showTooltip(tag: String) {
		TooltipManager.showIdeCategoryTooltip(
			context = this,
			anchorView = content.projectActionsToolbar,
			tag = tag,
		)
	}

	private fun getLogContent(): String? {
		val pagerAdapter = binding.content.bottomSheet.pagerAdapter

		val candidateTabs =
			buildList {
				add(bottomSheetViewModel.currentTab)
				add(BottomSheetViewModel.TAB_BUILD_OUTPUT)
				add(BottomSheetViewModel.TAB_APPLICATION_LOGS)
				add(BottomSheetViewModel.TAB_IDE_LOGS)
			}.distinct()

		candidateTabs.forEach { tabIndex ->
			val fragment = pagerAdapter.getFragmentAtIndex<Fragment>(tabIndex)
			if (fragment is ShareableOutputFragment) {
				val shareable = fragment.getShareableContent().trim()
				if (shareable.isNotEmpty()) {
					return shareable
				}
			}
		}

		return null
	}
}
