package com.itsaky.androidide.quickbuild

import android.content.Context
import android.view.LayoutInflater
import android.view.View.MeasureSpec
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutEditorBuildStatusBinding
import com.itsaky.androidide.ui.bindStatusLineTap
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.SessionEvent
import org.appdevforall.cotg.quickbuild.domain.session.SessionReducer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.itsaky.androidide.resources.R as Res

/**
 * The Quick Build status line takes the tap its text asks for (F221).
 *
 * While the SQLite debugger is on, the line reads "Quick Build: off while the SQLite debugger is
 * on - tap for details", and tapping it did nothing: only the toolbar button showed the refusal.
 * A tap on the line must now run the button's tap, which shows the refusal again.
 *
 * The editor activity is far beyond a JVM test, so this drives the parts it joins: the real
 * status mapping, the real status-line layout, and the binder the bottom sheet uses, applied the
 * way ProjectHandlerActivity applies them (every write drops the tap, then a Show that asks for one
 * binds it). The tap callback stands in for the activity's run of the toolbar action.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildStatusLineTapTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()

	/** The real status block, inflated against the app's theme, which its text colour needs. */
	private val line =
		LayoutEditorBuildStatusBinding.inflate(
			LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_AndroidIDE)),
		)

	private var quickBuildTaps = 0

	private val refused =
		QuickBuildStatus.from(
			SessionReducer()
				.reduce(
					QuickBuildSessionState.Provisioning(),
					SessionEvent.ProvisioningFailed(QuickBuildMessage.DisabledBySqliteDebugger),
				).state,
		)

	private fun show(update: QuickBuildStatusBarUpdate?) {
		val show = update as QuickBuildStatusBarUpdate.Show
		bindStatusLineTap(line.statusText, null, null)
		line.statusText.text = context.getString(show.text, *show.args.toTypedArray())
		show.tapLabel?.let { label ->
			bindStatusLineTap(line.statusText, context.getString(label)) { quickBuildTaps++ }
		}
	}

	private fun clickAction(): AccessibilityNodeInfo.AccessibilityAction? =
		line.statusText
			.createAccessibilityNodeInfo()
			.actionList
			.singleOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }

	private fun measuredHeight(): Int {
		line.root.measure(
			MeasureSpec.makeMeasureSpec(WIDTH_PX, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
		)
		return line.statusText.measuredHeight
	}

	@Test
	fun `a tap on the refused line runs the Quick Build button's tap`() {
		show(quickBuildStatusBarUpdate(QuickBuildStatus.Provisioning(), refused))
		assertThat(line.statusText.text.toString())
			.isEqualTo(context.getString(Res.string.quick_build_status_off_sqlite_debugger))

		assertThat(line.statusText.performClick()).isTrue()

		assertThat(quickBuildTaps).isEqualTo(1)
	}

	@Test
	fun `the refused line is a labelled button for TalkBack and the keyboard`() {
		show(quickBuildStatusBarUpdate(null, refused))

		assertThat(line.statusText.isClickable).isTrue()
		assertThat(line.statusText.isFocusable).isTrue()
		assertThat(clickAction()?.label?.toString())
			.isEqualTo(context.getString(Res.string.quick_build_status_off_sqlite_debugger_action))
		// TalkBack's double-tap is an accessibility click, not a touch.
		assertThat(line.statusText.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)).isTrue()
		assertThat(quickBuildTaps).isEqualTo(1)
	}

	@Test
	fun `the tap leaves the line's size alone`() {
		show(quickBuildStatusBarUpdate(QuickBuildStatus.Provisioning(), refused))
		val withTap = measuredHeight()
		bindStatusLineTap(line.statusText, null, null)

		assertThat(withTap).isEqualTo(measuredHeight())
	}

	@Test
	fun `the next line is plain text again, so a tap on it reaches the header`() {
		show(quickBuildStatusBarUpdate(QuickBuildStatus.Provisioning(), refused))
		show(quickBuildStatusBarUpdate(refused, QuickBuildStatus.Provisioning()))

		assertThat(line.statusText.isClickable).isFalse()
		assertThat(line.statusText.isFocusable).isFalse()
		assertThat(clickAction()).isNull()
		assertThat(line.statusText.performClick()).isFalse()
		assertThat(quickBuildTaps).isEqualTo(0)
	}

	private companion object {
		const val WIDTH_PX = 600
	}
}
