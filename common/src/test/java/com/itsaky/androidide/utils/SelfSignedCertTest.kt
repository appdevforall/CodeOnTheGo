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

	@Test
	fun `generated certificate round-trips through CertificateFactory and verifies`() {
		val keyPair = rsaKeyPair()
		val cert =
			generateSelfSignedCert(
				keyPair,
				"C=US, O=Test Org, CN=test",
				BigInteger.ONE,
				Date(0),
				Date(4102444800000), // 2100-01-01, exercises the GeneralizedTime branch
			)

		cert.verify(keyPair.public)
		assertThat(cert.subjectX500Principal.name).contains("CN=test")
		assertThat(cert.subjectX500Principal.name).contains("O=Test Org")
		assertThat(cert.subjectX500Principal.name).contains("C=US")
	}

	@Test
	fun `certificate generation is unaffected by the default locale`() {
		val original = Locale.getDefault()
		try {
			Locale.setDefault(Locale.Builder().setLanguage("ar").build())
			val keyPair = rsaKeyPair()
			val cert = generateSelfSignedCert(keyPair, "CN=00", BigInteger.ONE, Date(0), Date(2461449600L * 1000))
			cert.verify(keyPair.public)
		} finally {
			Locale.setDefault(original)
		}
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
}
