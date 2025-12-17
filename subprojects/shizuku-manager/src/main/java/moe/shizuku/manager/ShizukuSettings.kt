package moe.shizuku.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntDef
import androidx.core.content.edit
import com.itsaky.androidide.app.BaseApplication.Companion.baseInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ShizukuSettings {
	const val NAME: String = "settings"

	@Volatile
	private var preferences: SharedPreferences? = null
	private val mutex = Mutex()

	suspend fun setLastLaunchMode(
		@LaunchMethod mode: Int,
	) {
		getSharedPreferences().edit { putInt("mode", mode) }
	}

	suspend fun getLastLaunchMode(): Int =
		getSharedPreferences().getInt("mode", LaunchMethod.UNKNOWN)

	suspend fun getSharedPreferences(): SharedPreferences {
		initialize()
		return preferences!!
	}

	suspend fun initialize() {
		if (preferences != null) return

		mutex.withLock {
			preferences =
				withContext(Dispatchers.IO) {
					settingsStorageContext
						.getSharedPreferences(NAME, Context.MODE_PRIVATE)
				}
		}
	}

	private val settingsStorageContext: Context
		get() = baseInstance.getSafeContext(true)

	@IntDef(
		LaunchMethod.UNKNOWN,
		LaunchMethod.ROOT,
		LaunchMethod.ADB,
	)
	@Retention(AnnotationRetention.SOURCE)
	annotation class LaunchMethod {
		companion object {
			const val UNKNOWN: Int = -1
			const val ROOT: Int = 0
			const val ADB: Int = 1
		}
	}
}
