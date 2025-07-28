#include <jni.h>


extern "C"
JNIEXPORT jlong JNICALL
Java_com_itsaky_androidide_app_LlmInferenceEngine_initModel(JNIEnv *env, jobject thiz,
                                                            jobject asset_manager,
                                                            jstring model_path) {
    // TODO: implement initModel()
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_itsaky_androidide_app_LlmInferenceEngine_runInference(JNIEnv *env, jobject thiz,
                                                               jstring prompt, jlong context_ptr) {
    // TODO: implement runInference()
}
extern "C"
JNIEXPORT void JNICALL
Java_com_itsaky_androidide_app_LlmInferenceEngine_releaseModel(JNIEnv *env, jobject thiz,
                                                               jlong context_ptr) {
    // TODO: implement releaseModel()
}