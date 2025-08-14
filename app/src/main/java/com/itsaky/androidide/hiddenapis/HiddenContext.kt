package com.itsaky.androidide.hiddenapis

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

@Suppress("ktlint:standard:property-naming")
object HiddenContext : HiddenApisBase() {
	private lateinit var ContextImpl: Class<*>
	private lateinit var ContextImpl_getOuterContext: Method
	private lateinit var ContextImpl_applyLaunchDisplayIfNeeded: Method

	@SuppressLint("PrivateApi")
	override fun init() {
		ContextImpl = Class.forName("android.app.ContextImpl")
		ContextImpl_getOuterContext = HiddenApiBypass.getDeclaredMethod(ContextImpl, "getOuterContext")
		ContextImpl_applyLaunchDisplayIfNeeded =
			HiddenApiBypass.getDeclaredMethod(
				ContextImpl,
				"applyLaunchDisplayIfNeeded",
				Bundle::class.java,
			)
	}

	fun Context.getOuterContext(): Context? =
		initAndDo {
			ContextImpl_getOuterContext.invoke(this) as Context?
		}

	fun Context.applyLaunchDisplayIfNeeded(bundle: Bundle?): Bundle? =
		initAndDo {
			ContextImpl_applyLaunchDisplayIfNeeded.invoke(this, bundle) as Bundle?
		}
}
