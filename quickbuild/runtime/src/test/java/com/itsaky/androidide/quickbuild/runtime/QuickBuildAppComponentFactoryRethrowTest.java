package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * What reaches the user when a component cannot be instantiated from either loader.
 *
 * User classes exist only in the payload dex, so the default-loader fallback almost always ends in a {@code ClassNotFoundException} - surfacing that one would put it in the crash dialog and the crash reporter while the real cause (a payload class whose static init threw, a stale-payload {@code NoSuchFieldError}) stayed only in logcat.
 */
class QuickBuildAppComponentFactoryRethrowTest {

	@Test
	void aCheckedPayloadFailureKeepsItsOwnType() {
		InstantiationException payloadError = new InstantiationException("no public no-arg constructor");

		InstantiationException thrown = assertThrows(
				InstantiationException.class,
				() -> QuickBuildAppComponentFactory.rethrowPayloadFailure(
						payloadError, new ClassNotFoundException("com.example.UserService")));

		assertThat(thrown).isSameInstanceAs(payloadError);
	}

	@Test
	void aLinkageErrorIsNotFatal() {
		// The stale-payload case the default-loader fallback exists for, so it must fall
		// through to the retry rather than being rethrown here.
		QuickBuildAppComponentFactory.rethrowIfFatal(new NoSuchFieldError("field removed by a stale payload"));
	}

	@Test
	void aRuntimePayloadFailurePropagatesUnwrapped() {
		NoSuchFieldError payloadError = new NoSuchFieldError("field removed by a stale payload");

		assertThat(assertThrows(
				NoSuchFieldError.class,
				() -> QuickBuildAppComponentFactory.rethrowPayloadFailure(
						payloadError, new ClassNotFoundException("com.example.UserProvider"))))
				.isSameInstanceAs(payloadError);
	}

	@Test
	void aThrowableNoSignatureAllowsIsWrappedWithTheCauseIntact() throws Exception {
		Throwable payloadError = new Throwable("some other checked throwable");

		RuntimeException wrapper = QuickBuildAppComponentFactory.rethrowPayloadFailure(
				payloadError, new ClassNotFoundException("com.example.UserReceiver"));

		assertThat(wrapper.getCause()).isSameInstanceAs(payloadError);
	}

	@Test
	void aVirtualMachineErrorIsRethrownRatherThanRetried() {
		OutOfMemoryError fatal = new OutOfMemoryError("payload dex would not fit");

		// Retrying the same construction after this would allocate again in exactly the state
		// that cannot afford it, and a retry that happened to succeed would swallow it entirely.
		assertThat(assertThrows(
				OutOfMemoryError.class, () -> QuickBuildAppComponentFactory.rethrowIfFatal(fatal)))
				.isSameInstanceAs(fatal);
	}

	@Test
	void oneThrowableAsBothFailuresDoesNotBlowUpOnSelfSuppression() {
		RuntimeException error = new RuntimeException("the same instance twice");

		assertThat(assertThrows(
				RuntimeException.class,
				() -> QuickBuildAppComponentFactory.rethrowPayloadFailure(error, error)))
				.isSameInstanceAs(error);
	}

	@Test
	void theOriginalPayloadFailurePropagatesRatherThanTheFallbacksClassNotFound() {
		ExceptionInInitializerError payloadError = new ExceptionInInitializerError("payload static init threw");
		ClassNotFoundException fallbackError = new ClassNotFoundException("com.example.UserActivity");

		ExceptionInInitializerError thrown = assertThrows(
				ExceptionInInitializerError.class,
				() -> QuickBuildAppComponentFactory.rethrowPayloadFailure(payloadError, fallbackError));

		assertThat(thrown).isSameInstanceAs(payloadError);
		// The fallback failure is still reachable - kept, just not promoted over the cause.
		assertThat(thrown.getSuppressed()).asList().containsExactly(fallbackError);
	}
}
