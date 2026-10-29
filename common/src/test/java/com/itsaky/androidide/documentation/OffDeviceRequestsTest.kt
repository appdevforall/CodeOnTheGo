package com.itsaky.androidide.documentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.utils.UrlManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
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
			"http://[2001:db8::1]/x",
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
			// Chromium ends the authority at a backslash, so the host is evil.com.
			"http://evil.com\\@localhost/",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isTrue()
		}
	}

	@Test
	fun `an http URL that cannot be read is treated as off the device`() {
		for (url in listOf("http://exa mple.com/", "HTTP://exa mple.com/", "https://example.com/a b", "http:///no-host", "https:")) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isTrue()
		}
	}

	@Test
	fun `spaces around a URL do not hide its host`() {
		// Chromium drops them before parsing, and tabs and line breaks anywhere, so these load from
		// the host inside.
		assertThat(OffDeviceRequests.isOffDevice(" http://evil.com/x|y")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("\thttps://evil.com/\n")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("http\n://evil.com/x")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("ht\ttp://evil.com/x")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("https\r\n://evil.com/x")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice(" http://localhost:6174/x|y ")).isFalse()
	}

	@Test
	fun `a local URL that java_net_URI rejects is still on the device`() {
		// Chromium leaves these characters as they are in a canonical URL; java.net.URI refuses them.
		for (url in listOf(
			"http://localhost:6174/i/x.html?a=1|2",
			"http://localhost:6174/i/%zz.html",
			"http://127.0.0.1:6174/p.html#b|c",
			"http://[::1]:6174/q.html?{y}",
		)) {
			assertWithMessage(url).that(OffDeviceRequests.isOffDevice(url)).isFalse()
		}
		assertThat(OffDeviceRequests.isOffDevice("http://example.com/i/x.html?a=1|2")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("http://localhost@example.com/%zz")).isTrue()
		assertThat(OffDeviceRequests.isOffDevice("http://[2001:db8::1]/x|y")).isTrue()
		// The @ is in the query, not the authority: the host is evil.com.
		assertThat(OffDeviceRequests.isOffDevice("http://evil.com/x|y?u=a@localhost:1")).isTrue()
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

	@Test
	fun `the consent dialog uses the first context that is or wraps an Activity`() {
		val activity = mockk<Activity>()
		val plain = mockk<Context>()
		val wrapsActivity = mockk<ContextWrapper> { every { baseContext } returns activity }
		val wrapsWrapper = mockk<ContextWrapper> { every { baseContext } returns wrapsActivity }
		val wrapsPlain = mockk<ContextWrapper> { every { baseContext } returns plain }

		assertThat(OffDeviceRequests.activityFor(activity, plain)).isSameInstanceAs(activity)
		assertThat(OffDeviceRequests.activityFor(plain, wrapsActivity)).isSameInstanceAs(activity)
		assertThat(OffDeviceRequests.activityFor(wrapsPlain, wrapsWrapper)).isSameInstanceAs(activity)
		assertThat(OffDeviceRequests.activityFor(null, wrapsActivity)).isSameInstanceAs(activity)
		assertThat(OffDeviceRequests.activityFor(plain, wrapsPlain)).isNull()
		assertThat(OffDeviceRequests.activityFor()).isNull()
	}

	@Test
	fun `keepOnDevice turns Safe Browsing off and blocks service-worker fetches`() {
		val settings = mockk<WebSettings>(relaxed = true)
		val controller = mockk<ServiceWorkerController>(relaxed = true)
		val client = slot<ServiceWorkerClient>()
		mockkStatic(ServiceWorkerController::class)
		every { ServiceWorkerController.getInstance() } returns controller
		every { controller.setServiceWorkerClient(capture(client)) } returns Unit

		OffDeviceRequests.keepOnDevice(settings)

		verify(exactly = 1) { settings.safeBrowsingEnabled = false }
		verify(exactly = 0) { settings.safeBrowsingEnabled = true }
		assertThat(client.captured.shouldInterceptRequest(request("https://example.com/sw.js"))).isNotNull()
		assertThat(client.captured.shouldInterceptRequest(request("http://localhost:6174/sw.js"))).isNull()
	}

	private fun request(url: String): WebResourceRequest {
		// Uri is a framework class, so stand in for the one call the guard makes.
		val uri = mockk<Uri>()
		every { uri.toString() } returns url
		return mockk { every { this@mockk.url } returns uri }
	}
}
