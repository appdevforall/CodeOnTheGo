package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import java.util.List;

/**
 * @author Akash Yadav
 */
public interface IActivityManager extends IInterface {

	/**
	 * Android 8
	 */
	boolean dumpHeap(String process, int userId, boolean managed, String path, ParcelFileDescriptor fd);

	/**
	 * Android 9
	 */
	boolean dumpHeap(String process, int userId, boolean managed, boolean mallocInfo, boolean runGc, String path, ParcelFileDescriptor fd);

	/**
	 * Android 10, 11, 12, 13, 14
	 */
	boolean dumpHeap(String process, int userId, boolean managed, boolean mallocInfo, boolean runGc, String path, ParcelFileDescriptor fd, RemoteCallback finishCallback);

	/**
	 * Android 15
	 */
	boolean dumpHeap(String process, int userId, boolean managed, boolean mallocInfo, boolean runGc, String dumpBitmaps, String path, ParcelFileDescriptor fd, RemoteCallback finishCallback);

	/**
	 * Snapshot of currently running processes. Rikka's {@code ActivityManagerApis} does not wrap this, so it is declared here to seed the live process list.
	 */
	List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses();

	abstract class Stub extends Binder implements IActivityManager {
		public static IActivityManager asInterface(IBinder obj) {
			throw new RuntimeException("STUB");
		}
	}
}
