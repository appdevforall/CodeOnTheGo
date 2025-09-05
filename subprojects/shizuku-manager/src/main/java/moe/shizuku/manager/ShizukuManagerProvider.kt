package moe.shizuku.manager

import android.os.Bundle
import androidx.core.os.bundleOf
import com.itsaky.androidide.buildinfo.BuildInfo
import moe.shizuku.api.BinderContainer
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ShizukuManagerProvider : ShizukuProvider() {
	companion object {
		private val logger = LoggerFactory.getLogger(ShizukuManagerProvider::class.java)
		private const val EXTRA_BINDER = "${BuildInfo.PACKAGE_NAME}.shizuku.intent.extra.BINDER"
		private const val METHOD_SEND_USER_SERVICE = "sendUserService"
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
				extras.classLoader = BinderContainer::class.java.classLoader

				val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
				val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

				val countDownLatch = CountDownLatch(1)
				var reply: Bundle? = Bundle()

				val listener =
					object : Shizuku.OnBinderReceivedListener {
						override fun onBinderReceived() {
							try {
								Shizuku.attachUserService(
									binder,
									bundleOf(
										USER_SERVICE_ARG_TOKEN to token,
									),
								)
								reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
							} catch (e: Throwable) {
								logger.error("attachUserService $token", e)
								reply = null
							}

							Shizuku.removeBinderReceivedListener(this)

							countDownLatch.countDown()
						}
					}

				Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

				return try {
					countDownLatch.await(5, TimeUnit.SECONDS)
					reply
				} catch (e: TimeoutException) {
					logger.error("Binder not received in 5s", e)
					null
				}
			} catch (e: Throwable) {
				logger.error("sendUserService", e)
				null
			}
		} else {
			super.call(method, arg, extras)
		}
	}
}
