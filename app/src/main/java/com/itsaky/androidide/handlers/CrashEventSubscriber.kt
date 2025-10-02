package com.itsaky.androidide.handlers

import android.content.Intent
import com.blankj.utilcode.util.ActivityUtils.startActivity
import com.blankj.utilcode.util.ThrowableUtils.getFullStackTrace
import com.itsaky.androidide.activities.CrashHandlerActivity
import com.itsaky.androidide.eventbus.events.editor.ReportCaughtExceptionEvent
import io.sentry.Sentry
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

const val EXIT_CODE_CRASH = 1
class CrashEventSubscriber {
    private val log = LoggerFactory.getLogger(CrashEventSubscriber::class.java)

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
                intent.putExtra(CrashHandlerActivity.TRACE_KEY, getFullStackTrace(ev.throwable))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)

                exitProcess(EXIT_CODE_CRASH)
            } catch (error: Throwable) {
                log.error("Unable to show crash handler activity", error)
            }
        } catch (t: Throwable) {
            log.error("Failed to forward exception to Sentry", t)
        }
    }
}