package com.itsaky.androidide.handlers

import android.content.Intent
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.eventbus.events.editor.ReportCaughtExceptionEvent
import io.sentry.Sentry
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

const val EXIT_CODE_CRASH = 1

class CrashEventSubscriber {
	private val log = LoggerFactory.getLogger(CrashEventSubscriber::class.java)

	@Suppress("unused")
	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onReportCaughtException(ev: ReportCaughtExceptionEvent) {
		try {
			Sentry.configureScope { scope ->
				ev.extras.forEach { (k, v) -> scope.setTag(k, v) }
				ev.message?.let { scope.setExtra("message", it) }
			}
			Sentry.captureException(ev.throwable)

			try {
				val intent = Intent()
				intent.action = CrashHandlerActivity.REPORT_ACTION
				intent.putExtra(CrashHandlerActivity.TRACE_KEY, ev.throwable.stackTraceToString())
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				IDEApplication.instance.startActivity(intent)

				exitProcess(EXIT_CODE_CRASH)
			} catch (error: Throwable) {
				log.error("Unable to show crash handler activity", error)
			}
		} catch (t: Throwable) {
			log.error("Failed to forward exception to GlitchTip", t)
		}
	}
}
