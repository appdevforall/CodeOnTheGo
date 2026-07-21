package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Contract tests for the restart-vs-recreate decision (design contract sections 4-5):
 * restart iff the recompiled set intersects {service, provider, custom Application}
 * united with their user-side supertypes and nested classes of either. Receivers and
 * activities never restart; unknown recompiled sets decide conservatively.
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

	private fun policy(vararg components: ComponentInfo) = DeployPolicy(components.toList())

	@Test
	fun `service class recompiled - restart naming the service`() {
		val decision =
			policy(activity, service, receiver)
				.decide(listOf("com/example/SyncService.class"))

		assertThat(decision)
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `provider class recompiled - restart`() {
		val decision = policy(provider).decide(listOf("com/example/DataProvider.class"))

		assertThat(decision)
			.isEqualTo(DeployDecision.Restart(ComponentKind.PROVIDER, "com.example.DataProvider"))
	}

	@Test
	fun `custom Application recompiled - restart`() {
		val decision = policy(activity, application).decide(listOf("com/example/App.class"))

		assertThat(decision)
			.isEqualTo(DeployDecision.Restart(ComponentKind.APPLICATION, "com.example.App"))
	}

	@Test
	fun `receiver class recompiled - recreate, receivers instantiate fresh per delivery`() {
		val decision = policy(activity, receiver).decide(listOf("com/example/BootReceiver.class"))

		assertThat(decision).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `activity or helper class recompiled - recreate`() {
		val policy = policy(activity, service, provider, application)

		assertThat(policy.decide(listOf("com/example/MainActivity.class")))
			.isEqualTo(DeployDecision.Recreate)
		assertThat(policy.decide(listOf("com/example/util/Formatter.class")))
			.isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `baked supertype of a service recompiled - restart`() {
		val decision = policy(activity, service).decide(listOf("com/example/BaseService.class"))

		assertThat(decision)
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `nested class of a service - restart, of its supertype - restart`() {
		val policy = policy(service)

		assertThat(policy.decide(listOf("com/example/SyncService\$Worker.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy.decide(listOf("com/example/BaseService\$Companion.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `textual prefix without a dollar is NOT a nested class - recreate`() {
		// SyncServiceHelper merely shares the prefix; only `SyncService$...` is nested.
		val decision = policy(service).decide(listOf("com/example/SyncServiceHelper.class"))

		assertThat(decision).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `activity supertypes are not in the restart closure`() {
		val decision = policy(activity, service).decide(listOf("com/example/BaseActivity.class"))

		assertThat(decision).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `empty recompiled set - recreate even without component info`() {
		assertThat(policy(service).decide(emptyList())).isEqualTo(DeployDecision.Recreate)
		assertThat(DeployPolicy(emptyList(), componentInfoAvailable = false).decide(emptyList()))
			.isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `unknown recompiled set decides conservatively - restart when a restart component exists`() {
		assertThat(policy(activity, service).decide(null))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
		assertThat(policy(activity, receiver).decide(null)).isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `pre-v2 baseline - any code-bearing deploy routes to rebaseline`() {
		val policy = DeployPolicy(emptyList(), componentInfoAvailable = false)

		assertThat(policy.decide(listOf("com/example/Foo.class")))
			.isInstanceOf(DeployDecision.Rebaseline::class.java)
		assertThat(policy.decide(null)).isInstanceOf(DeployDecision.Rebaseline::class.java)
	}

	@Test
	fun `re-parenting is caught - live hierarchy update extends the closure`() {
		val policy = policy(service)

		// Before the re-parent, NewBase is unrelated to the service.
		assertThat(policy.decide(listOf("com/example/NewBase.class")))
			.isEqualTo(DeployDecision.Recreate)

		// The build that re-parents recompiles SyncService itself (direct hit) and
		// reports its new header; from then on NewBase edits also restart.
		policy.onClassHierarchy("com.example.SyncService", listOf("com.example.NewBase"))
		assertThat(policy.decide(listOf("com/example/NewBase.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `re-parenting drops the OLD parent from the closure`() {
		val policy = policy(service)
		policy.onClassHierarchy("com.example.SyncService", listOf("com.example.NewBase"))

		assertThat(policy.decide(listOf("com/example/BaseService.class")))
			.isEqualTo(DeployDecision.Recreate)
	}

	@Test
	fun `interface supertypes from live headers count toward the closure`() {
		val policy = policy(service)
		policy.onClassHierarchy(
			"com.example.SyncService",
			listOf("android.app.Service", "com.example.SyncContract"),
		)

		assertThat(policy.decide(listOf("com/example/SyncContract.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `cyclic hierarchy edges do not hang the closure walk`() {
		val policy = policy(service)
		policy.onClassHierarchy("com.example.SyncService", listOf("com.example.A"))
		policy.onClassHierarchy("com.example.A", listOf("com.example.SyncService"))

		assertThat(policy.decide(listOf("com/example/A.class")))
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}

	@Test
	fun `backslash-separated class paths map to the same FQNs`() {
		val decision = policy(service).decide(listOf("com\\example\\SyncService.class"))

		assertThat(decision)
			.isEqualTo(DeployDecision.Restart(ComponentKind.SERVICE, "com.example.SyncService"))
	}
}
