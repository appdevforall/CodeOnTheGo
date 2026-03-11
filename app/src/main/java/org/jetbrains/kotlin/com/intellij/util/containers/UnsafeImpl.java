package org.jetbrains.kotlin.com.intellij.util.containers;

import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

@SuppressWarnings("ALL")
public class UnsafeImpl {

    private static final Object unsafe;

    private static final Method putObjectVolatile;
    private static final Method getObjectVolatile;
    private static final Method compareAndSwapObject;
    private static final Method compareAndSwapInt;
    private static final Method compareAndSwapLong;
    private static final Method getAndAddInt;
    private static final Method objectFieldOffset;
    private static final Method arrayIndexScale;
    private static final Method arrayBaseOffset;
//    private static final Method copyMemory;

    private static final String TAG = "UnsafeImpl";

    private static @NotNull Method find(String name, Class<?>... params) throws Exception {
        Log.d(TAG, "find: name=" + name + ", params=" + Arrays.toString(params));
        Method m = HiddenApiBypass.getDeclaredMethod(unsafe.getClass(), name, params);
        m.setAccessible(true);
        return m;
    }

    public static boolean compareAndSwapInt(Object object, long offset, int expected, int value) {
        try {
            return (boolean) compareAndSwapInt.invoke(unsafe, object, offset, expected, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static boolean compareAndSwapLong(@NotNull Object object, long offset, long expected, long value) {
        try {
            return (boolean) compareAndSwapLong.invoke(unsafe, object, offset, expected, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int getAndAddInt(Object object, long offset, int v) {
        try {
            return (int) getAndAddInt.invoke(unsafe, object, offset, v);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object getObjectVolatile(Object object, long offset) {
        try {
            return getObjectVolatile.invoke(unsafe, object, offset);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
        try {
            return (boolean) compareAndSwapObject.invoke(unsafe, o, offset, expected, x);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void putObjectVolatile(Object o, long offset, Object x) {
        try {
            putObjectVolatile.invoke(unsafe, o, offset, x);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static long objectFieldOffset(Field f) {
        try {
            return (long) objectFieldOffset.invoke(unsafe, f);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int arrayIndexScale(Class<?> arrayClass) {
        try {
            return (int) arrayIndexScale.invoke(unsafe, arrayClass);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int arrayBaseOffset(Class<?> arrayClass) {
        try {
            return (int) arrayBaseOffset.invoke(unsafe, arrayClass);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
        throw new UnsupportedOperationException("Not supported on Android!");
    }

    static {
        try {
            unsafe = ReflectionUtil.getUnsafe();
            putObjectVolatile  = find("putObjectVolatile",  Object.class, long.class, Object.class);
            getObjectVolatile  = find("getObjectVolatile",  Object.class, long.class);
            compareAndSwapObject = find("compareAndSwapObject", Object.class, long.class, Object.class, Object.class);
            compareAndSwapInt  = find("compareAndSwapInt",  Object.class, long.class, int.class, int.class);
            compareAndSwapLong = find("compareAndSwapLong", Object.class, long.class, long.class, long.class);
            getAndAddInt       = find("getAndAddInt",       Object.class, long.class, int.class);
            objectFieldOffset  = find("objectFieldOffset",  Field.class);
            arrayBaseOffset    = find("arrayBaseOffset",    Class.class);
            arrayIndexScale    = find("arrayIndexScale",    Class.class);
//            copyMemory         = find("copyMemory",         Object.class, long.class, Object.class, long.class, long.class);
        } catch (Throwable t) {
            throw new Error(t);
        }
    }
}
