package app.payload;
public final class Native {
    static { System.loadLibrary("payloadjni"); }
    public static native String greet();
    public static native int add(int a, int b);
}
