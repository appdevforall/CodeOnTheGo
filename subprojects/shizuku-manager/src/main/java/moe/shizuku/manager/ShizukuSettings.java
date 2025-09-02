package moe.shizuku.manager;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import java.lang.annotation.Retention;
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;

public class ShizukuSettings {

	public static final String NAME = "settings";
	public static final String KEEP_START_ON_BOOT = "start_on_boot";

	private static SharedPreferences sPreferences;

	@LaunchMethod
	public static int getLastLaunchMode() {
		return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
	}

	public static SharedPreferences getPreferences() {
		return sPreferences;
	}

	public static void initialize(Context context) {
		if (sPreferences == null) {
			sPreferences = getSettingsStorageContext(context)
					.getSharedPreferences(NAME, Context.MODE_PRIVATE);
		}
	}

	public static void setLastLaunchMode(@LaunchMethod int method) {
		getPreferences().edit().putInt("mode", method).apply();
	}

	@NonNull
	private static Context getSettingsStorageContext(@NonNull Context context) {
		return new ContextWrapper(context.createDeviceProtectedStorageContext()) {
			@Override
			public SharedPreferences getSharedPreferences(String name, int mode) {
				try {
					return super.getSharedPreferences(name, mode);
				} catch (IllegalStateException e) {
					// SharedPreferences in credential encrypted storage are not available until after user is unlocked
					return new EmptySharedPreferencesImpl();
				}
			}
		};
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
