package com.itsaky.androidide.localWebServer

import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream
import com.aayushatharva.brotli4j.encoder.Encoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

// Deliberately routed through production's toDirectByteBuffer rather than allocating here:
// attachDictionary reads the buffer's capacity, so an over-allocated buffer fails every decode.
// Duplicating the allocation would leave that helper untested and let the two drift apart.
private fun decodeBase64ToDirectBuffer(base64: String): ByteBuffer = toDirectByteBuffer(Base64.getDecoder().decode(base64))

// Regression coverage for ADFA-5153: documentation.db's Content rows are Brotli-compressed
// against a shared dictionary trained by OfflineDocumentationTools' zstd/brotli CLI pipeline
// (see populate_db.py's DictionaryCompressor), not by brotli4j itself. These fixtures were
// produced by that exact pipeline, so this test is what protects the cross-tool contract: a
// brotli4j upgrade (or native lib change) that silently broke compatibility with the CLI-produced
// wire format would otherwise only surface as garbled content on-device.
class BrotliDictionaryDecodeTest {
	companion object {
		// Unlike on-device (where ToolsManager/AssetsInstallationHelper already load it before
		// WebServer ever runs), nothing loads brotli4j's native lib in a plain JVM unit test --
		// without this, every test below fails with UnsatisfiedLinkError instead of exercising
		// real decode behavior.
		@JvmStatic
		@BeforeClass
		fun loadNativeLibrary() {
			Brotli4jLoader.ensureAvailability()
		}
	}

	// A ~3.3 KB zstd fast-cover dictionary trained on synthetic doc-page-like text, and a small
	// payload Brotli-compressed against it via the `brotli` CLI's `-D` flag (OfflineDocumentationTools'
	// actual encode path) -- see ADFA-5153.
	private val dictionaryBase64 =
		"N6Qw7OTyEGgfENCSpAP//////49QsrssRMqWGsnNSkLy/zfL/Ef3/zMAADhYoPCcRptTLgAEQIEAAMAS" +
			"pykQlqZI41QGmTEGEAIAAAAAAAAAAAAAAABkXQEAAAAAAAAAAAAAAAAAAAABAAAABAAAAAgAAABhY2Ug" +
			"dG9jLWVsZW1lbnQgZG9jcy1zaWRlYmFyIGludGVyZmFjZSB2YWwgZnVuIG9iamxlbWVudCB0b2MtZWxl" +
			"bWVudCBrb3RsaW4gb3ZlcnJpZGUgdG9jLWVsZW1lbnQgb3ZlciBrb3RsaW4ga290bGluIHZhciBkb2Nz" +
			"LXNpZGViYXIgdmFsIGNvbXBhbmlvbiBjb21wZSBmdW4gcGFnZS5wZWIga290bGluIGZ1biB2YXIgb2Jq" +
			"ZWN0IHRlbXBsYXRlIGRvY24gdmFyIHRlbXBsYXRlIGludGVyZmFjZSBjb21wYW5pb24gcGFnZS5wZWIg" +
			"dmFyIGlua290bGluIENvbnRlbnQtVHlwZSBkb2NzLXNpZGViYXIgbmF2IGludGVyZmFjZSBjb20gdG9j" +
			"LWVsZW1lbnQgY29tcGFuaW9uIG9iamVjdCBpbnRlcmZhY2Uga290bGluIGRvY2RlYmFyIG5hdiB0b2Mt" +
			"ZWxlbWVudCBDb250ZW50LVR5cGUgdGVtcGxhdGUgdmFyIGNsbiBzaWRlYmFyIHNpZGViYXIgdG9jLWVs" +
			"ZW1lbnQgb2JqZWN0IGNvbXBhbmlvbiBpbnRycmlkZSB0b2MtZWxlbWVudCBmdW4gY2xhc3MgdGVtcGxh" +
			"dGUgaW50ZXJmYWNlIGRvYyB0b2MtZWxlbWVudCBmdW4gdG9jLWVsZW1lbnQgdmFsIG9iamVjdCBvYmpl" +
			"Y3QgdG9jYmplY3QgbmF2IGZ1biBzaWRlYmFyIG92ZXJyaWRlIG9iamVjdCBmdW4gdmFsIG92ZXJhdGUg" +
			"aW50ZXJmYWNlIHZhciB0ZW1wbGF0ZSB0ZW1wbGF0ZSB2YXIgb2JqZWN0IGtvdGUga290bGluIG92ZXJy" +
			"aWRlIHBhZ2UucGViIG92ZXJyaWRlIGZ1biBjbGFzcyB2YXIgaW50ZXJmYWNlIGNsYXNzIHRlbXBsYXRl" +
			"IHNpZGViYXIgZnVuIHBhZ2UucGViIGRvY3NlIENvbnRlbnQtVHlwZSBpbnRlcmZhY2UgdGVtcGxhdGUg" +
			"aW50ZXJmYWNlIHZhciB0ZWVudC1UeXBlIENvbnRlbnQtVHlwZSBvYmplY3QgcGFnZS5wZWIgdGVtcGxh" +
			"dGUgb3ZlZW50LVR5cGUgb3ZlcnJpZGUgQ29udGVudC1UeXBlIHBhZ2UucGViIGNsYXNzIHNpZGVyIHRv" +
			"Yy1lbGVtZW50IHZhciBzaWRlYmFyIG5hdiBmdW4gY2xhc3Mga290bGluIHBhZyBvdmVycmlkZSBpbnRl" +
			"cmZhY2UgbmF2IHZhciBvdmVycmlkZSBjb21wYW5pb24gcGFnY2xhc3MgdmFsIGNsYXNzIENvbnRlbnQt" +
			"VHlwZSBkb2NzLXNpZGViYXIgbmF2IGNvbXAgZnVuIHRlbXBsYXRlIHBhZ2UucGViIGNsYXNzIG5hdiBw" +
			"YWdlLnBlYiBuYXYgQ29udCBjb21wYW5pb24gb3ZlcnJpZGUgdGVtcGxhdGUga290bGluIHNpZGViYXIg" +
			"dmFyIHBhdmFsIG5hdiBjbGFzcyBmdW4gb3ZlcnJpZGUgaW50ZXJmYWNlIGludGVyZmFjZSBrb3RudGVu" +
			"dC1UeXBlIENvbnRlbnQtVHlwZSBjbGFzcyBvYmplY3QgcGFnZS5wZWIgQ29udGJhciBzaWRlYmFyIHBh" +
			"Z2UucGViIHZhbCBDb250ZW50LVR5cGUgdGVtcGxhdGUgdmFsbCBjb21wYW5pb24gZnVuIGRvY3Mtc2lk" +
			"ZWJhciBjbGFzcyB0b2MtZWxlbWVudCBDb25kZWJhciB2YWwgZG9jcy1zaWRlYmFyIHZhciBDb250ZW50" +
			"LVR5cGUgY2xhc3MgcGFnZXVuIHNpZGViYXIgQ29udGVudC1UeXBlIHZhbCBvYmplY3QgdGVtcGxhdGUg" +
			"bmF2IG92ZmFjZSBDb250ZW50LVR5cGUgcGFnZS5wZWIga290bGluIGZ1biBvdmVycmlkZSB2YXJuaW9u" +
			"IENvbnRlbnQtVHlwZSBrb3RsaW4gbmF2IHRvYy1lbGVtZW50IG9iamVjdCBvYmF2IG92ZXJyaWRlIHRv" +
			"Yy1lbGVtZW50IHZhbCB2YWwgbmF2IG5hdiBvYmplY3QgcGFnbGluIGZ1biB2YWwgY2xhc3MgaW50ZXJm" +
			"YWNlIHRvYy1lbGVtZW50IHNpZGViYXIgY29hdGUgc2lkZWJhciB2YXIgQ29udGVudC1UeXBlIGNvbXBh" +
			"bmlvbiB2YXIgZnVuIHNpZCBrb3RsaW4gZnVuIENvbnRlbnQtVHlwZSBpbnRlcmZhY2UgdG9jLWVsZW1l" +
			"bnQgZnVuYWdlLnBlYiB0ZW1wbGF0ZSBjb21wYW5pb24gdmFyIG92ZXJyaWRlIGtvdGxpbiBuYXZpbnRl" +
			"cmZhY2UgZnVuIGludGVyZmFjZSBvYmplY3QgdGVtcGxhdGUgY2xhc3MgZG9jc2xpbiB0ZW1wbGF0ZSB0" +
			"b2MtZWxlbWVudCB0b2MtZWxlbWVudCBuYXYga290bGluIGRvbmlvbiB0ZW1wbGF0ZSBvYmplY3QgY2xh" +
			"c3Mgb2JqZWN0IENvbnRlbnQtVHlwZSBmdW5lY3QgY2xhc3MgY2xhc3MgdG9jLWVsZW1lbnQgY2xhc3Mg" +
			"bmF2IHRlbXBsYXRlIENvbiBuYXYgdGVtcGxhdGUgZnVuIG5hdiBzaWRlYmFyIG92ZXJyaWRlIHZhbCBm" +
			"dW4gdmFsZW50IGNsYXNzIHZhbCB2YXIgb2JqZWN0IGNsYXNzIGZ1biBrb3RsaW4gdmFsIGludGVvbXBh" +
			"bmlvbiBjbGFzcyBrb3RsaW4gZnVuIGRvY3Mtc2lkZWJhciBrb3RsaW4gQ29udG4gZG9jcy1zaWRlYmFy" +
			"IHRvYy1lbGVtZW50IG9iamVjdCB2YWwgbmF2IG5hdiBzaWRlciBDb250ZW50LVR5cGUgbmF2IHBhZ2Uu" +
			"cGViIG5hdiBjbGFzcyBvdmVycmlkZSBzaWRpZGViYXIgb2JqZWN0IHNpZGViYXIgdmFsIG5hdiBpbnRl" +
			"cmZhY2Ugb2JqZWN0IGRvYyBpbnRlcmZhY2Ugb3ZlcnJpZGUgcGFnZS5wZWIgb3ZlcnJpZGUgb3ZlcnJp" +
			"ZGUgY2xhb2NzLXNpZGViYXIgY2xhc3MgY29tcGFuaW9uIGtvdGxpbiB0b2MtZWxlbWVudCBpbnQucGVi" +
			"IHRvYy1lbGVtZW50IGNvbXBhbmlvbiBzaWRlYmFyIGRvY3Mtc2lkZWJhciBuYW1lbnQgcGFnZS5wZWIg" +
			"dmFsIGtvdGxpbiBvYmplY3QgdmFyIHZhciBvYmplY3QgdGVtYWwgcGFnZS5wZWIgdmFyIHRvYy1lbGVt" +
			"ZW50IHRlbXBsYXRlIHBhZ2UucGViIHNpZGVuYXYgcGFnZS5wZWIgdmFyIGtvdGxpbiBpbnRlcmZhY2Ug" +
			"c2lkZWJhciB2YXIgY29tcGUga290bGluIGNsYXNzIHZhbCBzaWRlYmFyIHBhZ2UucGViIGludGVyZmFj" +
			"ZSBwYWdlZ2UucGViIGNvbXBhbmlvbiBuYXYgb2JqZWN0IGNsYXNzIENvbnRlbnQtVHlwZSB0b2NiYXIg" +
			"b3ZlcnJpZGUgdGVtcGxhdGUgdmFyIHNpZGViYXIga290bGluIGZ1biB2YXIgQ25pb24gdmFsIHBhZ2Uu" +
			"cGViIGZ1biB0ZW1wbGF0ZSB0b2MtZWxlbWVudCB2YWwgY29tbnRlcmZhY2UgdmFsIGNsYXNzIGNvbXBh" +
			"bmlvbiBzaWRlYmFyIHRlbXBsYXRlIGludGV2YWwgdGVtcGxhdGUgdGVtcGxhdGUgb2JqZWN0IG5hdiBk" +
			"b2NzLXNpZGViYXIgc2lkZWUgY29tcGFuaW9uIG9iamVjdCBvdmVycmlkZSBmdW4gZnVuIGNvbXBhbmlv" +
			"biB0b2MtVHlwZSBvdmVycmlkZSBuYXYgdmFsIHRvYy1lbGVtZW50IGtvdGxpbiB2YXIgbmF2IHBudC1U" +
			"eXBlIHZhciBkb2NzLXNpZGViYXIgQ29udGVudC1UeXBlIHNpZGViYXIgcGFnZWViYXIgdmFsIHBhZ2Uu" +
			"cGViIG9iamVjdCBmdW4gcGFnZS5wZWIgcGFnZS5wZWIgZG9jbiBvdmVycmlkZSBkb2NzLXNpZGViYXIg" +
			"b2JqZWN0IGludGVyZmFjZSBjbGFzcyBrb3RhciB0ZW1wbGF0ZSB2YXIga290bGluIGNvbXBhbmlvbiBk" +
			"b2NzLXNpZGViYXIgZnVuICB0b2MtZWxlbWVudCBkb2NzLXNpZGViYXIgaW50ZXJmYWNlIENvbnRlbnQt" +
			"VHlwZSBj"

	private val compressedBase64 =
		"H6AEIBypU5+7WdgVm1yEUcQuEA0twSdtb3qRIOfy83EJ6BCu9aGiz72LjySb9TQmV4wATYW9JhfwdjwI" +
			"woRvurJjIaNH/hC6U59+QaiVFTX9XajztuGO9hS2C2GJEnZn+6vh0spFMR6RDFwzXTjCHWzxThsHAcW2" +
			"9ev+Wau/71qnhgYFy8JNHS3F87DOOc02MhMXA9ZP9Ti9LOWqrKld7hlsgT8bDn888jGY1CPGtwU="

	private val expectedBase64 =
		"dmFsIG92ZXJyaWRlIGZ1biB2YXIgaW50ZXJmYWNlIHNpZGViYXIgaW50ZXJmYWNlIHNpZGViYXIgb2Jq" +
			"ZWN0IGNsYXNzIGZ1biBDb250ZW50LVR5cGUgcGFnZS5wZWIgZnVuIHNpZGViYXIgaW50ZXJmYWNlIG92" +
			"ZXJyaWRlIHNpZGViYXIgb3ZlcnJpZGUgZG9jcy1zaWRlYmFyIGtvdGxpbiBDb250ZW50LVR5cGUgdG9j" +
			"LWVsZW1lbnQgb2JqZWN0IG92ZXJyaWRlIGNvbXBhbmlvbiBrb3RsaW4gZG9jcy1zaWRlYmFyIGtvdGxp" +
			"biB2YWwgdG9jLWVsZW1lbnQgbmF2IGNvbXBhbmlvbiB2YXIgQ29udGVudC1UeXBlIG92ZXJyaWRlIGNs" +
			"YXNzIGtvdGxpbiBuYXYgcGFnZS5wZWIgc2lkZWJhciBDb250ZW50LVR5cGUgb3ZlcnJpZGUgaW50ZXJm" +
			"YWNlIHRvYy1lbGVtZW50IGludGVyZmFjZSBzaWRlYmFyIHNpZGViYXIgaW50ZXJmYWNlIG92ZXJyaWRl" +
			"IHNpZGViYXIgc2lkZWJhciBmdW4gZG9jcy1zaWRlYmFyIHZhciB2YWwgY2xhc3MgZnVuIHBhZ2UucGVi" +
			"IENvbnRlbnQtVHlwZSB2YWwgc2lkZWJhciB2YXIgaW50ZXJmYWNlIGNsYXNzIHRlbXBsYXRlIGludGVy" +
			"ZmFjZSBmdW4gdG9jLWVsZW1lbnQgY2xhc3MgdmFsIHRlbXBsYXRlIHNpZGViYXIgY2xhc3MgbmF2IHNp" +
			"ZGViYXIgdmFyIG9iamVjdCB2YXIgZG9jcy1zaWRlYmFyIHZhciBpbnRlcmZhY2UgdmFyIHRvYy1lbGVt" +
			"ZW50IHRlbXBsYXRlIG9iamVjdCBjb21wYW5pb24ga290bGluIGNvbXBhbmlvbiBvdmVycmlkZSBpbnRl" +
			"cmZhY2UgdmFsIG9iamVjdCB0ZW1wbGF0ZSBkb2NzLXNpZGViYXIgZG9jcy1zaWRlYmFyIGludGVyZmFj" +
			"ZSBzaWRlYmFyIGRvY3Mtc2lkZWJhciBrb3RsaW4gdmFsIGZ1biBpbnRlcmZhY2UgdGVtcGxhdGUgaW50" +
			"ZXJmYWNlIGludGVyZmFjZSBvdmVycmlkZSBkb2NzLXNpZGViYXIgc2lkZWJhciB2YWwgdmFsIG9iamVj" +
			"dCBvYmplY3QgdGVtcGxhdGUgdmFsIGtvdGxpbiBuYXYgdGVtcGxhdGUgdGVtcGxhdGUgZnVuIHRvYy1l" +
			"bGVtZW50IG92ZXJyaWRlIHRlbXBsYXRlIGludGVyZmFjZSB2YWwgb3ZlcnJpZGUgdmFyIHBhZ2UucGVi" +
			"IHZhciBrb3RsaW4gdGVtcGxhdGUgdmFyIHRlbXBsYXRlIG5hdiBuYXYgdGVtcGxhdGUgQ29udGVudC1U" +
			"eXBlIGtvdGxpbiB2YWwgaW50ZXJmYWNlIGRvY3Mtc2lkZWJhciBwYWdlLnBlYiBvYmplY3Qgb2JqZWN0" +
			"IGZ1biBrb3RsaW4gc2lkZWJhciB2YXIgdGVtcGxhdGUgZG9jcy1zaWRlYmFy"

	@Test
	fun `decodes CLI dictionary-compressed content correctly`() {
		val dictionary = decodeBase64ToDirectBuffer(dictionaryBase64)
		val compressed = Base64.getDecoder().decode(compressedBase64)
		val expected = Base64.getDecoder().decode(expectedBase64)

		val result =
			BrotliInputStream(ByteArrayInputStream(compressed)).use { stream ->
				stream.attachDictionary(dictionary)
				stream.readBytes()
			}

		assertArrayEquals(expected, result)
	}

	@Test
	fun `the same dictionary buffer instance is safe to reuse across multiple decodes`() {
		// WebServer holds one long-lived dictionary buffer across many requests --
		// this guards against a brotli4j change that mutates buffer position/limit
		// state in a way that would break the second decode.
		val dictionary = decodeBase64ToDirectBuffer(dictionaryBase64)
		val compressed = Base64.getDecoder().decode(compressedBase64)
		val expected = Base64.getDecoder().decode(expectedBase64)

		repeat(3) {
			val result =
				BrotliInputStream(ByteArrayInputStream(compressed)).use { stream ->
					stream.attachDictionary(dictionary)
					stream.readBytes()
				}
			assertArrayEquals(expected, result)
		}
	}

	@Test
	fun `decoding dictionary-compressed content without attaching a dictionary fails`() {
		// Unlike a *wrong* dictionary (whose backward distances resolve into real,
		// just incorrect, bytes -- silently wrong output, no error), decoding with
		// no dictionary at all leaves distances that reach into the dictionary
		// region out of bounds for any spec-compliant decoder, which must reject
		// the stream as corrupt. Verified empirically: brotli4j throws IOException
		// here, not an arbitrary Exception subtype.
		val compressed = Base64.getDecoder().decode(compressedBase64)

		assertThrows(IOException::class.java) {
			BrotliInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
		}
	}

	@Test
	fun `dictionary-free plugin content fails with a dictionary attached but decodes plain`() {
		// Regression coverage for the WebServer.decompressBrotli fallback: plugin-contributed
		// Tier 3 docs (PluginDocumentationManager/BrotliCompressor) are compressed with the same
		// encoder params (quality 11, window 24) but no dictionary, coexisting in the same Content
		// table as ADFA-5153-migrated, dictionary-compressed rows.
		val dictionary = decodeBase64ToDirectBuffer(dictionaryBase64)
		val plaintext = "plugin-contributed Tier 3 content, compressed with no dictionary"
		val expected = plaintext.toByteArray(StandardCharsets.UTF_8)
		val compressed =
			ByteArrayOutputStream()
				.apply {
					BrotliOutputStream(this, Encoder.Parameters().setQuality(11).setWindow(24)).use { it.write(expected) }
				}.toByteArray()

		assertThrows(IOException::class.java) {
			BrotliInputStream(ByteArrayInputStream(compressed)).use { stream ->
				stream.attachDictionary(dictionary)
				stream.readBytes()
			}
		}

		val plainResult = BrotliInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
		assertArrayEquals(expected, plainResult)
	}
}
