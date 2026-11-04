# The starter launches the server by class name through app_process (starter.cpp), so R8
# sees no caller and strips it; pairing then succeeds but the server dies with
# ClassNotFoundException.
-keep class rikka.shizuku.server.ShizukuService { public static void main(java.lang.String[]); }

# The framework calls these observer callbacks over binder, so R8 sees no caller and drops
# them; the server then dies with AbstractMethodError on the first callback. The rules target
# the adapters because their hidden-API superclasses are compile-only and invisible to R8.
-keep class rikka.hidden.compat.adapter.** { public *; }
-keep class * extends rikka.hidden.compat.adapter.ProcessObserverAdapter { public *; }
-keep class * extends rikka.hidden.compat.adapter.UidObserverAdapter { public *; }
