package com.itsaky.androidide.utils

import android.app.Activity
import android.content.Context
import android.content.Intent


fun Intent.applyMultiWindowFlags(context: Context): Intent = apply {
    val formFactor = DeviceFormFactorUtils.getCurrent(context)

    if (formFactor.isLargeScreenLike) {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )
    } else if (context !is Activity) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
