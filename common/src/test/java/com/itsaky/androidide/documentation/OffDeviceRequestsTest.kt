package com.itsaky.androidide.documentation

import android.app.Activity
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.UrlManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

/**
 * Which URLs [OffDeviceRequests] keeps from leaving the device (ADFA-6589). A documentation page
 * that reaches the internet is a critical bug, so the cases below lean on the shapes that could
 * slip past a host check: userinfo, look-alike hosts, case, and URLs that do not parse.
 */
class OffDeviceRequestsTest {
	@After
	fun tearDown() {
		unmockkAll()
	}

	@Test
	fun `the device itself is on the device`() {
		for (url in listOf(
			"http://localhost:6174/i/index.html",
			"http://localhost/",
			"HTTP://LOCALHOST:6174/i/index.html",
			"https://localhost:8443/x",
			"http://127.0.0.1:6174/x",
			"http://127.10.20.30/x",
			"http://[::1]:6174/x",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isFalse()
		}
	}

	@Test
	fun `every other host is off the device`() {
		for (url in listOf(
			"https://fonts.googleapis.com/css?family=Roboto:400,700",
			"https://www.youtube.com/embed/abc",
			"https://developer.android.com/static/images/x.png",
			"HTTPS://KOTL.IN/x",
			"http://192.168.1.10:6174/x",
			"http://10.0.2.2:6174/x",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isTrue()
		}
	}

	@Test
	fun `a host that only mentions localhost is off the device`() {
		for (url in listOf(
			"http://localhost.example.com/",
			"http://example-localhost/",
			"http://localhost@example.com/",
			"http://localhost:6174@example.com/",
			"http://example.com/?next=http://localhost:6174/",
			"http://example.com/#localhost",
			"http://127.0.0.1.example.com/",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isTrue()
		}
	}

	@Test
	fun `an http URL that cannot be read is treated as off the device`() {
		for (url in listOf("http://exa mple.com/", "https://example.com/a b", "http:///no-host", "https:")) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isTrue()
		}
	}

	@Test
	fun `other schemes never leave the device`() {
		for (url in listOf(
			"file:///android_asset/x.html",
			"data:text/html,<p>hi</p>",
			"about:blank",
			"blob:http://localhost:6174/1234",
			"javascript:void(0)",
			"mailto:someone@example.com",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isFalse()
		}
	}

	@Test
	fun `an off-device request gets a local response, not the network`() {
		// WebResourceResponse is a framework class: stand in for its constructor.
		mockkConstructor(WebResourceResponse::class)

		assertThat(OffDeviceRequests.block(request("https://fonts.googleapis.com/css?family=Roboto"))).isNotNull()
		assertThat(OffDeviceRequests.block(request("http://localhost@example.com/x.png"))).isNotNull()
	}

	@Test
	fun `a request that stays on the device goes on to the interceptor and the server`() {
		assertThat(OffDeviceRequests.block(request("http://localhost:6174/pr/db"))).isNull()
		assertThat(OffDeviceRequests.block(request("data:text/css,"))).isNull()
	}

	@Test
	fun `only an off-device link is sent through the consent dialog`() {
		mockkObject(UrlManager)
		every { UrlManager.openUrl(any(), any(), any()) } just runs
		val activity = mockk<Activity>()

		assertThat(OffDeviceRequests.openWithConsent(activity, "https://kotl.in/x")).isTrue()
		verify(exactly = 1) { UrlManager.openUrl("https://kotl.in/x", null, activity) }

		assertThat(OffDeviceRequests.openWithConsent(activity, "http://localhost:6174/i/index.html")).isFalse()
		assertThat(OffDeviceRequests.openWithConsent(activity, "file:///android_asset/x.html")).isFalse()
		verify(exactly = 1) { UrlManager.openUrl(any(), any(), any()) }
	}

	private fun request(url: String): WebResourceRequest {
		// Uri is a framework class, so stand in for the one call the guard makes.
		val uri = mockk<Uri>()
		every { uri.toString() } returns url
		return mockk { every { this@mockk.url } returns uri }
	}
}
