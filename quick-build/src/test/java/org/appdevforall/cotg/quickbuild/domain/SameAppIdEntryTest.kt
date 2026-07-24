package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SameAppIdEntryTest {
	private val appId = "com.example.app"
	private val cert = "aa".repeat(32)
	private val otherCert = "bb".repeat(32)

	private fun proceed(decision: SameAppIdEntryDecision): SameAppIdEntryDecision.Proceed {
		assertThat(decision).isInstanceOf(SameAppIdEntryDecision.Proceed::class.java)
		return decision as SameAppIdEntryDecision.Proceed
	}

	@Test
	fun `not installed is a fresh install at the project versionCode`() {
		val decision =
			proceed(SameAppIdEntry.decide(appId, installed = null, projectVersionCode = 7, cogoCertSha256 = cert))

		assertThat(decision.versionCode).isEqualTo(7)
		assertThat(decision.updateInstall).isFalse()
		assertThat(decision.signatureVerified).isTrue()
	}

	@Test
	fun `not installed with unknown project versionCode pins 1`() {
		val decision =
			proceed(
				SameAppIdEntry.decide(appId, installed = null, projectVersionCode = null, cogoCertSha256 = null),
			)

		assertThat(decision.versionCode).isEqualTo(1)
	}

	@Test
	fun `non-positive project versionCode floors to 1`() {
		val decision =
			proceed(SameAppIdEntry.decide(appId, installed = null, projectVersionCode = 0, cogoCertSha256 = null))

		assertThat(decision.versionCode).isEqualTo(1)
	}

	@Test
	fun `matching signature is a data-preserving update at installed plus one`() {
		val decision =
			proceed(
				SameAppIdEntry.decide(
					appId,
					installed = InstalledRealApp(versionCode = 41, certSha256 = cert),
					projectVersionCode = 3,
					cogoCertSha256 = cert,
				),
			)

		assertThat(decision.versionCode).isEqualTo(42)
		assertThat(decision.updateInstall).isTrue()
		assertThat(decision.signatureVerified).isTrue()
	}

	@Test
	fun `project versionCode wins when above installed plus one`() {
		val decision =
			proceed(
				SameAppIdEntry.decide(
					appId,
					installed = InstalledRealApp(versionCode = 5, certSha256 = cert),
					projectVersionCode = 100,
					cogoCertSha256 = cert,
				),
			)

		assertThat(decision.versionCode).isEqualTo(100)
	}

	@Test
	fun `certificate comparison ignores case`() {
		val decision =
			SameAppIdEntry.decide(
				appId,
				installed = InstalledRealApp(versionCode = 1, certSha256 = cert.uppercase()),
				projectVersionCode = null,
				cogoCertSha256 = cert,
			)

		assertThat(decision).isInstanceOf(SameAppIdEntryDecision.Proceed::class.java)
	}

	@Test
	fun `signature mismatch refuses with the app id named and the signature-mismatch reason`() {
		val decision =
			SameAppIdEntry.decide(
				appId,
				installed = InstalledRealApp(versionCode = 1, certSha256 = otherCert),
				projectVersionCode = null,
				cogoCertSha256 = cert,
			)

		assertThat(decision).isInstanceOf(SameAppIdEntryDecision.Refuse::class.java)
		val refuse = decision as SameAppIdEntryDecision.Refuse
		assertThat(refuse.message).contains(appId)
		assertThat(refuse.reason).isEqualTo(SameAppIdRefusalReason.SIGNATURE_MISMATCH)
	}

	@Test
	fun `unknown installed certificate proceeds unverified`() {
		val decision =
			proceed(
				SameAppIdEntry.decide(
					appId,
					installed = InstalledRealApp(versionCode = 1, certSha256 = null),
					projectVersionCode = null,
					cogoCertSha256 = cert,
				),
			)

		assertThat(decision.updateInstall).isTrue()
		assertThat(decision.signatureVerified).isFalse()
	}

	@Test
	fun `unknown CoGo certificate proceeds unverified`() {
		val decision =
			proceed(
				SameAppIdEntry.decide(
					appId,
					installed = InstalledRealApp(versionCode = 1, certSha256 = cert),
					projectVersionCode = null,
					cogoCertSha256 = null,
				),
			)

		assertThat(decision.signatureVerified).isFalse()
	}

	@Test
	fun `versionCode overflow refuses instead of wrapping, tagged distinctly from a signature mismatch`() {
		val decision =
			SameAppIdEntry.decide(
				appId,
				installed = InstalledRealApp(versionCode = Int.MAX_VALUE.toLong(), certSha256 = cert),
				projectVersionCode = null,
				cogoCertSha256 = cert,
			)

		assertThat(decision).isInstanceOf(SameAppIdEntryDecision.Refuse::class.java)
		// Matching certs above: this refusal is NOT a signature mismatch. Analytics must
		// not conflate the two - see SameAppIdModeControllerTest for the reporting side.
		assertThat((decision as SameAppIdEntryDecision.Refuse).reason)
			.isEqualTo(SameAppIdRefusalReason.VERSION_CODE_OVERFLOW)
	}
}
