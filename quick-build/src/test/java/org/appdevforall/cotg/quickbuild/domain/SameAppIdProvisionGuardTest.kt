package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The refusal pipeline that gates installing over the user's real app - extracted from
 * `GradleQuickBuildProvisioner` so it is JVM-tested here rather than only exercised
 * on-device (finding: the glue was the untested last line of defense).
 */
class SameAppIdProvisionGuardTest {
	private val realId = "com.example.app"
	private val suffixPkg = "com.example.app.quickbuild"
	private val cert = "aa".repeat(32)
	private val otherCert = "bb".repeat(32)

	private fun refuse(decision: SameAppIdProvisionDecision): SameAppIdProvisionDecision.Refuse {
		assertThat(decision).isInstanceOf(SameAppIdProvisionDecision.Refuse::class.java)
		return decision as SameAppIdProvisionDecision.Refuse
	}

	/** A guard with a confirmed episode for [realId] (what a real provision reaches with). */
	private fun confirmedGuard(): SameAppIdGuard = SameAppIdGuard().apply { mintClobberToken(realId) }

	// --- preflight -----------------------------------------------------------

	@Test
	fun `preflight is a no-op in suffix mode`() {
		assertThat(SameAppIdProvisionGuard.preflight(false, null, SameAppIdGuard())).isNull()
		assertThat(SameAppIdProvisionGuard.preflight(false, 5, SameAppIdGuard())).isNull()
	}

	@Test
	fun `preflight refuses same-app-id with no pinned versionCode`() {
		val message = SameAppIdProvisionGuard.preflight(true, null, SameAppIdGuard())
		assertThat(message).contains("never confirmed")
	}

	@Test
	fun `preflight proceeds with a pinned versionCode`() {
		assertThat(SameAppIdProvisionGuard.preflight(true, 42, SameAppIdGuard())).isNull()
	}

	@Test
	fun `preflight refuses a versionCode override that decreased within the episode`() {
		val guard = SameAppIdGuard()
		assertThat(SameAppIdProvisionGuard.preflight(true, 100, guard)).isNull()
		val message = SameAppIdProvisionGuard.preflight(true, 50, guard)
		assertThat(message).contains("decreased within an episode")
	}

	// --- installGate: honored-mode + versionCode ------------------------------

	@Test
	fun `installGate refuses when the setup build did not honor the mode`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = false,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = null,
				guard = confirmedGuard(),
			)
		val r = refuse(decision)
		assertThat(r.message).contains("did not honor same-app-id mode")
		assertThat(r.signatureMismatch).isFalse()
	}

	@Test
	fun `installGate refuses when the applied versionCode is not the pinned one`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 9,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = null,
				guard = confirmedGuard(),
			)
		val r = refuse(decision)
		assertThat(r.message).contains("instead of the pinned 10")
		assertThat(r.signatureMismatch).isFalse()
	}

	// --- installGate: signature refusal (the destructive path) ----------------

	@Test
	fun `installGate proceeds for a fresh install (real app not installed)`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = null,
				guard = confirmedGuard(),
			)
		assertThat(decision).isEqualTo(SameAppIdProvisionDecision.Proceed)
	}

	@Test
	fun `installGate proceeds for an update install when the certs match`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = cert,
				builtCertSha256 = cert,
				guard = confirmedGuard(),
			)
		assertThat(decision).isEqualTo(SameAppIdProvisionDecision.Proceed)
	}

	@Test
	fun `installGate matches certs case-insensitively`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = cert.uppercase(),
				builtCertSha256 = cert.lowercase(),
				guard = confirmedGuard(),
			)
		assertThat(decision).isEqualTo(SameAppIdProvisionDecision.Proceed)
	}

	@Test
	fun `installGate refuses a signature mismatch and flags it for analytics`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = otherCert,
				builtCertSha256 = cert,
				guard = confirmedGuard(),
			)
		val r = refuse(decision)
		assertThat(r.signatureMismatch).isTrue()
		assertThat(r.message).isEqualTo(SameAppIdEntry.refusalMessage(realId))
	}

	@Test
	fun `installGate treats an unreadable installed cert as a mismatch`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = null,
				builtCertSha256 = cert,
				guard = confirmedGuard(),
			)
		assertThat(refuse(decision).signatureMismatch).isTrue()
	}

	@Test
	fun `installGate treats an unreadable built cert as a mismatch`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = cert,
				builtCertSha256 = null,
				guard = confirmedGuard(),
			)
		assertThat(refuse(decision).signatureMismatch).isTrue()
	}

	// --- installGate: guard assertions (section 7) ----------------------------

	@Test
	fun `installGate refuses same-app-id install with no confirmed episode token`() {
		// Matching certs, but the guard has no minted token: the last-line guard refuses.
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = true,
				pinnedVersionCode = 10,
				setupSameAppId = true,
				setupVersionCode = 10,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = true,
				installedCertSha256 = cert,
				builtCertSha256 = cert,
				guard = SameAppIdGuard(),
			)
		val r = refuse(decision)
		assertThat(r.message).contains("No confirmed clobber warning")
		assertThat(r.signatureMismatch).isFalse()
	}

	@Test
	fun `installGate proceeds in suffix mode for a distinct suffixed package`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = false,
				pinnedVersionCode = null,
				setupSameAppId = false,
				setupVersionCode = null,
				testAppPackage = suffixPkg,
				realApplicationId = realId,
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = null,
				guard = SameAppIdGuard(),
			)
		assertThat(decision).isEqualTo(SameAppIdProvisionDecision.Proceed)
	}

	@Test
	fun `installGate refuses suffix mode that would target the real id`() {
		val decision =
			SameAppIdProvisionGuard.installGate(
				modeSameAppId = false,
				pinnedVersionCode = null,
				setupSameAppId = false,
				setupVersionCode = null,
				testAppPackage = realId,
				realApplicationId = realId,
				realAppInstalled = false,
				installedCertSha256 = null,
				builtCertSha256 = null,
				guard = SameAppIdGuard(),
			)
		assertThat(refuse(decision).signatureMismatch).isFalse()
	}
}
