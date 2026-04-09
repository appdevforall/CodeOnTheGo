package com.itsaky.androidide.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

private const val TABLET_MIN_SMALLEST_WIDTH_DP = 500

data class DeviceFormFactor(
    val isTablet: Boolean,
    val isDexMode: Boolean,
) {
    val isLargeScreenLike: Boolean
        get() = isTablet || isDexMode
}

object DeviceFormFactorUtils {

    fun getCurrent(context: Context): DeviceFormFactor {
        return DeviceFormFactor(
            isTablet = isTablet(context),
            isDexMode = isDexMode(context),
        )
    }

    fun isDexMode(context: Context): Boolean {
        val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiModeType == Configuration.UI_MODE_TYPE_DESK) {
            return true
        }

        if (isSamsungDexModeModern(context)) {
            return true
        }

        return isSamsungDexModeLegacy(context.resources.configuration)
    }

    fun isTablet(context: Context): Boolean {
        if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) {
            return true
        }

        return isPhysicalLargeScreen(context)
    }

    private fun isPhysicalLargeScreen(context: Context): Boolean {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val smallestPhysicalWidthDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.maximumWindowMetrics
                val density = context.resources.configuration.densityDpi / 160f
                val widthDp = metrics.bounds.width() / density
                val heightDp = metrics.bounds.height() / density
                widthDp.coerceAtMost(heightDp)
            } else {
                val display = windowManager.defaultDisplay
                val realMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(realMetrics)
                val widthDp = realMetrics.widthPixels / realMetrics.density
                val heightDp = realMetrics.heightPixels / realMetrics.density
                widthDp.coerceAtMost(heightDp)
            }

            smallestPhysicalWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
        } catch (_: Exception) {
            false
        }
    }

    private fun isSamsungDexModeModern(context: Context): Boolean {
        return try {
            val desktopModeManager = context.applicationContext.getSystemService("desktopmode") ?: return false

            val getDesktopModeStateMethod = desktopModeManager.javaClass.getDeclaredMethod("getDesktopModeState")
            val desktopModeState = getDesktopModeStateMethod.invoke(desktopModeManager) ?: return false
            val desktopModeStateClass = desktopModeState.javaClass

            val getEnabledMethod = desktopModeStateClass.getDeclaredMethod("getEnabled")
            val enabled = getEnabledMethod.invoke(desktopModeState) as Int
            val enabledConstant = desktopModeStateClass.getDeclaredField("ENABLED").getInt(desktopModeStateClass)

            enabled == enabledConstant
        } catch (_: Exception) {
            false
        }
    }

    private fun isSamsungDexModeLegacy(configuration: Configuration): Boolean {
        val enabledValue = readSamsungDesktopModeValue(
            target = configuration.javaClass,
            fieldName = "SEM_DESKTOP_MODE_ENABLED",
            targetClass = configuration.javaClass,
        ) ?: return false

        val currentValue = readSamsungDesktopModeValue(
            target = configuration,
            fieldName = "semDesktopModeEnabled",
            targetClass = configuration.javaClass,
        ) ?: return false

        return currentValue == enabledValue
    }

    private fun readSamsungDesktopModeValue(
        target: Any?,
        fieldName: String,
        targetClass: Class<*>,
    ): Int? {
        return runCatching {
            targetClass.getField(fieldName).getInt(target)
        }.recoverCatching {
            targetClass.getDeclaredField(fieldName).apply {
                isAccessible = true
            }.getInt(target)
        }.getOrNull()
    }
}
