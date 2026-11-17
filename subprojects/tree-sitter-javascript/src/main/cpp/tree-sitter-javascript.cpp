// JNI binding for the JavaScript grammar. It does what the binding android-tree-sitter
// generates for its own grammars does: JNI_OnLoad registers
// TSLanguageJavascript.Native.getInstance(), which returns the grammar's TSLanguage.

#include <android/log.h>
#include <jni.h>
#include <stdint.h>

extern "C" const void *tree_sitter_javascript(void);

static const char *const LOG_TAG = "AndroidTreeSitter";
static const char *const NATIVE_CLASS = "com/itsaky/androidide/treesitter/javascript/TSLanguageJavascript$Native";

static jlong getInstance(JNIEnv *, jclass) {
	return (jlong) (intptr_t) tree_sitter_javascript();
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
	JNIEnv *env;
	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to get JNIEnv* from JavaVM: %p", vm);
		return JNI_ERR;
	}

	jclass clazz = env->FindClass(NATIVE_CLASS);
	if (clazz == nullptr) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find class %s", NATIVE_CLASS);
		return JNI_ERR;
	}

	static const JNINativeMethod methods[] = {
		{"getInstance", "()J", reinterpret_cast<void *>(getInstance)},
	};
	jint rc = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]));
	env->DeleteLocalRef(clazz);
	if (rc != JNI_OK) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to register native methods for %s", NATIVE_CLASS);
		return JNI_ERR;
	}

	return JNI_VERSION_1_6;
}
