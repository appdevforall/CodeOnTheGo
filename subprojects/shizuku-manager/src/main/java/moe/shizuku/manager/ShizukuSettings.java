package moe.shizuku.manager;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.itsaky.androidide.app.BaseApplication;

import java.lang.annotation.Retention;

public class ShizukuSettings {

	public static final String NAME = "settings";

	private static SharedPreferences sPreferences;

	@LaunchMethod
	public static int getLastLaunchMode() {
		return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
	}

	public static SharedPreferences getPreferences() {
		return sPreferences;
	}

	public static void initialize() {
		if (sPreferences == null) {
			sPreferences = getSettingsStorageContext()
					.getSharedPreferences(NAME, Context.MODE_PRIVATE);
		}
	}

	public static void setLastLaunchMode(@LaunchMethod int method) {
		getPreferences().edit().putInt("mode", method).apply();
	}

	@NonNull
	private static Context getSettingsStorageContext() {
		return BaseApplication.getBaseInstance().getSafeContext(true);
	}

	@IntDef({
			LaunchMethod.UNKNOWN,
			LaunchMethod.ROOT,
			LaunchMethod.ADB,
	})
	@Retention(SOURCE)
	public @interface LaunchMethod {
		int UNKNOWN = -1;
		int ROOT = 0;
		int ADB = 1;
	}
}
