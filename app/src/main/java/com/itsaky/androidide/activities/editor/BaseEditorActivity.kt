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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.annotation.GravityInt
import androidx.annotation.UiThread
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.collection.MutableIntIntMap
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blankj.utilcode.constant.MemoryConstants
import com.blankj.utilcode.util.ConvertUtils.byte2MemorySize
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ThreadUtils
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.Tab
import com.itsaky.androidide.R
import com.itsaky.androidide.R.string
import com.itsaky.androidide.actions.build.DebugAction
import com.itsaky.androidide.adapters.DiagnosticsAdapter
import com.itsaky.androidide.adapters.SearchListAdapter
import com.itsaky.androidide.api.BuildOutputProvider
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.ActivityEditorBinding
import com.itsaky.androidide.databinding.ContentEditorBinding
import com.itsaky.androidide.databinding.LayoutDiagnosticInfoBinding
import com.itsaky.androidide.events.InstallationResultEvent
import com.itsaky.androidide.fragments.sidebar.EditorSidebarFragment
import com.itsaky.androidide.fragments.sidebar.FileTreeFragment
import com.itsaky.androidide.handlers.EditorActivityLifecyclerObserver
import com.itsaky.androidide.handlers.LspHandler.registerLanguageServers
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.interfaces.DiagnosticClickListener
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.models.DiagnosticGroup
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SearchResult
import com.itsaky.androidide.plugins.manager.ui.PluginEditorTabManager
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.services.debug.DebuggerService
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.ui.ContentTranslatingDrawerLayout
import com.itsaky.androidide.ui.SwipeRevealLayout
import com.itsaky.androidide.uidesigner.UIDesignerActivity
import com.itsaky.androidide.utils.ActionMenuUtils.showPopupWindow
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.FlashType
import com.itsaky.androidide.utils.InstallationResultHandler.onResult
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashMessage
import com.itsaky.androidide.utils.resolveAttr
import com.itsaky.androidide.viewmodel.ApkInstallationViewModel
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.DebuggerConnectionState
import com.itsaky.androidide.viewmodel.DebuggerViewModel
import com.itsaky.androidide.viewmodel.EditorViewModel
import com.itsaky.androidide.viewmodel.FileManagerViewModel
import com.itsaky.androidide.viewmodel.FileOpResult
import com.itsaky.androidide.xml.resources.ResourceTableRegistry
import com.itsaky.androidide.xml.versions.ApiVersionsRegistry
import com.itsaky.androidide.xml.widgets.WidgetTableRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.FeedbackButtonManager
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode.MAIN
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
	protected val mLifecycleObserver = EditorActivityLifecyclerObserver()
	protected var diagnosticInfoBinding: LayoutDiagnosticInfoBinding? = null
	protected var filesTreeFragment: FileTreeFragment? = null
	protected var editorBottomSheet: BottomSheetBehavior<out View?>? = null
	protected val memoryUsageWatcher = MemoryUsageWatcher()
	protected val pidToDatasetIdxMap = MutableIntIntMap(initialCapacity = 3)

	private val fileManagerViewModel by viewModels<FileManagerViewModel>()

    var isDestroying = false
        protected set

	/**
	 * Editor activity's [CoroutineScope] for executing tasks in the background.
	 */
	protected val editorActivityScope = CoroutineScope(Dispatchers.Default)

	var uiDesignerResultLauncher: ActivityResultLauncher<Intent>? = null
	val editorViewModel by viewModels<EditorViewModel>()
	val debuggerViewModel by viewModels<DebuggerViewModel>()
	val bottomSheetViewModel by viewModels<BottomSheetViewModel>()
	val apkInstallationViewModel by viewModels<ApkInstallationViewModel>()

	@Suppress("ktlint:standard:backing-property-naming")
	internal var _binding: ActivityEditorBinding? = null
	val binding: ActivityEditorBinding
		get() = checkNotNull(_binding) { "Activity has been destroyed" }
	val content: ContentEditorBinding
		get() = binding.content

	override val subscribeToEvents: Boolean
		get() = true

	private val onBackPressedCallback: OnBackPressedCallback =
		object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (binding.editorDrawerLayout.isDrawerOpen(GravityCompat.START)) {
					binding.editorDrawerLayout.closeDrawer(GravityCompat.START)
				} else if (bottomSheetViewModel.sheetBehaviorState != BottomSheetBehavior.STATE_COLLAPSED) {
					bottomSheetViewModel.setSheetState(sheetState = BottomSheetBehavior.STATE_COLLAPSED)
				} else if (binding.swipeReveal.isOpen) {
					binding.swipeReveal.close()
				} else {
					doConfirmProjectClose()
				}
			}
		}

	private val memoryUsageListener =
		MemoryUsageWatcher.MemoryUsageListener { memoryUsage ->
			memoryUsage.forEachValue { proc ->
				_binding?.memUsageView?.chart?.apply {
					val dataset =
						(data.getDataSetByIndex(pidToDatasetIdxMap[proc.pid]) as LineDataSet?)
							?: run {
								log.error(
									"No dataset found for process: {}: {}",
									proc.pid,
									proc.pname,
								)
								return@forEachValue
							}

					dataset.entries.mapIndexed { index, entry ->
						entry.y =
							byte2MemorySize(proc.usageHistory[index], MemoryConstants.MB).toFloat()
					}

					dataset.label = "%s - %.2fMB".format(proc.pname, dataset.entries.last().y)
					dataset.notifyDataSetChanged()
					data.notifyDataChanged()
					notifyDataSetChanged()
					invalidate()
				}
			}
		}

	private val shizukuBinderReceivedListener =
		Shizuku.OnBinderReceivedListener {
			invalidateOptionsMenu()
		}

	private var isImeVisible = false
	private var contentCardRealHeight: Int? = null
	private val editorSurfaceContainerBackground by lazy {
		resolveAttr(R.attr.colorSurfaceDim)
	}

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
			log.error("Failed to stop debugger service", e)
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

		val intent = Intent(this, DebuggerService::class.java)
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

	private lateinit var gestureDetector: GestureDetector
    private val flingDistanceThreshold by lazy { SizeUtils.dp2px(100f) }
    private val flingVelocityThreshold by lazy { SizeUtils.dp2px(100f) }

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

		const val EDITOR_CONTAINER_SCALE_FACTOR = 0.87f
		const val KEY_BOTTOM_SHEET_SHOWN = "editor_bottomSheetShown"
		const val KEY_PROJECT_PATH = "saved_projectPath"
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
		BuildOutputProvider.clearBottomSheet()
		_binding = null

		Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)

		optionsMenuInvalidator?.also {
			ThreadUtils.getMainHandler().removeCallbacks(it)
		}

		optionsMenuInvalidator = null

		apkInstallationViewModel.destroy(this)

		if (isDestroying) {
			memoryUsageWatcher.stopWatching(true)
			memoryUsageWatcher.listener = null
			editorActivityScope.cancelIfActive("Activity is being destroyed")

			unbindDebuggerService()
		}
	}

	protected open fun postDestroy() {
		if (isDestroying) {
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
		val height = contentCardRealHeight ?: return
		val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

		_binding?.content?.bottomSheet?.setImeVisible(imeInsets.bottom > 0)
		_binding?.contentCard?.updateLayoutParams<ViewGroup.LayoutParams> {
			this.height = height - imeInsets.bottom
		}

		val isImeVisible = imeInsets.bottom > 0
		if (this.isImeVisible != isImeVisible) {
			this.isImeVisible = isImeVisible
			onSoftInputChanged()
		}
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		super.onApplySystemBarInsets(insets)
		this._binding?.apply {
			drawerSidebar
				.getFragment<EditorSidebarFragment>()
				.onApplyWindowInsets(insets)

			content.apply {
				editorAppBarLayout.updatePadding(
					top = insets.top,
				)
				customToolbar.setContentInsetsRelative(0, 0)
			}
		}
	}

	@Subscribe(threadMode = MAIN)
	open fun onInstallationResult(event: InstallationResultEvent) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

		Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)

        this.optionsMenuInvalidator = Runnable { super.invalidateOptionsMenu() }

        registerLanguageServers()

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_PROJECT_PATH)) {
            savedInstanceState.getString(KEY_PROJECT_PATH)?.let { path ->
                ProjectManagerImpl.getInstance().projectPath = path
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        lifecycle.addObserver(mLifecycleObserver)

        setupToolbar()
        setupDrawers()
        content.tabs.addOnTabSelectedListener(this)

		setupStateObservers()
        setupViews()

        setupContainers()
        setupDiagnosticInfo()

        uiDesignerResultLauncher = registerForActivityResult(
            StartActivityForResult(),
            this::handleUiDesignerResult
        )

        content.bottomSheet.binding.buildStatus.buildStatusLayout.setOnLongClickListener {
            showTooltip(
                tag = TooltipTag.EDITOR_BUILD_STATUS
            )
            true
        }

        FeedbackButtonManager(activity = this, feedbackFab = binding.fabFeedback)
            .setupDraggableFab()

        setupMemUsageChart()
        watchMemory()
        observeFileOperations()

        setupGestureDetector()
    }

    private fun setupToolbar() {
        content.customToolbar.apply {
            setTitleText(editorViewModel.getProjectName())

            val toggle = object : ActionBarDrawerToggle(
                this@BaseEditorActivity,
                binding.editorDrawerLayout,
                content.customToolbar,
                string.app_name,
                string.app_name
            ) {
                override fun onDrawerOpened(drawerView: View) {
                    super.onDrawerOpened(drawerView)
                    // Hide the keyboard when the drawer opens.
                    closeKeyboard()
                }
            }


            binding.editorDrawerLayout.addDrawerListener(toggle)
            toggle.syncState()
            setOnNavIconLongClickListener {
                showTooltip(
                    tag = TooltipTag.EDITOR_TOOLBAR_NAV_ICON
                )
            }
        }

    }

    private fun onSwipeRevealDragProgress(progress: Float) {
        _binding?.apply {
            contentCard.progress = progress
            val insetsTop = systemBarInsets?.top ?: 0
            content.editorAppBarLayout.updatePadding(
                top = (insetsTop * (1f - progress)).roundToInt()
            )
            memUsageView.chart.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = (insetsTop * progress).roundToInt()
            }
        }
    }

    private fun setupMemUsageChart() {
        binding.memUsageView.chart.apply {
            val colorAccent = resolveAttr(R.attr.colorAccent)

            isDragEnabled = false
            description.isEnabled = false
            xAxis.axisLineColor = colorAccent
            axisRight.axisLineColor = colorAccent

            setPinchZoom(false)
            setBackgroundColor(editorSurfaceContainerBackground)
            setDrawGridBackground(true)
            setScaleEnabled(true)

            axisLeft.isEnabled = false
            axisRight.valueFormatter = object :
                IAxisValueFormatter {
                override fun getFormattedValue(value: Float, axis: AxisBase?): String {
                    return "%dMB".format(value.roundToLong())
                }
            }
        }
    }

    private fun watchMemory() {
        memoryUsageWatcher.listener = memoryUsageListener
        memoryUsageWatcher.watchProcess(Process.myPid(), PROC_IDE)
        resetMemUsageChart()
    }

    protected fun resetMemUsageChart() {
        val processes = memoryUsageWatcher.getMemoryUsages()
        val datasets = Array(processes.size) { index ->
            LineDataSet(
                List(MemoryUsageWatcher.MAX_USAGE_ENTRIES) { Entry(it.toFloat(), 0f) },
                processes[index].pname
            )
        }

        val bgColor = editorSurfaceContainerBackground
        val textColor = resolveAttr(R.attr.colorOnSurface)

        for ((index, proc) in processes.withIndex()) {
            val dataset = datasets[index]
            dataset.color = getMemUsageLineColorFor(proc)
            dataset.setDrawIcons(false)
            dataset.setDrawCircles(false)
            dataset.setDrawCircleHole(false)
            dataset.setDrawValues(false)
            dataset.formLineWidth = 1f
            dataset.formSize = 15f
            dataset.isHighlightEnabled = false
            pidToDatasetIdxMap[proc.pid] = index
        }

        binding.memUsageView.chart.setBackgroundColor(bgColor)

        binding.memUsageView.chart.apply {
            data = LineData(*datasets)
            axisRight.textColor = textColor
            axisLeft.textColor = textColor
            legend.textColor = textColor

            data.setValueTextColor(textColor)
            setBackgroundColor(bgColor)
            setGridBackgroundColor(bgColor)
            notifyDataSetChanged()
            invalidate()
        }
    }

    private fun getMemUsageLineColorFor(proc: MemoryUsageWatcher.ProcessMemoryInfo): Int {
        return when (proc.pname) {
            PROC_IDE -> Color.BLUE
            PROC_GRADLE_TOOLING -> Color.RED
            PROC_GRADLE_DAEMON -> Color.GREEN
            else -> throw IllegalArgumentException("Unknown process: $proc")
        }
    }

    override fun onPause() {
        super.onPause()
        memoryUsageWatcher.listener = null
        memoryUsageWatcher.stopWatching(false)

        this.isDestroying = isFinishing
        getFileTreeFragment()?.saveTreeState()

        // Clear current activity from plugin services when activity is finishing
        if (isFinishing) {
            IDEApplication.instance.setCurrentActivity(null)
        }
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()

        memoryUsageWatcher.listener = memoryUsageListener
        memoryUsageWatcher.startWatching()

		apkInstallationViewModel.reloadStatus(this)

        try {
            getFileTreeFragment()?.listProjectFiles()
        } catch (th: Throwable) {
            log.error("Failed to update files list", th)
            flashError(string.msg_failed_list_files)
        }

        // Set this activity as current for plugin services
        IDEApplication.instance.setCurrentActivity(this)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PROJECT_PATH, IProjectManager.getInstance().projectDirPath)
        super.onSaveInstanceState(outState)
    }

    override fun invalidateOptionsMenu() {
        val mainHandler = ThreadUtils.getMainHandler()
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

        val fileIndex = if (this is EditorHandlerActivity) {
            getFileIndexForTabPosition(position)
        } else {
            position
        }

        if (fileIndex == -1) {
            invalidateOptionsMenu()
            return
        }

        editorViewModel.displayedFileIndex = fileIndex

        val editorView = if (this is EditorHandlerActivity) {
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
        showPopupWindow(
            context = this,
            anchorView = tab.view
        )
    }

    override fun onGroupClick(group: DiagnosticGroup?) {
        if (group?.file?.exists() == true && FileUtils.isUtf8(group.file)) {
            doOpenFile(group.file, null)
            hideBottomSheet()
        }
    }

    override fun onDiagnosticClick(file: File, diagnostic: DiagnosticItem) {
        doOpenFile(file, diagnostic.range)
        hideBottomSheet()
    }

    open fun handleSearchResults(map: Map<File, List<SearchResult>>?) {
        val results = map ?: emptyMap()
        setSearchResultAdapter(SearchListAdapter(results, { file ->
            doOpenFile(file, null)
            hideBottomSheet()
        }) { match ->
            doOpenFile(match.file, match)
            hideBottomSheet()
        })

		bottomSheetViewModel.setSheetState(currentTab = BottomSheetViewModel.TAB_SEARCH_RESULT)
        doDismissSearchProgress()
    }

    open fun setSearchResultAdapter(adapter: SearchListAdapter) {
        content.bottomSheet.setSearchResultAdapter(adapter)
    }

    open fun setDiagnosticsAdapter(adapter: DiagnosticsAdapter) {
        content.bottomSheet.setDiagnosticsAdapter(adapter)
    }

    open fun hideBottomSheet() {
		bottomSheetViewModel.setSheetState(sheetState = BottomSheetBehavior.STATE_COLLAPSED)
    }

	private fun updateBottomSheetState(state: BottomSheetViewModel.SheetState = BottomSheetViewModel.SheetState.EMPTY) {
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
        if (filesTreeFragment == null) {
            filesTreeFragment = supportFragmentManager.findFragmentByTag(
                FileTreeFragment.TAG
            ) as FileTreeFragment?
        }
        return filesTreeFragment
    }

    fun doSetStatus(text: CharSequence, @GravityInt gravity: Int = Gravity.CENTER) {
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
                result.data
            )
            return
        }
        val generated =
            result.data!!.getStringExtra(UIDesignerActivity.RESULT_GENERATED_XML)
        if (TextUtils.isEmpty(generated)) {
            log.warn("UI Designer returned blank generated XML code")
            return
        }
        val view = provideCurrentEditor()
        val text = view?.editor?.text ?: run {
            log.warn("No file opened to append UI designer result")
            return
        }
        val endLine = text.lineCount - 1
        text.replace(0, 0, endLine, text.getColumnCount(endLine), generated)
    }

    private fun setupDrawers() {
        val toggle = object : ActionBarDrawerToggle(
            this,
            binding.editorDrawerLayout,
            content.customToolbar,
            string.app_name,
            string.app_name
        ) {
            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                // Hide the keyboard when the drawer opens.
                closeKeyboard()
            }
        }


        binding.editorDrawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.apply {
            editorDrawerLayout.apply {
                childId = contentCard.id
                translationBehaviorStart =
                    ContentTranslatingDrawerLayout.TranslationBehavior.FULL
                translationBehaviorEnd =
                    ContentTranslatingDrawerLayout.TranslationBehavior.FULL
                setScrimColor(Color.TRANSPARENT)
            }
        }
    }

    private fun onUpdateProgressBarVisibility() {
        log.debug("onBuildStatusChanged: isInitializing: ${editorViewModel.isInitializing}, isBuildInProgress: ${editorViewModel.isBuildInProgress}")
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
			}
		}

		editorViewModel._isBuildInProgress.observe(this) { onUpdateProgressBarVisibility() }
		editorViewModel._isInitializing.observe(this) { onUpdateProgressBarVisibility() }
		editorViewModel._statusText.observe(this) {
			content.bottomSheet.setStatus(
				it.first,
				it.second
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

	private fun setupViews() {
        setupNoEditorView()
        setupBottomSheet()

        if (!app.prefManager.getBoolean(
                KEY_BOTTOM_SHEET_SHOWN
            ) && bottomSheetViewModel.sheetBehaviorState != BottomSheetBehavior.STATE_EXPANDED
        ) {
            bottomSheetViewModel.setSheetState(BottomSheetBehavior.STATE_EXPANDED)
            ThreadUtils.runOnUiThreadDelayed({
				bottomSheetViewModel.setSheetState(BottomSheetBehavior.STATE_COLLAPSED)
                app.prefManager.putBoolean(KEY_BOTTOM_SHEET_SHOWN, true)
            }, 1500)
        }

        binding.contentCard.progress = 0f
        binding.swipeReveal.dragListener = object : SwipeRevealLayout.OnDragListener {
            override fun onDragStateChanged(
                swipeRevealLayout: SwipeRevealLayout,
                state: Int
            ) {
            }

            override fun onDragProgress(
                swipeRevealLayout: SwipeRevealLayout,
                progress: Float
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

		debuggerService?.setOverlayVisibility(state >= DebuggerConnectionState.ATTACHED)
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
			showTooltip(
				tag = TooltipTag.EDITOR_PROJECT_OVERVIEW
			)
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

                val margin = when {
                    trimmed.startsWith("-") -> indentChild
                    trimmed.startsWith("•") -> indentParent
                    else -> 0
                }

                val spannable = SpannableString("$trimmed\n")

                if (margin > 0) {
                    spannable.setSpan(
                        LeadingMarginSpan.Standard(margin, margin),
                        0, spannable.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                sb.append(spannable)
            }
        }

        appendHierarchicalText(R.string.msg_drawer_for_files)
        sb.append("\n")
        appendHierarchicalText(R.string.msg_swipe_for_output)
        sb.append("\n")
        appendHierarchicalText(R.string.msg_help_hint)

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
        editorBottomSheet?.addBottomSheetCallback(object : BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
				// update the sheet state so that the ViewModel is in sync
				bottomSheetViewModel.setSheetState(sheetState = newState)

                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val editor = provideCurrentEditor()
                    editor?.editor?.ensureWindowsDismissed()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
				content.apply {
                    val editorScale = 1 - slideOffset * (1 - EDITOR_CONTAINER_SCALE_FACTOR)
                    this.bottomSheet.onSlide(slideOffset)
                    this.viewContainer.scaleX = editorScale
                    this.viewContainer.scaleY = editorScale
                }
            }
        })

        val observer: OnGlobalLayoutListener = object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentCardRealHeight = binding.contentCard.height
                content.also {
                    it.realContainer.pivotX = it.realContainer.width.toFloat() / 2f
                    it.realContainer.pivotY =
                        (it.realContainer.height.toFloat() / 2f) + (systemBarInsets?.run { bottom - top }
                            ?: 0)
                    it.viewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        }

        content.apply {
            viewContainer.viewTreeObserver.addOnGlobalLayoutListener(observer)
            bottomSheet.setOffsetAnchor(editorAppBarLayout)
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

    private fun observeFileOperations() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileManagerViewModel.operationResult.collect { result ->
                    when (result) {
                        is FileOpResult.Success -> flashMessage(
                            result.messageRes,
                            FlashType.SUCCESS
                        )

                        is FileOpResult.Error -> flashMessage(result.messageRes, FlashType.ERROR)
                    }
                }
            }
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Check if no files are open by looking at the displayedChild of the view flipper
                val noFilesOpen = content.viewContainer.displayedChild == 1
                if (!noFilesOpen) {
                    return false // If files are open, do nothing
                }

                val diffX = e2.x - (e1?.x ?: 0f)

                // Check for a right swipe (to open left drawer)
                if (diffX > flingDistanceThreshold && abs(velocityX) > flingVelocityThreshold) {
                    binding.editorDrawerLayout.openDrawer(GravityCompat.START)
                    return true
                }

                // Check for a left swipe (to open right drawer)
                if (diffX < -flingDistanceThreshold && abs(velocityX) > flingVelocityThreshold) {
                    binding.editorDrawerLayout.openDrawer(GravityCompat.END)
                    return true
                }

                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Pass the event to our gesture detector first
        if (ev != null) {
            gestureDetector.onTouchEvent(ev)
        }
        // Then, let the default dispatching happen
        return super.dispatchTouchEvent(ev)
    }

    private fun showTooltip(tag: String) {
        TooltipManager.showTooltip(
            context = this@BaseEditorActivity,
            anchorView = content.customToolbar,
            tag = tag,
        )
    }
}

