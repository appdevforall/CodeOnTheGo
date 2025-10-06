package moe.shizuku.manager.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.os.RemoteException
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.hidden.compat.util.SystemServiceBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuSystemApis {
	init {
		SystemServiceBinder.setOnGetBinderListener {
			return@setOnGetBinderListener ShizukuBinderWrapper(it)
		}
	}

	private val users = arrayListOf<UserInfoCompat>()

	private fun getUsers(): List<UserInfoCompat> {
		// COTG Changed: Limit to single user
		return arrayListOf(UserInfoCompat(UserHandleCompat.myUserId(), "Owner"))
	}

	fun getUsers(useCache: Boolean = true): List<UserInfoCompat> {
		synchronized(users) {
			if (!useCache || users.isEmpty()) {
				users.clear()
				users.addAll(getUsers())
			}
			return users
		}
	}

	fun getUserInfo(userId: Int): UserInfoCompat =
		getUsers(useCache = true).firstOrNull { it.id == userId } ?: UserInfoCompat(
			UserHandleCompat.myUserId(),
			"Unknown",
		)

	fun getInstalledPackages(
		flags: Long,
		userId: Int,
	): List<PackageInfo> {
		return if (!Shizuku.pingBinder()) {
			ArrayList()
		} else {
			try {
				val listSlice: ParceledListSlice<PackageInfo>? =
					PackageManagerApis.getInstalledPackages(
						flags,
						userId,
					)
				return if (listSlice != null) {
					listSlice.list
				} else {
					ArrayList()
				}
			} catch (tr: RemoteException) {
				throw RuntimeException(tr.message, tr)
			}
		}
	}

	fun checkPermission(
		permName: String,
		pkgName: String,
		userId: Int,
	): Int =
		if (!Shizuku.pingBinder()) {
			PackageManager.PERMISSION_DENIED
		} else {
			try {
				PermissionManagerApis.checkPermission(permName, pkgName, userId)
			} catch (tr: RemoteException) {
				throw RuntimeException(tr.message, tr)
			}
		}

	fun grantRuntimePermission(
		packageName: String,
		permissionName: String,
		userId: Int,
	) {
		if (!Shizuku.pingBinder()) {
			return
		}
		try {
			PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
		} catch (tr: RemoteException) {
			throw RuntimeException(tr.message, tr)
		}
	}

	fun revokeRuntimePermission(
		packageName: String,
		permissionName: String,
		userId: Int,
	) {
		if (!Shizuku.pingBinder()) {
			return
		}
		try {
			PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
		} catch (tr: RemoteException) {
			throw RuntimeException(tr.message, tr)
		}
	}
}
