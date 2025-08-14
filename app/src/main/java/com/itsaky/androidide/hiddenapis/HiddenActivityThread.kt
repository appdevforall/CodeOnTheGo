package com.itsaky.androidide.hiddenapis

import android.annotation.SuppressLint
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

object HiddenActivityThread: HiddenApisBase() {

	lateinit var ActivityThread: Class<*>
	lateinit var ActivityThread_getApplicationThread: Method

	@SuppressLint("PrivateApi")
	override fun init() {
		ActivityThread = Class.forName("android.app.ActivityThread")
		ActivityThread_getApplicationThread = HiddenApiBypass.getDeclaredMethod(ActivityThread, "getApplicationThread")
	}

	fun getApplicationThread(activityThread: Any): Any? = initAndDo {
		ActivityThread_getApplicationThread.invoke(activityThread)
	}
}