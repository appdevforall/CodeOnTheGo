package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProxySourceGeneratorTest {
	@Test
	fun `generates a subclass in the proxy package`() {
		val source =
			ProxySourceGenerator.generateSource(
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				userClass = "com.example.app.MainActivity",
			)

		assertThat(source).contains("package com.example.app.quickbuild.proxies;")
		assertThat(source)
			.contains("public class Proxy0Activity extends com.example.app.MainActivity {")
	}

	@Test
	fun `activity proxy routes getClassLoader through the payload loader picker`() {
		// without this override, androidx FragmentFactory
		// (Navigation-Component destinations, <fragment> tags) and LayoutInflater custom
		// views resolve classes via context.getClassLoader(), which never sees a
		// payload-only class - crashing every BottomNav/NavDrawer template on launch.
		val source =
			ProxySourceGenerator.generateSource(
				proxyClass = "com.example.app.quickbuild.proxies.Proxy0Activity",
				userClass = "com.example.app.MainActivity",
			)

		assertThat(source).contains("public ClassLoader getClassLoader()")
		assertThat(source).contains(
			"com.itsaky.androidide.quickbuild.runtime.QuickBuildClassLoaders" +
				".forActivity(super.getClassLoader());",
		)
	}

	@Test
	fun `service proxy is an empty subclass`() {
		val source =
			ProxySourceGenerator.generateSource(
				ProxiedComponent(
					ComponentType.SERVICE,
					"com.example.app.SyncService",
					"com.example.app.quickbuild.proxies.Proxy0Service",
				),
			)

		assertThat(source)
			.contains("public class Proxy0Service extends com.example.app.SyncService {")
		// The activity-only member must not leak into a service body: a service that
		// overrode getClassLoader would answer for its own lifecycle, not an activity's.
		assertThat(source).doesNotContain("getClassLoader")
		assertThat(source).doesNotContain("@Override")
	}

	@Test
	fun `receiver and provider proxies are empty subclasses`() {
		listOf(
			ProxiedComponent(
				ComponentType.RECEIVER,
				"com.example.app.BootReceiver",
				"com.example.app.quickbuild.proxies.Proxy0Receiver",
			),
			ProxiedComponent(
				ComponentType.PROVIDER,
				"com.example.app.DataProvider",
				"com.example.app.quickbuild.proxies.Proxy0Provider",
			),
		).forEach { component ->
			val source = ProxySourceGenerator.generateSource(component)

			assertThat(source).contains(
				"public class ${component.proxyClass!!.substringAfterLast('.')} " +
					"extends ${component.userClass} {",
			)
			assertThat(source).doesNotContain("@Override")
		}
	}

	@Test
	fun `nested user class binary name becomes a canonical name in the extends clause`() {
		// A receiver declared as an inner class (e.g. WorkManager's
		// ConstraintProxy$BatteryChargingProxy) arrives as a binary name; javac resolves
		// only the canonical Outer.Inner form.
		val source =
			ProxySourceGenerator.generateSource(
				ProxiedComponent(
					ComponentType.RECEIVER,
					"com.example.app.Outer\$Inner",
					"com.example.app.quickbuild.proxies.Proxy0Receiver",
				),
			)

		assertThat(source).contains("extends com.example.app.Outer.Inner {")
		assertThat(source).doesNotContain("Outer\$Inner")
	}

	@Test
	fun `fails on a proxy class without a package`() {
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource("Proxy0Activity", "com.example.app.MainActivity")
			}
		assertThat(error).hasMessageThat().contains("no package")
	}

	@Test
	fun `fails on the application component - it has no proxy`() {
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource(
					ProxiedComponent(ComponentType.APPLICATION, "com.example.app.App", null),
				)
			}
		assertThat(error).hasMessageThat().contains("no proxy")
	}

	@Test
	fun `fails on the application type even when a proxy class name is supplied`() {
		// The component overload above rejects the Application on its null proxyClass, so it
		// never reaches the body switch. A caller of the class-pair overload can hand over a
		// perfectly good name, and the type alone still has to be refused - emitting an
		// `extends android.app.Application` proxy would give the manifest a second Application.
		val error =
			assertThrows<IllegalArgumentException> {
				ProxySourceGenerator.generateSource(
					proxyClass = "com.example.app.quickbuild.proxies.Proxy0Application",
					userClass = "com.example.app.App",
					type = ComponentType.APPLICATION,
				)
			}
		assertThat(error).hasMessageThat().contains("the Application gets no proxy")
	}

	/**
	 * A package segment Java reserves makes the emitted source unparsable, so it has to be
	 * caught by name before javac sees it.
	 *
	 * `package com.example.native` is legal Kotlin - `native` is only a soft keyword there - and
	 * legal in a manifest `android:name`, but `extends com.example.native.MainActivity` is a
	 * Java syntax error, and javac reports it as "<identifier> expected" while naming neither
	 * the component nor the user's way out.
	 */
	@Test
	fun `a package segment Java reserves is reported by name`() {
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.native.MainActivity"))
			.isEqualTo("native")
		// The literals are as illegal as the keywords, and an id can end in a keyword segment
		// too, which breaks the package declaration rather than the extends clause.
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.null"))
			.isEqualTo("null")
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.class.Widget"))
			.isEqualTo("class")
	}

	@Test
	fun `a name whose segments are all legal identifiers is accepted`() {
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.app.MainActivity")).isNull()
		// Contextual keywords are legal identifiers where a proxy source writes them, so they
		// must not be rejected: doing so would refuse a project that builds.
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.record.var.Widget")).isNull()
		// A segment that merely starts with a keyword is a different word.
		assertThat(ProxySourceGenerator.reservedJavaSegment("com.example.nativeui.Widget")).isNull()
	}
}
