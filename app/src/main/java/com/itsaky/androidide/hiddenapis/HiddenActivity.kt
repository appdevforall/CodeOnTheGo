package com.itsaky.androidide.hiddenapis

import android.app.Activity
import android.app.Instrumentation
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

@Suppress("ktlint:standard:property-naming")
object HiddenActivity : HiddenApisBase() {
	private val logger = LoggerFactory.getLogger(HiddenActivity::class.java)

	lateinit var Activity: Class<*>
	lateinit var Activity_mEmbeddedID: Field
	lateinit var Activity_mInstrumentation: Field
	lateinit var Activity_mMainThread: Field
	lateinit var Activity_mToken: Field

	@get:Throws(ReflectiveOperationException::class)
	val Activity.mEmbeddedID: String?
		get() = initAndDo { Activity_mEmbeddedID.get(this) as String? }

	@get:Throws(ReflectiveOperationException::class)
	val Activity.mInstrumentation: Instrumentation?
		get() = initAndDo { Activity_mInstrumentation.get(this) as Instrumentation? }

	@get:Throws(ReflectiveOperationException::class)
	val Activity.mMainThread: Any?
		get() = initAndDo { Activity_mMainThread.get(this) }

	@get:Throws(ReflectiveOperationException::class)
	val Activity.mToken: Any?
		get() = initAndDo { Activity_mToken.get(this) }

	override fun init() {
		Activity = Class.forName("android.app.Activity")

		val fields = HiddenApiBypass.getInstanceFields(Activity)
		fields.forEach { field ->
			when (field.name) {
				"mEmbeddedID" -> Activity_mEmbeddedID = field
				"mInstrumentation" -> Activity_mInstrumentation = field
				"mMainThread" -> Activity_mMainThread = field
				"mToken" -> Activity_mToken = field
			}
		}
	}
}
