package org.appdevforall.cotg.profiler.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single running process exposed across the privileged-service binder.
 *
 * [profileable]/[debuggable] are static, package-level attributes; [appLabel] may be null for
 * processes that are not backed by an installed app (native daemons, isolated processes, ...).
 *
 * @author Akash Yadav
 */
@Parcelize
data class ProcessInfo(
	val pid: Int,
	val uid: Int,
	val processName: String,
	val packageName: String?,
	val appLabel: String?,
	val debuggable: Boolean,
	val profileable: Boolean,
) : Parcelable
