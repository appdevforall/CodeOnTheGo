package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import com.itsaky.androidide.buildinfo.BuildInfo
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {
	private val logger = LoggerFactory.getLogger(ShellBinderRequestHandler::class.java)

	const val ACTION_REQUEST_BINDER = "${BuildInfo.PACKAGE_NAME}.shizuku.intent.action.REQUEST_BINDER"

	fun handleRequest(
		context: Context,
		intent: Intent,
	): Boolean {
		if (intent.action != ACTION_REQUEST_BINDER) {
			return false
		}

		val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
		val shizukuBinder = Shizuku.getBinder()
		if (shizukuBinder == null) {
			logger.warn("Binder not received or Shizuku service not running")
		}

		val data = Parcel.obtain()
		return try {
			data.writeStrongBinder(shizukuBinder)
			data.writeString(context.applicationInfo.sourceDir)
			binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
			true
		} catch (e: Throwable) {
			e.printStackTrace()
			false
		} finally {
			data.recycle()
		}
	}
}
