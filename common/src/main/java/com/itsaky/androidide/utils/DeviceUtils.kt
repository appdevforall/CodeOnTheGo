package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.text.TextUtils
import org.slf4j.LoggerFactory

object DeviceUtils {

	private val logger = LoggerFactory.getLogger(DeviceUtils::class.java)

	fun isMiui(): Boolean = !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"))

	@SuppressLint("PrivateApi")
	fun getSystemProperty(key: String?): String? =
		try {
			Class
				.forName("android.os.SystemProperties")
				.getDeclaredMethod("get", String::class.java)
				.invoke(null, key) as String
		} catch (e: Exception) {
			logger.warn("Unable to use SystemProperties.get", e)
			null
		}
}
