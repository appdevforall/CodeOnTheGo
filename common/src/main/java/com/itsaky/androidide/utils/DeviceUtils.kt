package com.itsaky.androidide.utils

import android.text.TextUtils
import com.itsaky.androidide.utils.ApkInstaller.getSystemProperty

object DeviceUtils {
	fun isMiui(): Boolean = !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"))
}
