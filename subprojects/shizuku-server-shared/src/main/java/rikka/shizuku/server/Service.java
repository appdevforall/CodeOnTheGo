package rikka.shizuku.server;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;
import androidx.annotation.CallSuper;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.util.Logger;

public abstract class Service<UserServiceMgr extends UserServiceManager> extends IShizukuService.Stub {

	protected static final Logger LOGGER = new Logger("ShizukuService");
	private final UserServiceMgr userServiceManager;

	public Service() {
		userServiceManager = onCreateUserServiceManager();
	}

	@Override
	public final int addUserService(IShizukuServiceConnection conn, Bundle options) {
		enforceCallingPermission("addUserService");
		// COTG Changed: removed client manager
		return userServiceManager.addUserService(conn, options, ShizukuApiConstants.SERVER_VERSION);
	}

	@Override
	public void attachUserService(IBinder binder, Bundle options) {
		enforceManagerPermission("attachUserService");
		userServiceManager.attachUserService(binder, options);
	}

	public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
		return false;
	}

	@Override
	public final int checkPermission(String permission) throws RemoteException {
		enforceCallingPermission("checkPermission");
		return PermissionManagerApis.checkPermission(permission, Os.getuid());
	}

	public final void enforceCallingPermission(String func) {
		// COTG Changed: all operations require manager permission
		enforceManagerPermission(func);
	}

	public final void enforceManagerPermission(String func) {
		int callingUid = Binder.getCallingUid();
		int callingPid = Binder.getCallingPid();

		if (callingPid == Os.getpid()) {
			return;
		}

		if (checkCallerManagerPermission(func, callingUid, callingPid)) {
			return;
		}

		String msg = "Permission Denial: " + func + " from pid="
				+ Binder.getCallingPid()
				+ " is not manager ";
		LOGGER.w(msg);
		throw new SecurityException(msg);
	}

	@Override
	public final String getSELinuxContext() {
		enforceCallingPermission("getSELinuxContext");

		try {
			return SELinux.getContext();
		} catch (Throwable tr) {
			throw new IllegalStateException(tr.getMessage());
		}
	}

	@Override
	public final String getSystemProperty(String name, String defaultValue) {
		enforceCallingPermission("getSystemProperty");

		try {
			return SystemProperties.get(name, defaultValue);
		} catch (Throwable tr) {
			throw new IllegalStateException(tr.getMessage());
		}
	}

	@Override
	public final int getUid() {
		enforceCallingPermission("getUid");
		return Os.getuid();
	}

	public final UserServiceMgr getUserServiceManager() {
		return userServiceManager;
	}

	@Override
	public final int getVersion() {
		enforceCallingPermission("getVersion");
		return ShizukuApiConstants.SERVER_VERSION;
	}

	public abstract boolean isManager(int uid);

	@Override
	public final IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
		enforceCallingPermission("newProcess");

		LOGGER.d("newProcess: uid=%d, cmd=%s, env=%s, dir=%s", Binder.getCallingUid(), Arrays.toString(cmd), Arrays.toString(env), dir);

		java.lang.Process process;
		try {
			process = Runtime.getRuntime().exec(cmd, env, dir != null ? new File(dir) : null);
		} catch (IOException e) {
			throw new IllegalStateException(e.getMessage());
		}

		// COTG Changed: removed client manager
		return new RemoteProcessHolder(process, null);
	}

	public abstract UserServiceMgr onCreateUserServiceManager();

	@CallSuper
	@Override
	public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
		if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
			data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
			transactRemote(data, reply, flags);
			return true;
		} else if (code == 14 /* attachApplication <= v12 */) {
			data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
			IBinder binder = data.readStrongBinder();
			String packageName = data.readString();
			Bundle args = new Bundle();
			args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);
			args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1);
			attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
			reply.writeNoException();
			return true;
		}
		return super.onTransact(code, data, reply, flags);
	}

	@Override
	public final int removeUserService(IShizukuServiceConnection conn, Bundle options) {
		enforceCallingPermission("removeUserService");

		return userServiceManager.removeUserService(conn, options);
	}

	@Override
	public final void setSystemProperty(String name, String value) {
		enforceCallingPermission("setSystemProperty");

		try {
			SystemProperties.set(name, value);
		} catch (Throwable tr) {
			throw new IllegalStateException(tr.getMessage());
		}
	}

	public final void transactRemote(Parcel data, Parcel reply, int flags) throws RemoteException {
		enforceCallingPermission("transactRemote");

		IBinder targetBinder = data.readStrongBinder();
		int targetCode = data.readInt();

		LOGGER.d("transact: uid=%d, descriptor=%s, code=%d", Binder.getCallingUid(), targetBinder.getInterfaceDescriptor(), targetCode);
		Parcel newData = Parcel.obtain();
		try {
			newData.appendFrom(data, data.dataPosition(), data.dataAvail());
		} catch (Throwable tr) {
			LOGGER.w(tr, "appendFrom");
			return;
		}
		try {
			long id = Binder.clearCallingIdentity();
			targetBinder.transact(targetCode, newData, reply, flags);
			Binder.restoreCallingIdentity(id);
		} finally {
			newData.recycle();
		}
	}
}
