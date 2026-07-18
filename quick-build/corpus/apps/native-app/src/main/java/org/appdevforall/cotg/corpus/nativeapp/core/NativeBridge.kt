package org.appdevforall.cotg.corpus.nativeapp.core

/** Thin JNI wrapper over the bundled `libnativestub.so` (see
 * src/main/jniLibs/arm64-v8a/). Compiles fine without the .so present - `external fun`
 * only declares the JNI binding, resolved at classload/runtime, not at compile time. */
class NativeBridge {
	companion object {
		init {
			System.loadLibrary("nativestub")
		}
	}

	external fun greet(): String
}
