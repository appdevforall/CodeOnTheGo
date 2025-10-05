package rikka.hidden.compat

import android.app.IActivityManager

/**
 * @author Akash Yadav
 */
object ActivityManagerAccessor {

	val activityManager: IActivityManager
		get() = Services.activityManager.get()
}