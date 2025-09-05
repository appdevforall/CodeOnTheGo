package moe.shizuku.manager

import com.itsaky.androidide.app.BaseApplication
import java.io.File

object ShizukuStarter {
	private val starterFile =
		File(BaseApplication.getBaseInstance().applicationInfo.nativeLibraryDir, "libshizuku.so")

	val userCommand: String = starterFile.absolutePath

	val adbCommand = "adb shell $userCommand"

	val internalCommand = "$userCommand --apk=${BaseApplication.getBaseInstance().applicationInfo.sourceDir}"
}
