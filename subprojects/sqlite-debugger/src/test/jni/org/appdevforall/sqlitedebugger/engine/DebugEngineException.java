package org.appdevforall.sqlitedebugger.engine;

/** Stands in for DebugEngineException in DebugEngine.kt: the JNI layer constructs it as (int, String). */
public class DebugEngineException extends Exception {
	public final int sqliteCode;

	public DebugEngineException(int sqliteCode, String message) {
		super(message);
		this.sqliteCode = sqliteCode;
	}
}
