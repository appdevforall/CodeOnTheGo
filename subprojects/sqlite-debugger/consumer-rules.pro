# JNI_OnLoad registers these by name, and the native code builds this exception
-keep class org.appdevforall.sqlitedebugger.engine.NativeBridge { *; }
-keep class org.appdevforall.sqlitedebugger.engine.DebugEngineException { <init>(int, java.lang.String); }
