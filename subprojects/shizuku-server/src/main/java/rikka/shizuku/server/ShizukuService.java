package rikka.shizuku.server;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.server.ServerConstants.MANAGER_APPLICATION_ID;

import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.ddm.DdmHandleAppName;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.itsaky.androidide.buildinfo.BuildInfo;

import java.util.List;

import moe.shizuku.api.BinderContainer;
import moe.shizuku.common.util.OsUtils;
import moe.shizuku.common.util.UserUtils;
import moe.shizuku.server.IShizukuApplication;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.UserHandleCompat;

public class ShizukuService extends Service<ShizukuUserServiceManager> {

	public static ApplicationInfo getManagerApplicationInfo() {
		return PackageManagerApis.getApplicationInfoNoThrow(MANAGER_APPLICATION_ID, 0, 0);
	}

	public static void main(String[] args) {
		DdmHandleAppName.setAppName("cotg_server", 0);
		Looper.prepareMainLooper();
		new ShizukuService();
		Looper.loop();
	}

	static void sendBinderToManager(ShizukuService binder, int userId) {
		sendBinderToUserApp(binder, MANAGER_APPLICATION_ID, userId);
	}

	static void sendBinderToUserApp(ShizukuService binder, String packageName, int userId) {
		sendBinderToUserApp(binder, packageName, userId, true);
	}

	static void sendBinderToUserApp(ShizukuService binder, String packageName, int userId, boolean retry) {
		final var packageInfo = PackageManagerApis.getPackageInfoNoThrow(packageName, 0, userId);
		if (packageInfo == null || packageInfo.applicationInfo == null) {
			LOGGER.w("package %s in user %d not found", packageName, userId);
			return;
		}

		if (!MANAGER_APPLICATION_ID.equals(packageInfo.packageName) || !binder.isManager(packageInfo.applicationInfo.uid)) {
			LOGGER.w("Cannot send binder to package %s in user %d (not manager)", packageName, userId);
			return;
		}

		try {
			DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
					316/* PowerExemptionManager#REASON_SHELL */, "shell");
			LOGGER.v("Add %d:%s to power save temp whitelist for 30s", userId, packageName);
		} catch (Throwable tr) {
			LOGGER.e(tr, "Failed to add %d:%s to power save temp whitelist", userId, packageName);
		}

		String name = packageName + ".shizuku";
		IContentProvider provider = null;

		/* When we pass IBinder through binder (and really crossed process), the receive side (here is system_server process) will always get a new instance of android.os.BinderProxy.
		 *
		 * In the implementation of getContentProviderExternal and removeContentProviderExternal, received IBinder is used as the key of a HashMap. But hashCode() is not implemented by BinderProxy, so removeContentProviderExternal will never work.
		 *
		 * Luckily, we can pass null. When token is token, count will be used. */
		IBinder token = null;

		try {
			provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
			if (provider == null) {
				LOGGER.e("provider is null %s %d", name, userId);
				return;
			}
			if (!provider.asBinder().pingBinder()) {
				LOGGER.e("provider is dead %s %d", name, userId);

				if (retry) {
					// For unknown reason, sometimes this could happens
					// Kill Shizuku app and try again could work
					ActivityManagerApis.forceStopPackageNoThrow(packageName, userId);
					LOGGER.e("kill %s in user %d and try again", packageName, userId);
					Thread.sleep(1000);
					sendBinderToUserApp(binder, packageName, userId, false);
				}
				return;
			}

			if (!retry) {
				LOGGER.e("retry works");
			}

			Bundle extra = new Bundle();
			extra.putParcelable(BuildInfo.PACKAGE_NAME + ".shizuku.intent.extra.BINDER", new BinderContainer(binder));

			Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
			if (reply != null) {
				LOGGER.i("send binder to user app %s in user %d", packageName, userId);
			} else {
				LOGGER.w("failed to send binder to user app %s in user %d", packageName, userId);
			}
		} catch (Throwable tr) {
			LOGGER.e(tr, "failed send binder to user app %s in user %d", packageName, userId);
		} finally {
			if (provider != null) {
				try {
					ActivityManagerApis.removeContentProviderExternal(name, token);
				} catch (Throwable tr) {
					LOGGER.w(tr, "removeContentProviderExternal");
				}
			}
		}
	}

	private static void sendBinderToManager(ShizukuService binder) {
		for (int userId : UserUtils.getUserIds()) {
			sendBinderToManager(binder, userId);
		}
	}

	private static void waitSystemService(String name) {
		while (ServiceManager.getService(name) == null) {
			try {
				LOGGER.i("service " + name + " is not started, wait 1s.");
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				LOGGER.w(e.getMessage(), e);
			}
		}
	}

	@SuppressWarnings({"FieldCanBeLocal"})
	private final Handler mainHandler = new Handler(Looper.myLooper());

	private final int managerAppId;

	public ShizukuService() {
		super();

		HandlerUtil.setMainHandler(mainHandler);

		LOGGER.i("starting server...");

		waitSystemService("package");
		waitSystemService(Context.ACTIVITY_SERVICE);
		waitSystemService(Context.USER_SERVICE);
		waitSystemService(Context.APP_OPS_SERVICE);

		ApplicationInfo ai = getManagerApplicationInfo();
		if (ai == null) {
			System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
		}

		assert ai != null;
		managerAppId = ai.uid;

		LOGGER.d("manager app: pck=%s uid=%d", ai.packageName, ai.uid);
		LOGGER.d("cotg_server: uid=%d", Process.myUid());

		BinderSender.register(this);
		mainHandler.post(this::sendBinderToManager);
	}

	@Override
	public void attachApplication(IShizukuApplication application, Bundle args) {
		if (application == null || args == null) {
			return;
		}

		String requestPackageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
		if (requestPackageName == null) {
			return;
		}
		int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);

		int callingPid = Binder.getCallingPid();
		int callingUid = Binder.getCallingUid();
		boolean isManager;

		List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid);
		if (!packages.contains(requestPackageName)) {
			LOGGER.w("Request package " + requestPackageName + "does not belong to uid " + callingUid);
			throw new SecurityException("Request package " + requestPackageName + "does not belong to uid " + callingUid);
		}

		isManager = MANAGER_APPLICATION_ID.equals(requestPackageName);

		// verify package name, then verify that the it's the same manager that started this server process
		// manager can have a different UID if it was reinstalled, but the server wasn't stopped for some reason
		if (!isManager || !isManager(callingUid)) {
			String msg = "Permission Denial: attachApplication from pid="
					+ Binder.getCallingPid()
					+ " is not manager ";
			LOGGER.w(msg);
			throw new SecurityException(msg);
		}

		LOGGER.d("attachApplication: %s %d %d", requestPackageName, callingUid, callingPid);

		int replyServerVersion = ShizukuApiConstants.SERVER_VERSION;
		if (apiVersion == -1) {
			// ShizukuBinderWrapper has adapted API v13 in dev.rikka.shizuku:api 12.2.0, however
			// attachApplication in 12.2.0 is still old, so that server treat the client as pre 13.
			// This finally cause transactRemote fails.
			// So we can pass 12 here to pretend we are v12 server.
			replyServerVersion = 12;
		}

		Bundle reply = new Bundle();
		reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
		reply.putInt(BIND_APPLICATION_SERVER_VERSION, replyServerVersion);
		reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
		reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);

		try {
			PermissionManagerApis.grantRuntimePermission(MANAGER_APPLICATION_ID,
					WRITE_SECURE_SETTINGS, UserHandleCompat.getUserId(callingUid));
		} catch (RemoteException e) {
			LOGGER.w(e, "grant WRITE_SECURE_SETTINGS");
		}

		try {
			application.bindApplication(reply);
		} catch (Throwable e) {
			LOGGER.w(e, "attachApplication");
		}
	}

	@Override
	public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
		return isManager(callingUid);
	}

	@Override
	public void dispatchPackageChanged(Intent intent) throws RemoteException {

	}

	@Override
	public void exit() {
		enforceManagerPermission("exit");
		LOGGER.i("exit");
		System.exit(0);
	}

	@Override
	public boolean isHidden(int uid) throws RemoteException {
		return false;
	}

	@Override
	public boolean isManager(int uid) {
		return UserHandleCompat.getAppId(uid) == managerAppId;
	}

	@Override
	public ShizukuUserServiceManager onCreateUserServiceManager() {
		return new ShizukuUserServiceManager();
	}

	void sendBinderToManager() {
		sendBinderToManager(this);
	}

	public void onUidGone(int uid) {
		if (!isManager(uid)) {
			return;
		}

		// manager was either killed or uninstalled
		// if it was uninstalled, we need to exit
		final var managerInfo = getManagerApplicationInfo();
		if (managerInfo == null) {
			LOGGER.w("manager app is uninstalled in user 0, exiting...");
			System.exit(ServerConstants.MANAGER_APP_NOT_FOUND);
		}
	}
}
