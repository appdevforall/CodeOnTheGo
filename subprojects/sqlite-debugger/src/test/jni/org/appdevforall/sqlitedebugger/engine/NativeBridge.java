package org.appdevforall.sqlitedebugger.engine;

/**
 * Stands in for NativeBridge.kt in the host JNI test (test.sh): the same class name and native
 * signatures, which sqlitedbg_jni.c registers by name in JNI_OnLoad, so a Java-only toolchain can
 * run the real C. test.sh checks that the native names here match the ones in NativeBridge.kt.
 */
public final class NativeBridge {
	private NativeBridge() {}

	public static native int install();

	public static native Object[] connections();

	public static native void readEvents(long after, byte[] buf, long[] out);

	public static native void setClientConnected(boolean connected);

	public static native long openServer(byte[] path, boolean writable) throws DebugEngineException;

	public static native Object[] query(long handle, String sql, Object[] args, long offset, int maxRows, int maxTextBytes,
			int maxBlobBytes, int maxTotalBytes, long deadlineMillis)
			throws DebugEngineException;

	public static native void close(long handle);
}
