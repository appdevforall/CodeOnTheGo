package com.itsaky.androidide.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsaky.androidide.R
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.ui.compose.plugins.PluginManagerContent
import com.itsaky.androidide.ui.compose.templates.TemplateManagerScreen
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import com.itsaky.androidide.viewmodels.TemplateManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Matches Material's conventional disabled-content alpha; M3 has no ContentAlpha equivalent. */
private const val DISABLED_ALPHA = 0.38f

private const val TAB_PLUGINS = 0
private const val TAB_TEMPLATES = 1

/**
 * A `pointerInput(detectTapGestures(onLongPress = ...))` modifier placed on
 * [FloatingActionButton]/[IconButton] never fires: both append their own `clickable` after the
 * caller's modifier, so on the `Main` pointer pass their `clickable` (innermost) consumes the
 * down event before it reaches this composable's own gesture detector. Driving the long-press
 * off the button's own [MutableInteractionSource] sidesteps the race entirely - it observes the
 * same press/release stream the button's `clickable` reports, rather than competing for the raw
 * pointer event.
 *
 * Detection alone isn't suppression: [FloatingActionButton] routes to plain `clickable`
 * (`detectTapAndPress`), which has no long-press concept, so the finger lift after a long press
 * still fires `onClick`. The returned [LongPressAwareClick] latches a flag the moment the
 * long-press timeout elapses - strictly before that lift is reported - so the caller's `onClick`
 * can check-and-clear it via [LongPressAwareClick.consumeIfSuppressed] to swallow exactly that
 * one click.
 */
@Composable
private fun rememberLongPressInteractionSource(onLongPress: () -> Unit): LongPressAwareClick {
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
	val currentOnLongPress by rememberUpdatedState(onLongPress)
	val suppressNextClick = remember { mutableStateOf(false) }
	LaunchedEffect(isPressed) {
		if (isPressed) {
			// Reset at press start, not dependent on a click to clear it: a long press that
			// doesn't end in a tap-up (slide off before lifting, or a focusable tooltip popup
			// stealing the gesture) never reaches consumeIfSuppressed(), which would otherwise
			// leave the flag latched and silently eat the next real tap. The previous gesture's
			// onTap always fires on its own lift, strictly before this ACTION_DOWN, so a click
			// that legitimately needs suppressing can never be un-suppressed by this reset.
			suppressNextClick.value = false
			delay(longPressTimeoutMillis)
			suppressNextClick.value = true
			currentOnLongPress()
		}
	}
	return LongPressAwareClick(interactionSource, suppressNextClick)
}

/** See [rememberLongPressInteractionSource]. */
private class LongPressAwareClick(
	val interactionSource: MutableInteractionSource,
	private val suppressNextClick: MutableState<Boolean>,
) {
	/** Returns true (and clears the flag) if this click is the tail end of a long press. */
	fun consumeIfSuppressed(): Boolean {
		if (!suppressNextClick.value) return false
		suppressNextClick.value = false
		return true
	}
}

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
	val pluginUiState by pluginViewModel.uiState.collectAsStateWithLifecycle()

	fun showTooltip() {
		TooltipManager.showIdeCategoryTooltip(activity, rootView, TooltipTag.PLUGIN_MANAGER)
	}

	Scaffold(
		modifier = modifier,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.title_manager)) },
				windowInsets = WindowInsets(0, 0, 0, 0),
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
						// Not an IconButton: it appends its own clickable() after this modifier, which
						// would compete with combinedClickable's detector for the same pointer events -
						// see rememberLongPressInteractionSource's doc. .size(48.dp) matches
						// IconButtonTokens' 48dp minimum touch target; combinedClickable's default
						// indication already supplies the ripple IconButton would have.
						Box(
							modifier =
								Modifier
									.size(48.dp)
									.clip(CircleShape)
									.combinedClickable(
										role = Role.Button,
										onLongClickLabel = stringResource(R.string.cd_show_tooltip),
										onClick = {
											UrlManager.openUrl(activity.getString(R.string.url_discover_plugins), null, activity)
										},
										onLongClick = { showTooltip() },
									),
							contentAlignment = Alignment.Center,
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
				val longPressAwareClick = rememberLongPressInteractionSource { showTooltip() }
				FloatingActionButton(
					onClick = {
						// The long press that just showed the tooltip also ends in a finger lift, which
						// FloatingActionButton's plain clickable() has no long-press concept to suppress
						// on its own - swallow that one click here.
						if (longPressAwareClick.consumeIfSuppressed()) return@FloatingActionButton
						if (!pluginUiState.isInstalling) {
							pluginViewModel.onEvent(PluginManagerUiEvent.OpenFilePicker)
						}
					},
					modifier = Modifier.alpha(if (pluginUiState.isInstalling) DISABLED_ALPHA else 1f),
					interactionSource = longPressAwareClick.interactionSource,
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
