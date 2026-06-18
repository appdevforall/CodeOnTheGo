package moe.shizuku.manager

import android.os.Bundle
import androidx.core.os.bundleOf
import com.itsaky.androidide.buildinfo.BuildInfo
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider

class ShizukuManagerProvider : ShizukuProvider() {
	companion object {
		private val logger = LoggerFactory.getLogger(ShizukuManagerProvider::class.java)
		private const val EXTRA_BINDER = "${BuildInfo.PACKAGE_NAME}.shizuku.intent.extra.BINDER"
		private const val METHOD_SEND_USER_SERVICE = "sendUserService"

		/** How long to wait for the Shizuku binder to be available in this process. */
		private const val BINDER_WAIT_TIMEOUT_MS = 5_000L
		private const val BINDER_WAIT_INTERVAL_MS = 50L
	}

	override fun call(
		method: String,
		arg: String?,
		extras: Bundle?,
	): Bundle? {
		logger.debug("call: {}({}) extras={}", method, arg, extras)
		if (extras == null) return null

		return if (method == METHOD_SEND_USER_SERVICE) {
			try {
				val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
				val binder = extras.getBinder(EXTRA_BINDER) ?: return null

				// Forwarding the user-service binder to the Shizuku server only needs the binder this
				// (app) process already received from the server; it does NOT need `binderReady` (the
				// V13 attach callback), so we must not depend on addBinderReceivedListenerSticky here.
				// The provider can occasionally be called while app startup is still wiring Shizuku up,
				// so poll briefly for the binder instead of failing immediately.
				if (!awaitShizukuBinder()) {
					logger.error("Shizuku binder unavailable; cannot attach user service $token")
					return null
				}

				Shizuku.attachUserService(binder, bundleOf(USER_SERVICE_ARG_TOKEN to token))
				Bundle().apply { putBinder(EXTRA_BINDER, Shizuku.getBinder()) }
			} catch (e: Throwable) {
				logger.error("sendUserService", e)
				null
			}
		} else {
			super.call(method, arg, extras)
		}
	}

	private fun awaitShizukuBinder(): Boolean {
		var waited = 0L
		while (!Shizuku.pingBinder()) {
			if (waited >= BINDER_WAIT_TIMEOUT_MS) return false
			try {
				Thread.sleep(BINDER_WAIT_INTERVAL_MS)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				return false
			}
			waited += BINDER_WAIT_INTERVAL_MS
		}
		return true
	}
}
