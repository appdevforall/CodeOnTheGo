package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LoaderRouterTest {

	/** Serves exactly one class name (with a stand-in Class object); CNFE for everything else its parent chain misses. */
	private static final class ServingLoader extends ClassLoader {

		private final String served;

		ServingLoader(String served) {
			// Bootstrap parent: framework-style names still resolve parent-first.
			super(null);
			this.served = served;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (name.equals(served)) {
				return Runnable.class;
			}
			throw new ClassNotFoundException(name);
		}
	}

	private static final class BrokenLoader extends ClassLoader {

		BrokenLoader() {
			super(null);
		}

		@Override
		protected Class<?> findClass(String name) {
			throw new NoClassDefFoundError("corrupt payload entry: " + name);
		}
	}

	private final ClassLoader defaultLoader = getClass().getClassLoader();

	@Test
	void fallsBackWhenClassNotInPayloadChain() {
		ClassLoader payload = new ServingLoader("com.example.Other");
		assertThat(LoaderRouter.pick(defaultLoader, payload, "com.example.UserActivity"))
				.isSameInstanceAs(defaultLoader);
	}

	@Test
	void fallsBackWhenNoPayloadIsLive() {
		// Inert runtime (no baseline loaded): the app must behave like a normal app.
		assertThat(LoaderRouter.pick(defaultLoader, null, "com.example.UserActivity"))
				.isSameInstanceAs(defaultLoader);
	}

	@Test
	void nonCnfeProbeFailuresPropagate() {
		// The factory's own catch handles these by RE-INSTANTIATING through the
		// default loader; swallowing here would weaken that fallback. Pin it.
		ClassLoader payload = new BrokenLoader();
		assertThrows(NoClassDefFoundError.class,
				() -> LoaderRouter.pick(defaultLoader, payload, "com.example.UserActivity"));
	}

	@Test
	void payloadWinsWhenBothLoadersServeTheClass() {
		// Never-stale: whenever the payload chain can serve the class, it must be
		// the one that does - even for names the default loader also knows.
		ClassLoader payload = new ServingLoader("java.lang.Runnable");
		assertThat(LoaderRouter.pick(defaultLoader, payload, "java.lang.Runnable"))
				.isSameInstanceAs(payload);
	}

	@Test
	void picksPayloadWhenItServesTheClass() {
		ClassLoader payload = new ServingLoader("com.example.UserActivity");
		assertThat(LoaderRouter.pick(defaultLoader, payload, "com.example.UserActivity"))
				.isSameInstanceAs(payload);
	}
}
