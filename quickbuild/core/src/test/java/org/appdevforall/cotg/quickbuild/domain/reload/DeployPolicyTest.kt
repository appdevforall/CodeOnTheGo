package org.appdevforall.cotg.quickbuild.domain.reload

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Contract tests for the restart-vs-recreate decision (see component-proxying-design.md,
 * "Restart vs recreate"): restart iff the app declares a service, provider or custom
 * `Application`, whatever the compile touched. Receivers and activities never restart.
 *
 * The rule deliberately ignores the recompiled set, because every generation ships the whole
 * user class set. The tests below therefore pin the decision against edits that a
 * closure-intersection rule would have called a hot swap - those are the regressions that
 * reintroduce the measured `ClassCastException`.
 */
class DeployPolicyTest {
	private val service =
		ComponentInfo(
			ComponentKind.SERVICE,
			"com.example.SyncService",
			proxyClass = "com.example.quickbuild.proxies.Proxy0Service",
			supertypes = listOf("com.example.BaseService"),
		)
	private val provider =
		ComponentInfo(
			ComponentKind.PROVIDER,
			"com.example.DataProvider",
			proxyClass = "com.example.quickbuild.proxies.Proxy0Provider",
		)
	private val application = ComponentInfo(ComponentKind.APPLICATION, "com.example.App")
	private val receiver =
		ComponentInfo(
			ComponentKind.RECEIVER,
			"com.example.BootReceiver",
			proxyClass = "com.example.quickbuild.proxies.Proxy0Receiver",
		)
	private val activity =
		ComponentInfo(
			ComponentKind.ACTIVITY,
			"com.example.MainActivity",
			proxyClass = "com.example.quickbuild.proxies.Proxy0Activity",
			launcher = true,
			supertypes = listOf("com.example.BaseActivity"),
		)

	private val logSenderService =
		ComponentInfo(
			ComponentKind.SERVICE,
			"com.itsaky.androidide.logsender.LogSenderService",
			proxyClass = "com.example.quickbuild.proxies.Proxy1Service",
		)
	private val logSenderInstaller =
		ComponentInfo(
			ComponentKind.PROVIDER,
			"com.itsaky.androidide.logsender.utils.LogSenderInstaller",
			proxyClass = "com.example.quickbuild.proxies.Proxy1Provider",
		)

	private fun policy(vararg components: ComponentInfo) = DeployPolicy(components.toList())

	@Test
	fun `an edit far from the Application still restarts - the payload redefines it anyway`() {
		// The regression this rule exists for: an activity-only edit, reproduced on device as
		// `ClassCastException: ProbeApp cannot be cast to ProbeApp`. A closure-intersection
		// rule answers Recreate here, because the recompiled set never names the Application.
		val policy = policy(activity, application)

		assertThat(policy.decide(listOf("com/example/MainActivity.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
		assertThat(policy.decide(listOf("com/example/util/Formatter.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `an edit far from a service or provider restarts too`() {
		assertThat(policy(activity, service).decide(listOf("com/example/util/Formatter.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy(activity, provider).decide(listOf("com/example/util/Formatter.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.PROVIDER, "com.example.DataProvider"))
	}

	@Test
	fun `every restart-sensitive kind restarts on its own`() {
		RESTART_SENSITIVE_KINDS.forEach { kind ->
			val component = ComponentInfo(kind, "com.example.Held")
			assertThat(policy(activity, component).decide(listOf("com/example/Unrelated.class")))
				.isEqualTo(DeployDecision.Restart(kind, "com.example.Held"))
		}
	}

	@Test
	fun `the component class itself recompiled - restart naming it`() {
		assertThat(policy(activity, service, receiver).decide(listOf("com/example/SyncService.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy(provider).decide(listOf("com/example/DataProvider.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.PROVIDER, "com.example.DataProvider"))
		assertThat(policy(activity, application).decide(listOf("com/example/App.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `no restart-sensitive component - every code deploy hot swaps`() {
		val policy = policy(activity, receiver)

		assertThat(policy.decide(listOf("com/example/MainActivity.class"))).isEqualTo(DeployDecision.Recreate)
		assertThat(policy.decide(listOf("com/example/BootReceiver.class"))).isEqualTo(DeployDecision.Recreate)
		assertThat(policy.decide(emptyList())).isEqualTo(DeployDecision.Recreate)
		assertThat(policy.decide(null)).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `a component list with no components at all hot swaps`() {
		assertThat(policy().decide(listOf("com/example/MainActivity.class")))
			.isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `a compile that emitted nothing still restarts - the dex is rebuilt whole`() {
		// The dex step walks the compiler's output tree, so an empty recompiled set still
		// ships every user class through a fresh loader and still breaks a held instance.
		assertThat(policy(activity, application).decide(emptyList()))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `an unknown recompiled set restarts`() {
		assertThat(policy(activity, service).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `the first restart-sensitive component in declaration order names the cause`() {
		assertThat(policy(activity, provider, service, application).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.PROVIDER, "com.example.DataProvider"))
		assertThat(policy(activity, application, service).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `pre-v2 baseline - any code-bearing deploy routes to a proxy app rebuild`() {
		val policy = DeployPolicy(emptyList(), componentInfoAvailable = false)

		assertThat(policy.decide(listOf("com/example/Foo.class")))
			.isInstanceOf(DeployDecision.RebuildProxyApp::class.java)
		assertThat(policy.decide(null)).isInstanceOf(DeployDecision.RebuildProxyApp::class.java)
	}

	@Test
	fun `pre-v2 baseline - a compile that emitted nothing is not worth a rebuild`() {
		assertThat(DeployPolicy(emptyList(), componentInfoAvailable = false).decide(emptyList()))
			.isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `pre-v2 wins over a known component - that runtime cannot honour a restart`() {
		// The old runtime hot-swaps a restart deploy instead of exiting, so asking it to
		// restart would leave the component stale AND claim it did not. Rebuild instead.
		val policy = DeployPolicy(listOf(service), componentInfoAvailable = false)

		assertThat(policy.decide(listOf("com/example/SyncService.class")))
			.isInstanceOf(DeployDecision.RebuildProxyApp::class.java)
	}

	@Test
	fun `an app whose only service and provider are CoGo's own hot swaps`() {
		// Logsender is injected into every debuggable build, so without the exemption every app
		// restarts on every save. Its classes live in the base APK dex and no payload redefines
		// them, so nothing can go stale.
		val policy = policy(activity, logSenderService, logSenderInstaller)

		assertThat(policy.decide(listOf("com/example/MainActivity.class"))).isEqualTo(DeployDecision.Recreate)
		assertThat(policy.decide(null)).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `a user-declared service still restarts alongside CoGo's own`() {
		assertThat(policy(logSenderInstaller, logSenderService, service).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy(logSenderInstaller, application).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `the exemption is by exact class name, not by package`() {
		// A user class that happens to sit in logsender's package is still the user's code and
		// still ships in the payload. A prefix match would silently stop restarting for it.
		val neighbour =
			ComponentInfo(ComponentKind.SERVICE, "com.itsaky.androidide.logsender.MyOwnService")
		val nestedNeighbour =
			ComponentInfo(ComponentKind.PROVIDER, "com.itsaky.androidide.logsender.utils.MyOwnProvider")

		assertThat(policy(logSenderService, neighbour).decide(null))
			.isEqualTo(
				DeployDecision.Restart(ComponentKind.SERVICE, "com.itsaky.androidide.logsender.MyOwnService"),
			)
		assertThat(policy(logSenderInstaller, nestedNeighbour).decide(null))
			.isEqualTo(
				DeployDecision.Restart(
					ComponentKind.PROVIDER,
					"com.itsaky.androidide.logsender.utils.MyOwnProvider",
				),
			)
	}

	@Test
	fun `the exempt names are the ones CoGo actually injects`() {
		// Pinned against the logsender AAR's merged manifest; a rename there that misses this
		// set silently restores restart-on-every-save.
		assertThat(COGO_INJECTED_COMPONENTS)
			.containsExactly(
				"com.itsaky.androidide.logsender.LogSenderService",
				"com.itsaky.androidide.logsender.utils.LogSenderInstaller",
			)
	}

	@Test
	fun `backslash-separated class paths do not change the decision`() {
		assertThat(policy(service).decide(listOf("com\\example\\SyncService.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy(activity).decide(listOf("com\\example\\MainActivity.class")))
			.isEqualTo(DeployDecision.Recreate)
	}
}
