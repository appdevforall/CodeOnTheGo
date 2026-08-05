package com.itsaky.androidide.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.ui.compose.plugins.PluginManagerContent
import com.itsaky.androidide.ui.compose.templates.TemplateManagerScreen
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import kotlinx.coroutines.launch

private const val TAB_PLUGINS = 0
private const val TAB_TEMPLATES = 1

/**
 * Root screen for `PluginManagerActivity` (ADFA-4928): a single manager with two tabs, Plugins
 * and Templates, defaulting to Plugins. Owns the one shared Scaffold/TopAppBar; the FAB and
 * discover-plugins action only apply to the Plugins tab, since the Templates tab is a passive
 * scan of the Downloads folder with no equivalent action.
 *
 * Forwards each tab's ViewModel one level down to its own content composable rather than
 * hoisting all plugin/template UI state up into this shared screen - matches this repo's
 * established Koin `by viewModel()` + pass-as-parameter pattern (no koinViewModel() dependency).
 */
@Suppress("ktlint:compose:vm-forwarding-check")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManagerScreen(
	activity: ComponentActivity,
	pluginViewModel: PluginManagerViewModel,
	templateViewModel: TemplateManagerViewModel,
	modifier: Modifier = Modifier,
) {
	val pagerState = rememberPagerState(pageCount = { 2 })
	val coroutineScope = rememberCoroutineScope()
	val rootView = LocalView.current

	fun showTooltip() {
		TooltipManager.showIdeCategoryTooltip(activity, rootView, TooltipTag.PLUGIN_MANAGER)
	}

	Scaffold(
		modifier = modifier,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.title_manager)) },
				navigationIcon = {
					IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) {
						Icon(
							painter = painterResource(R.drawable.ic_back),
							contentDescription = stringResource(R.string.cd_navigate_back),
						)
					}
				},
				actions = {
					if (pagerState.currentPage == TAB_PLUGINS) {
						IconButton(
							onClick = {
								UrlManager.openUrl(activity.getString(R.string.url_discover_plugins), null, activity)
							},
							modifier =
								Modifier.pointerInput(Unit) {
									detectTapGestures(onLongPress = { showTooltip() })
								},
						) {
							Icon(
								painter = painterResource(R.drawable.ic_download),
								contentDescription = stringResource(R.string.action_discover_plugins),
							)
						}
					}
				},
			)
		},
		floatingActionButton = {
			if (pagerState.currentPage == TAB_PLUGINS) {
				FloatingActionButton(
					onClick = { pluginViewModel.onEvent(PluginManagerUiEvent.OpenFilePicker) },
					modifier =
						Modifier.pointerInput(Unit) {
							detectTapGestures(onLongPress = { showTooltip() })
						},
				) {
					Icon(
						painter = painterResource(R.drawable.ic_add),
						contentDescription = stringResource(R.string.cd_add),
					)
				}
			}
		},
	) { padding ->
		Column(modifier = Modifier.padding(padding).fillMaxSize()) {
			TabRow(selectedTabIndex = pagerState.currentPage) {
				Tab(
					selected = pagerState.currentPage == TAB_PLUGINS,
					onClick = { coroutineScope.launch { pagerState.animateScrollToPage(TAB_PLUGINS) } },
					text = { Text(stringResource(R.string.tab_plugins)) },
				)
				Tab(
					selected = pagerState.currentPage == TAB_TEMPLATES,
					onClick = { coroutineScope.launch { pagerState.animateScrollToPage(TAB_TEMPLATES) } },
					text = { Text(stringResource(R.string.tab_templates)) },
				)
			}

			HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
				when (page) {
					TAB_PLUGINS -> {
						PluginManagerContent(
							activity = activity,
							viewModel = pluginViewModel,
							modifier = Modifier.fillMaxSize(),
						)
					}

					TAB_TEMPLATES -> {
						TemplateManagerScreen(
							activity = activity,
							viewModel = templateViewModel,
							modifier = Modifier.fillMaxSize(),
						)
					}
				}
			}
		}
	}
}
