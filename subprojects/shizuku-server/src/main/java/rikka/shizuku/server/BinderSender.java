package rikka.shizuku.server;

import static android.app.ActivityManagerHidden.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_CACHED;
import static android.app.ActivityManagerHidden.UID_OBSERVER_GONE;
import static android.app.ActivityManagerHidden.UID_OBSERVER_IDLE;

import android.app.ActivityManagerHidden;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.shizuku.server.util.Logger;

public class BinderSender {

	private static final Logger LOGGER = new Logger("BinderSender");

	private static ShizukuService sShizukuService;

	public static void register(ShizukuService shizukuService) {
		sShizukuService = shizukuService;

		try {
			ActivityManagerApis.registerProcessObserver(new ProcessObserver());
		} catch (Throwable tr) {
			LOGGER.e(tr, "registerProcessObserver");
		}

		int flags = UID_OBSERVER_GONE | UID_OBSERVER_IDLE | UID_OBSERVER_ACTIVE | UID_OBSERVER_CACHED;
		try {
			ActivityManagerApis.registerUidObserver(new UidObserver(), flags,
					ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
					null);
		} catch (Throwable tr) {
			LOGGER.e(tr, "registerUidObserver");
		}
	}

	private static void sendBinder() {
		sShizukuService.sendBinderToManager();
	}

	}

	private static class ProcessObserver extends ProcessObserverAdapter {

		private static final List<Integer> PID_LIST = new ArrayList<>();

		@Override
		public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
			LOGGER.d("onForegroundActivitiesChanged: pid=%d, uid=%d, foregroundActivities=%s", pid, uid, foregroundActivities ? "true" : "false");

			synchronized (PID_LIST) {
				if (PID_LIST.contains(pid) || !foregroundActivities) {
					return;
				}
				PID_LIST.add(pid);
			}

			sendBinder();
		}

		@Override
		public void onProcessDied(int pid, int uid) {
			LOGGER.d("onProcessDied: pid=%d, uid=%d", pid, uid);

			synchronized (PID_LIST) {
				int index = PID_LIST.indexOf(pid);
				if (index != -1) {
					PID_LIST.remove(index);
				}
			}
		}

		@Override
		public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
			LOGGER.d("onProcessStateChanged: pid=%d, uid=%d, procState=%d", pid, uid, procState);

			synchronized (PID_LIST) {
				if (PID_LIST.contains(pid)) {
					return;
				}
				PID_LIST.add(pid);
			}

			sendBinder();
		}
	}

	private static class UidObserver extends UidObserverAdapter {

		private static final List<Integer> UID_LIST = new ArrayList<>();

		@Override
		public void onUidActive(int uid) throws RemoteException {
			LOGGER.d("onUidCachedChanged: uid=%d", uid);
			uidStarts(uid);
		}

		@Override
		public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
			LOGGER.d("onUidCachedChanged: uid=%d, cached=%s", uid, Boolean.toString(cached));

			if (!cached) {
				uidStarts(uid);
			}
		}

		@Override
		public void onUidGone(int uid, boolean disabled) throws RemoteException {
			LOGGER.d("onUidGone: uid=%d, disabled=%s", uid, Boolean.toString(disabled));
			uidGone(uid);
		}

		@Override
		public void onUidIdle(int uid, boolean disabled) throws RemoteException {
			LOGGER.d("onUidIdle: uid=%d, disabled=%s", uid, Boolean.toString(disabled));
			uidStarts(uid);
		}

		private void uidGone(int uid) {
			synchronized (UID_LIST) {
				int index = UID_LIST.indexOf(uid);
				if (index != -1) {
					UID_LIST.remove(index);
					LOGGER.v("Uid %d dead", uid);
				}
			}
		}

		private void uidStarts(int uid) throws RemoteException {
			synchronized (UID_LIST) {
				if (UID_LIST.contains(uid)) {
					LOGGER.v("Uid %d already starts", uid);
					return;
				}
				UID_LIST.add(uid);
				LOGGER.v("Uid %d starts", uid);
			}

			sendBinder();
		}
	}
}
