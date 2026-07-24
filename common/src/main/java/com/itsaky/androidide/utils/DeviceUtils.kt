package com.itsaky.androidide.utils

import android.annotation.SuppressLint
import android.os.Build
import android.text.TextUtils
import org.slf4j.LoggerFactory
import java.io.File

object DeviceUtils {
	private val logger = LoggerFactory.getLogger(DeviceUtils::class.java)

	private val ROOT_INDICATOR_PATHS =
		arrayOf(
			"/system/bin/",
			"/system/xbin/",
			"/sbin/",
			"/system/sd/xbin/",
			"/system/bin/failsafe/",
			"/data/local/xbin/",
			"/data/local/bin/",
			"/data/local/",
		)

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

	fun getManufacturer(): String = Build.MANUFACTURER

	fun getModel(): String = Build.MODEL?.trim()?.replace("\\s*".toRegex(), "") ?: ""

	/**
	 * Heuristic check for whether the app is running on an emulator, based on common
	 * build-fingerprint indicators used by AVD/Genymotion images.
	 */
	fun isEmulator(): Boolean =
		Build.FINGERPRINT.startsWith("generic") ||
			Build.FINGERPRINT.startsWith("unknown") ||
			Build.MODEL.contains("google_sdk") ||
			Build.MODEL.contains("Emulator") ||
			Build.MODEL.contains("Android SDK built for") ||
			Build.MANUFACTURER.contains("Genymotion") ||
			(Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
			Build.PRODUCT == "google_sdk"

	/**
	 * Heuristic check for whether the device is rooted, based on the presence of an `su`
	 * binary in common locations.
	 */
	fun isDeviceRooted(): Boolean = ROOT_INDICATOR_PATHS.any { File(it, "su").exists() }
}
