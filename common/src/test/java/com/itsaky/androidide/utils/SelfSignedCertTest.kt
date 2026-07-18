package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import java.util.Locale

/** Round-trip and regression coverage for the hand-rolled DER encoder in SelfSignedCert.kt. */
class SelfSignedCertTest {
	private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

	// DER time encoding truncates to whole seconds.
	private fun assertSameInstant(
		actual: Date,
		expected: Date,
	) {
		assertThat(actual.time / 1000).isEqualTo(expected.time / 1000)
	}

	@Test
	fun `generated certificate round-trips through CertificateFactory and verifies`() {
		val keyPair = rsaKeyPair()
		val notBefore = Date(0)
		val notAfter = Date(4102444800000) // 2100-01-01, exercises the GeneralizedTime branch
		val cert = generateSelfSignedCert(keyPair, "C=US, O=Test Org, CN=test", BigInteger.ONE, notBefore, notAfter)

		cert.verify(keyPair.public)
		assertThat(cert.subjectX500Principal.name).contains("CN=test")
		assertThat(cert.subjectX500Principal.name).contains("O=Test Org")
		assertThat(cert.subjectX500Principal.name).contains("C=US")
		assertSameInstant(cert.notBefore, notBefore)
		assertSameInstant(cert.notAfter, notAfter)
	}

	@Test
	fun `certificate generation is unaffected by a non-Gregorian default locale`() {
		val original = Locale.getDefault()
		try {
			// Thai triggers a Buddhist (non-Gregorian) Calendar from Calendar.getInstance()
			// unless Locale.ROOT is passed explicitly - see derTime in SelfSignedCert.kt.
			Locale.setDefault(
				Locale
					.Builder()
					.setLanguage("th")
					.setRegion("TH")
					.build(),
			)
			val keyPair = rsaKeyPair()
			val notBefore = Date(0)
			val notAfter = Date(2461449600L * 1000)
			val cert = generateSelfSignedCert(keyPair, "CN=00", BigInteger.ONE, notBefore, notAfter)

			cert.verify(keyPair.public)
			assertSameInstant(cert.notBefore, notBefore)
			assertSameInstant(cert.notAfter, notAfter)
		} finally {
			Locale.setDefault(original)
		}
	}

	@Test
	fun `dates before 1950 are encoded as GeneralizedTime`() {
		val keyPair = rsaKeyPair()
		val notBefore = Date(-946771200000) // 1940-01-01
		val notAfter = Date(0)
		val cert = generateSelfSignedCert(keyPair, "CN=test", BigInteger.ONE, notBefore, notAfter)

		cert.verify(keyPair.public)
		assertSameInstant(cert.notBefore, notBefore)
		assertSameInstant(cert.notAfter, notAfter)
	}

	@Test
	fun `escaped comma in a DN value does not split the RDN`() {
		val keyPair = rsaKeyPair()
		val cert =
			generateSelfSignedCert(
				keyPair,
				"O=Foo\\, Inc., CN=test",
				BigInteger.ONE,
				Date(0),
				Date(4102444800000),
			)

		cert.verify(keyPair.public)
		assertThat(cert.subjectX500Principal.name).contains("Foo\\, Inc.")
	}

	@Test
	fun `empty DN attribute values are preserved, not dropped`() {
		val keyPair = rsaKeyPair()
		val cert = generateSelfSignedCert(keyPair, "C=US, O=, CN=", BigInteger.ONE, Date(0), Date(4102444800000))

		cert.verify(keyPair.public)
		assertThat(cert.subjectX500Principal.name).contains("C=US")
	}

	@Test(expected = IllegalArgumentException::class)
	fun `unsupported DN attribute type throws`() {
		generateSelfSignedCert(rsaKeyPair(), "OU=Eng, CN=test", BigInteger.ONE, Date(0), Date(4102444800000))
	}

	@Test(expected = IllegalArgumentException::class)
	fun `malformed DN component throws`() {
		generateSelfSignedCert(rsaKeyPair(), "CN=test,", BigInteger.ONE, Date(0), Date(4102444800000))
	}

	@Test(expected = IllegalArgumentException::class)
	fun `non-PrintableString country value throws`() {
		generateSelfSignedCert(rsaKeyPair(), "C=U!", BigInteger.ONE, Date(0), Date(4102444800000))
	}
}
