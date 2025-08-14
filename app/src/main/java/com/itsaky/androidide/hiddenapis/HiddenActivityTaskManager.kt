package com.itsaky.androidide.hiddenapis

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

object HiddenActivityTaskManager : HiddenApisBase() {

	lateinit var ActivityTaskManager: Class<*>
	lateinit var ActivityTaskManager_getService: Method

	lateinit var ProfilerInfo: Class<*>

	lateinit var IApplicationThread: Class<*>

	lateinit var IActivityTaskManager: Class<*>
	lateinit var IActivityTaskManager_startActivity: Method

	@SuppressLint("PrivateApi")
	override fun init() {
		ActivityTaskManager = Class.forName("android.app.ActivityTaskManager")
		ActivityTaskManager_getService =
			HiddenApiBypass.getDeclaredMethod(ActivityTaskManager, "getService")

		ProfilerInfo = Class.forName("android.app.ProfilerInfo")

		IApplicationThread = Class.forName("android.app.IApplicationThread")

		IActivityTaskManager = Class.forName("android.app.IActivityTaskManager")
		IActivityTaskManager_startActivity = HiddenApiBypass.getDeclaredMethod(
			IActivityTaskManager,
			"startActivity",
			IApplicationThread,
			String::class.java,
			String::class.java,
			Intent::class.java,
			String::class.java,
			IBinder::class.java,
			String::class.java,
			Int::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			ProfilerInfo,
			Bundle::class.java
		)
	}

	fun getService(): Any? = initAndDo {
		ActivityTaskManager_getService.invoke(null)
	}
}