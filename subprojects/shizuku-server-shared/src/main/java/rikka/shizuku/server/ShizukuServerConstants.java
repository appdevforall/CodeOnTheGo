package rikka.shizuku.server;

import com.itsaky.androidide.buildinfo.BuildInfo;

/**
 * @author Akash Yadav
 */
public class ShizukuServerConstants {

	public static final String ACTION_FOREGROUND_APP_CHANGED = BuildInfo.PACKAGE_NAME + ".shizuku.ACTION_FOREGROUND_CHANGED";
	public static final String EXTRA_FOREGROUND_UID = BuildInfo.PACKAGE_NAME + ".shizuku.EXTRA_FOREGROUND_UID";
	public static final String EXTRA_FOREGROUND_PID = BuildInfo.PACKAGE_NAME + ".shizuku.EXTRA_FOREGROUND_PID";
	public static final String EXTRA_FOREGROUND_PACKAGES = BuildInfo.PACKAGE_NAME + ".shizuku.EXTRA_FOREGROUND_PACKAGE";

	private ShizukuServerConstants() {
		throw new UnsupportedOperationException();
	}
}
