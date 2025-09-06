package rikka.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.buildinfo.BuildInfo;
import moe.shizuku.api.BinderContainer;

/**
 * <p>
 * This provider receives binder from Shizuku server. When app process starts, Shizuku server (it runs under adb/root) will send the binder to client apps with this provider.
 * </p>
 * <p>
 * Add the provider to your manifest like this:
 * </p>
 *
 * <pre class="prettyprint">
 * &lt;manifest&gt;
 *    ...
 *    &lt;application&gt;
 *        ...
 *        &lt;provider
 *            android:name="rikka.shizuku.ShizukuProvider"
 *            android:authorities="${applicationId}.shizuku"
 *            android:exported="true"
 *            android:multiprocess="false"
 *            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
 *        &lt;/provider&gt;
 *        ...
 *    &lt;/application&gt;
 * &lt;/manifest&gt;
 * </pre>
 *
 * <p>
 * There are something needs you attention:
 * </p>
 * <ol>
 * <li><code>android:permission</code> shoule be a permission that granted to Shell (com.android.shell) but not normal apps (e.g., android.permission.INTERACT_ACROSS_USERS_FULL), so that it can only be used by the app itself and Shizuku server.</li>
 * <li><code>android:exported</code> must be <code>true</code> so that the provider can be accessed from Shizuku server runs under adb.</li>
 * <li><code>android:multiprocess</code> must be <code>false</code> since Shizuku server only gets uid when app starts.</li>
 * </ol>
 * <p>
 * </p>
 */
public class ShizukuProvider extends ContentProvider {

	private static final String TAG = "ShizukuProvider";

	// For receive Binder from Shizuku
	public static final String METHOD_SEND_BINDER = "sendBinder";

	// For share Binder between processes
	public static final String METHOD_GET_BINDER = "getBinder";

	public static final String ACTION_BINDER_RECEIVED = BuildInfo.PACKAGE_NAME + ".shizuku.api.action.BINDER_RECEIVED";

	private static final String EXTRA_BINDER = BuildInfo.PACKAGE_NAME + ".shizuku.intent.extra.BINDER";

	@Override
	public void attachInfo(Context context, ProviderInfo info) {
		super.attachInfo(context, info);

		if (info.multiprocess)
			throw new IllegalStateException("android:multiprocess must be false");

		if (!info.exported)
			throw new IllegalStateException("android:exported must be true");

	}

	@Nullable
	@Override
	public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
		if (extras == null) {
			return null;
		}

		extras.setClassLoader(BinderContainer.class.getClassLoader());

		Bundle reply = new Bundle();
		switch (method) {
		case METHOD_SEND_BINDER: {
			handleSendBinder(extras);
			break;
		}
		case METHOD_GET_BINDER: {
			if (!handleGetBinder(reply)) {
				return null;
			}
			break;
		}
		}
		return reply;
	}

	@Override
	public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Nullable
	@Override
	public final String getType(@NonNull Uri uri) {
		return null;
	}

	@Nullable
	@Override
	public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		return null;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	// no other provider methods
	@Nullable
	@Override
	public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
		return null;
	}

	@Override
	public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	private boolean handleGetBinder(@NonNull Bundle reply) {
		// Other processes in the same app can read the provider without permission
		IBinder binder = Shizuku.getBinder();
		if (binder == null || !binder.pingBinder())
			return false;

		reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
		return true;
	}

	private void handleSendBinder(@NonNull Bundle extras) {
		if (Shizuku.pingBinder()) {
			Log.d(TAG, "sendBinder is called when already a living binder");
			return;
		}

		BinderContainer container = extras.getParcelable(EXTRA_BINDER);
		if (container != null && container.binder != null) {
			Log.d(TAG, "binder received");
			Shizuku.onBinderReceived(container.binder, getContext().getPackageName());
		}
	}
}
