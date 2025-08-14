package com.itsaky.androidide.hiddenapis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.itsaky.androidide.hiddenapis.HiddenActivity.mEmbeddedID
import com.itsaky.androidide.hiddenapis.HiddenActivity.mMainThread
import com.itsaky.androidide.hiddenapis.HiddenActivity.mToken

fun Activity.startActivityWithFlags(
	intent: Intent,
	options: Bundle? = null,
	startFlags: Int = 0
) {
	val activityTaskManager = HiddenActivityTaskManager.getService()
	HiddenActivityTaskManager.IActivityTaskManager_startActivity.invoke(
		activityTaskManager,
		HiddenActivityThread.getApplicationThread(mMainThread!!),
		opPackageName,
		attributionTag,
		intent,
		intent.resolveTypeIfNeeded(contentResolver),
		mToken,
		mEmbeddedID,
		-1, 0, null, options
	)
}