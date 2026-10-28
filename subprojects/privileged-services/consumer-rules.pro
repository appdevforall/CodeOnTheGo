# Shizuku instantiates user services reflectively from the ComponentName class name
# (UserService.create), so R8 sees no constructor call and empties the class.
-keep class org.appdevforall.cotg.profiler.ProfilerUserService { public <init>(...); }

# The framework calls these observer callbacks over binder, so R8 sees no caller and drops
# them. The rules target the adapters because their hidden-API superclasses are compile-only
# and invisible to R8.
-keep class rikka.hidden.compat.adapter.** { public *; }
-keep class * extends rikka.hidden.compat.adapter.ProcessObserverAdapter { public *; }
