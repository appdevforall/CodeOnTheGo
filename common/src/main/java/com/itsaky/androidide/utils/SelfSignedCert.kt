@file:JvmName("SelfSignedCertUtils")

package com.itsaky.androidide.utils

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Generates a self-signed X.509 v3 certificate using only standard Java SE / Android APIs -
 * no BouncyCastle required. Encodes the DER structure manually.
 *
 * Supports a simple distinguished name with C, O, and CN components (empty values are omitted).
 */
fun generateSelfSignedCert(
	keyPair: KeyPair,
	dn: String,
	serial: BigInteger,
	notBefore: Date,
	notAfter: Date,
): X509Certificate {
	val tbs = buildTbsCertificate(keyPair.public.encoded, dn, serial, notBefore, notAfter)
	val sig = Signature.getInstance("SHA256WithRSA")
	sig.initSign(keyPair.private)
	sig.update(tbs)
	val sigBytes = sig.sign()

	val certDer = derSeq(tbs + sha256WithRsaAlgId() + derBitString(sigBytes))
	return CertificateFactory
		.getInstance("X.509")
		.generateCertificate(certDer.inputStream()) as X509Certificate
}

// TBSCertificate

private fun buildTbsCertificate(
	spkiBytes: ByteArray,
	dn: String,
	serial: BigInteger,
	notBefore: Date,
	notAfter: Date,
): ByteArray {
	val version = derContextTagged(0, derInteger(BigInteger.valueOf(2))) // v3
	val serialNum = derInteger(serial)
	val sigAlg = sha256WithRsaAlgId()
	val issuer = encodeDn(dn)
	val validity = derSeq(derTime(notBefore) + derTime(notAfter))
	val subject = issuer // self-signed
	val spki = spkiBytes // already DER-encoded by JCA

	return derSeq(version + serialNum + sigAlg + issuer + validity + subject + spki)
}

// Distinguished Name encoding
// Parses "C=XX, O=Foo, CN=Bar"-style strings; skips RDNs with empty values.

// OIDs for common attribute types
private val OID_COMMON_NAME = oidBytes(2, 5, 4, 3)
private val OID_ORGANIZATION = oidBytes(2, 5, 4, 10)
private val OID_COUNTRY = oidBytes(2, 5, 4, 6)

private fun encodeDn(dn: String): ByteArray {
	val rdns = mutableListOf<ByteArray>()
	for (part in dn.split(",")) {
		val eq = part.indexOf('=')
		if (eq < 0) continue
		val key = part.substring(0, eq).trim().uppercase()
		val value = part.substring(eq + 1).trim()
		if (value.isEmpty()) continue

		val oidBytes =
			when (key) {
				"CN" -> OID_COMMON_NAME
				"O" -> OID_ORGANIZATION
				"C" -> OID_COUNTRY
				else -> continue
			}
		val attrType = derSeq(oidBytes + derUtf8String(value))
		rdns += derSet(attrType)
	}
	return derSeq(rdns.fold(ByteArray(0)) { acc, b -> acc + b })
}

// Algorithm identifier: SHA256WithRSA (OID 1.2.840.113549.1.1.11) + NULL

private val SHA256_WITH_RSA_OID = oidBytes(1, 2, 840, 113549, 1, 1, 11)

private fun sha256WithRsaAlgId(): ByteArray = derSeq(SHA256_WITH_RSA_OID + byteArrayOf(0x05, 0x00)) // NULL

// DER encoding primitives

private fun derSeq(content: ByteArray): ByteArray = tlv(0x30, content)

private fun derSet(content: ByteArray): ByteArray = tlv(0x31, content)

private fun derBitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0x00) + bytes)

private fun derUtf8String(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

private fun derInteger(value: BigInteger): ByteArray =
	// BigInteger.toByteArray() already prepends a 0x00 sign byte for positive integers whose
	// high bit would otherwise be set - exactly what DER requires.
	tlv(0x02, value.toByteArray())

private fun derContextTagged(
	tag: Int,
	content: ByteArray,
): ByteArray = tlv(0xA0 or tag, content)

private fun derTime(date: Date): ByteArray {
	// GeneralizedTime (tag 0x18) for dates in or after 2050, UTCTime (tag 0x17) otherwise.
	val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
	cal.time = date
	return if (cal.get(Calendar.YEAR) >= 2050) {
		val s =
			String.format(
				Locale.ROOT,
				"%04d%02d%02d%02d%02d%02dZ",
				cal.get(Calendar.YEAR),
				cal.get(Calendar.MONTH) + 1,
				cal.get(Calendar.DAY_OF_MONTH),
				cal.get(Calendar.HOUR_OF_DAY),
				cal.get(Calendar.MINUTE),
				cal.get(Calendar.SECOND),
			)
		tlv(0x18, s.toByteArray(Charsets.US_ASCII))
	} else {
		val s =
			String.format(
				Locale.ROOT,
				"%02d%02d%02d%02d%02d%02dZ",
				cal.get(Calendar.YEAR) % 100,
				cal.get(Calendar.MONTH) + 1,
				cal.get(Calendar.DAY_OF_MONTH),
				cal.get(Calendar.HOUR_OF_DAY),
				cal.get(Calendar.MINUTE),
				cal.get(Calendar.SECOND),
			)
		tlv(0x17, s.toByteArray(Charsets.US_ASCII))
	}
}

// OID encoding helpers

private fun oidBytes(vararg arcs: Int): ByteArray {
	val out = ByteArrayOutputStream()
	// First two arcs are combined: first*40 + second
	out.write(encodeBase128(arcs[0] * 40 + arcs[1]))
	for (i in 2 until arcs.size) {
		out.write(encodeBase128(arcs[i]))
	}
	return tlv(0x06, out.toByteArray())
}

private fun encodeBase128(value: Int): ByteArray {
	if (value == 0) return byteArrayOf(0)
	val buf = ByteArray(5)
	var v = value
	var pos = 4
	while (v > 0) {
		buf[pos--] = (v and 0x7F).toByte()
		v = v ushr 7
	}
	val start = pos + 1
	val result = ByteArray(5 - start)
	for (i in result.indices) {
		result[i] = if (i < result.size - 1) (buf[start + i].toInt() or 0x80).toByte() else buf[start + i]
	}
	return result
}

// Type-Length-Value builder

private fun tlv(
	tag: Int,
	content: ByteArray,
): ByteArray {
	val out = ByteArrayOutputStream()
	out.write(tag)
	val len = content.size
	when {
		len < 0x80 -> {
			out.write(len)
		}

		len < 0x100 -> {
			out.write(0x81)
			out.write(len)
		}

		else -> {
			out.write(0x82)
			out.write(len ushr 8)
			out.write(len and 0xFF)
		}
	}
	out.write(content)
	return out.toByteArray()
}
