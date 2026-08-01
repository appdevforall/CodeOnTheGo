package com.itsaky.androidide.quickbuild.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The logging guard's contract: logging must never alter behavior. In JVM unit tests android.util.Log is the unmocked stub and throws on every call, so each assertion below proves the guard actually swallowed a real throw - remove any try/catch in RuntimeLog and its test here goes red.
 */
class RuntimeLogTest {

	@Test
	void debugSwallowsTheLogThrow() {
		assertDoesNotThrow(() -> RuntimeLog.d("debug message"));
	}

	@Test
	void environmentSanityTheLogStubActuallyThrows() {
		// Self-validation: if Log stopped throwing here (e.g. returnDefaultValues flipped
		// on), the no-throw assertions below would pass vacuously. Keep this canary.
		assertThrows(Throwable.class, () -> android.util.Log.d(RuntimeLog.TAG, "canary"));
	}

	@Test
	void errorSwallowsTheLogThrow() {
		assertDoesNotThrow(() -> RuntimeLog.e("error message", new RuntimeException("cause")));
	}

	@Test
	void infoSwallowsTheLogThrow() {
		assertDoesNotThrow(() -> RuntimeLog.i("info message"));
	}

	@Test
	void warnSwallowsTheLogThrow() {
		assertDoesNotThrow(() -> RuntimeLog.w("warn message"));
	}

	@Test
	void warnWithThrowableSwallowsTheLogThrow() {
		assertDoesNotThrow(() -> RuntimeLog.w("warn message", new RuntimeException("cause")));
	}
}
