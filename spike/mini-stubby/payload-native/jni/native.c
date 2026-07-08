#include <jni.h>
#include <string.h>

JNIEXPORT jstring JNICALL
Java_app_payload_Native_greet(JNIEnv* env, jclass clazz) {
    return (*env)->NewStringUTF(env, "Hello from libpayloadjni.so (arm64 JNI)");
}

JNIEXPORT jint JNICALL
Java_app_payload_Native_add(JNIEnv* env, jclass clazz, jint a, jint b) {
    return a + b;
}
