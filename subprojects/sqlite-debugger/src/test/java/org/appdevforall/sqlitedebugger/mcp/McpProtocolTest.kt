package org.appdevforall.sqlitedebugger.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * MCP control without Android: the protocol (sessions, methods, tool results and errors) and the
 * HTTP side (token, Host and Origin checks, methods and paths), on a real loopback socket.
 */
class McpProtocolTest {

	private var sessionNumber = 0
	private val echo = McpTool("echo", "Echo", "Returns its arguments.") { args -> JSONObject().put("got", args) }
	private val needsPerson = McpTool("needs_person", "Needs a person", "Always says a person is needed.", readOnly = false) { _ ->
		throw McpToolError("A person is needed: tap Allow on the sharing phone.", JSONObject().put("person_needed", true))
	}
	private val crashes = McpTool("crashes", "Crashes", "Throws an unexpected exception.") { _ -> throw IllegalStateException("boom") }

	private fun protocol(maxSessions: Int = 16) =
		McpProtocol(listOf(echo, needsPerson, crashes), "test", { "s${++sessionNumber}" }, maxSessions)

	private fun initBody(version: String = "2025-06-18") = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
		.put("params", JSONObject().put("protocolVersion", version).put("capabilities", JSONObject())
			.put("clientInfo", JSONObject().put("name", "test").put("version", "1"))).toString()

	private fun request(id: Any, method: String, params: JSONObject? = null) = JSONObject().put("jsonrpc", "2.0").put("id", id)
		.put("method", method).also { if (params != null) it.put("params", params) }

	private fun session(p: McpProtocol): String {
		val r = p.post(initBody(), null, null)
		assertEquals(200, r.status)
		return r.headers[McpProtocol.SESSION_HEADER]!!
	}

	private fun call(p: McpProtocol, s: String, tool: String, args: JSONObject? = null): JSONObject {
		val params = JSONObject().put("name", tool).also { if (args != null) it.put("arguments", args) }
		val r = p.post(request(7, "tools/call", params).toString(), s, null)
		assertEquals(200, r.status)
		return JSONObject(r.body!!)
	}

	// ---------------------------------------------------------------- the protocol

	@Test fun initializeAgreesVersionAndStartsSession() {
		val p = protocol()
		val r = p.post(initBody("2025-03-26"), null, null)
		assertEquals(200, r.status)
		assertEquals("s1", r.headers[McpProtocol.SESSION_HEADER])
		val result = JSONObject(r.body!!).getJSONObject("result")
		assertEquals("2025-03-26", result.getString("protocolVersion"))
		assertEquals(McpProtocol.SERVER_NAME, result.getJSONObject("serverInfo").getString("name"))
		assertTrue(result.getJSONObject("capabilities").has("tools"))
		assertEquals(1, p.sessionCount())
	}

	@Test fun unknownVersionGetsTheNewest() {
		val r = protocol().post(initBody("1999-01-01"), null, null)
		assertEquals(McpProtocol.VERSIONS[0], JSONObject(r.body!!).getJSONObject("result").getString("protocolVersion"))
	}

	@Test fun requestsNeedALiveSession() {
		val p = protocol()
		assertEquals(400, p.post(request(1, "ping").toString(), null, null).status)
		assertEquals(404, p.post(request(1, "ping").toString(), "nope", null).status)
		val s = session(p)
		assertEquals(200, p.post(request(1, "ping").toString(), s, null).status)
	}

	@Test fun unsupportedVersionHeaderIsRefused() {
		val p = protocol(); val s = session(p)
		assertEquals(400, p.post(request(1, "ping").toString(), s, "1999-01-01").status)
		assertEquals(200, p.post(request(1, "ping").toString(), s, "2025-06-18").status)
	}

	@Test fun notificationsGetNoAnswer() {
		val p = protocol(); val s = session(p)
		val r = p.post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), s, null)
		assertEquals(202, r.status)
		assertNull(r.body)
	}

	@Test fun pingAnswersEmptyResult() {
		val p = protocol(); val s = session(p)
		val r = JSONObject(p.post(request("a", "ping").toString(), s, null).body!!)
		assertEquals("a", r.get("id"))
		assertEquals(0, r.getJSONObject("result").length())
	}

	@Test fun toolsListDescribesEveryTool() {
		val p = protocol(); val s = session(p)
		val tools = JSONObject(p.post(request(2, "tools/list").toString(), s, null).body!!).getJSONObject("result").getJSONArray("tools")
		val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
		assertEquals(listOf("echo", "needs_person", "crashes"), names)
		val first = tools.getJSONObject(0)
		assertEquals("object", first.getJSONObject("inputSchema").getString("type"))
		assertTrue(first.getJSONObject("annotations").getBoolean("readOnlyHint"))
		assertFalse(tools.getJSONObject(1).getJSONObject("annotations").getBoolean("readOnlyHint"))
	}

	@Test fun toolCallReturnsStructuredContent() {
		val p = protocol(); val s = session(p)
		val result = call(p, s, "echo", JSONObject().put("x", 3)).getJSONObject("result")
		assertFalse(result.getBoolean("isError"))
		assertEquals(3, result.getJSONObject("structuredContent").getJSONObject("got").getInt("x"))
		assertEquals("text", result.getJSONArray("content").getJSONObject(0).getString("type"))
	}

	@Test fun toolCallWithoutArgumentsGetsEmptyObject() {
		val p = protocol(); val s = session(p)
		val got = call(p, s, "echo").getJSONObject("result").getJSONObject("structuredContent").getJSONObject("got")
		assertEquals(0, got.length())
	}

	@Test fun toolErrorIsAResultWithIsError() {
		val p = protocol(); val s = session(p)
		val result = call(p, s, "needs_person").getJSONObject("result")
		assertTrue(result.getBoolean("isError"))
		assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("person is needed"))
		assertTrue(result.getJSONObject("structuredContent").getBoolean("person_needed"))
	}

	@Test fun unexpectedExceptionIsAResultWithIsError() {
		val p = protocol(); val s = session(p)
		val result = call(p, s, "crashes").getJSONObject("result")
		assertTrue(result.getBoolean("isError"))
		assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("boom"))
	}

	@Test fun unknownToolAndBadArgumentsAreInvalidParams() {
		val p = protocol(); val s = session(p)
		assertEquals(McpProtocol.INVALID_PARAMS, call(p, s, "nope").getJSONObject("error").getInt("code"))
		val bad = request(3, "tools/call", JSONObject().put("name", "echo").put("arguments", JSONArray())).toString()
		assertEquals(McpProtocol.INVALID_PARAMS, JSONObject(p.post(bad, s, null).body!!).getJSONObject("error").getInt("code"))
	}

	@Test fun unknownMethodIsMethodNotFound() {
		val p = protocol(); val s = session(p)
		val r = JSONObject(p.post(request(4, "resources/list").toString(), s, null).body!!)
		assertEquals(McpProtocol.METHOD_NOT_FOUND, r.getJSONObject("error").getInt("code"))
	}

	@Test fun badJsonIsAParseError() {
		val r = protocol().post("{not json", null, null)
		assertEquals(400, r.status)
		assertEquals(McpProtocol.PARSE_ERROR, JSONObject(r.body!!).getJSONObject("error").getInt("code"))
	}

	@Test fun batchGetsAnArrayOfReplies() {
		val p = protocol(); val s = session(p)
		val batch = JSONArray().put(request(1, "ping")).put(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/x")).put(request(2, "tools/list"))
		val r = p.post(batch.toString(), s, null)
		assertEquals(200, r.status)
		val replies = JSONArray(r.body!!)
		assertEquals(2, replies.length())
		assertEquals(1, replies.getJSONObject(0).getInt("id"))
		assertEquals(2, replies.getJSONObject(1).getInt("id"))
	}

	@Test fun initializeMustBeAlone() {
		val batch = JSONArray().put(JSONObject(initBody())).put(request(2, "ping"))
		assertEquals(400, protocol().post(batch.toString(), null, null).status)
	}

	@Test fun deleteEndsTheSession() {
		val p = protocol(); val s = session(p)
		assertEquals(204, p.delete(s).status)
		assertEquals(404, p.delete(s).status)
		assertEquals(404, p.post(request(1, "ping").toString(), s, null).status)
	}

	@Test fun oldestSessionIsDroppedBeyondTheLimit() {
		val p = protocol(maxSessions = 2)
		val a = session(p); val b = session(p)
		p.post(request(1, "ping").toString(), a, null)   // a is now the most recently used
		val c = session(p)                                  // drops b
		assertEquals(2, p.sessionCount())
		assertEquals(200, p.post(request(1, "ping").toString(), a, null).status)
		assertEquals(404, p.post(request(1, "ping").toString(), b, null).status)
		assertEquals(200, p.post(request(1, "ping").toString(), c, null).status)
	}

	@Test fun endAllEndsEverySession() {
		val p = protocol(); val s = session(p)
		p.endAll()
		assertEquals(0, p.sessionCount())
		assertEquals(404, p.post(request(1, "ping").toString(), s, null).status)
	}

	// ---------------------------------------------------------------- the HTTP side

	private val token = "secret-token"
	private var server: McpHttp? = null

	@After fun stopServer() { server?.stop(); server = null }

	private fun startServer(): Int {
		val s = McpHttp(protocol(), { token }, port = 0)
		s.start()
		server = s
		return s.boundPort
	}

	private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

	/** One request on its own connection; [headers] may replace Host. */
	private fun http(port: Int, method: String, path: String = McpHttp.PATH, headers: Map<String, String> = emptyMap(), body: String? = null): Answer {
		Socket("127.0.0.1", port).use { s ->
			s.soTimeout = 5000
			val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
			val all = linkedMapOf("Host" to "127.0.0.1:$port", "Connection" to "close")
			all.putAll(headers)
			if (body != null) { all["Content-Type"] = "application/json"; all["Content-Length"] = bytes.size.toString() }
			val head = StringBuilder("$method $path HTTP/1.1\r\n")
			for ((k, v) in all) head.append(k).append(": ").append(v).append("\r\n")
			head.append("\r\n")
			s.getOutputStream().apply { write(head.toString().toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
			val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
			val lines = text.substringBefore("\r\n\r\n").split("\r\n")
			val status = lines[0].split(' ')[1].toInt()
			val h = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
			return Answer(status, h, text.substringAfter("\r\n\r\n", ""))
		}
	}

	private val auth get() = mapOf("Authorization" to "Bearer $token")

	@Test fun tokenIs32BytesUrlSafeAndNew() {
		val a = McpHttp.newToken()
		assertEquals(43, a.length)
		assertTrue(a.matches(Regex("[A-Za-z0-9_-]{43}")))
		assertTrue(a != McpHttp.newToken())
	}

	@Test fun base64MatchesTheJdkEncoder() {
		for (n in 0..40) {
			val bytes = ByteArray(n) { (it * 37 + 250).toByte() }
			assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), McpHttp.base64(bytes, urlSafe = false, pad = true))
			assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), McpHttp.base64(bytes, urlSafe = true, pad = false))
		}
	}

	@Test fun httpPortZeroBindsAFreePortOnLoopback() {
		val port = startServer()
		assertTrue(port in 1..65535)
		assertEquals("127.0.0.1", java.net.Socket("127.0.0.1", port).use { it.inetAddress.hostAddress })
	}

	@Test fun httpNinthConnectionIsRefused() {
		val port = startServer()
		val held = (1..McpHttp.MAX_CONNECTIONS).map { Socket("127.0.0.1", port) }
		try {
			Thread.sleep(300)
			Socket("127.0.0.1", port).use { s ->
				s.soTimeout = 5000
				val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
				assertTrue(text.startsWith("HTTP/1.1 503"))
			}
		} finally { held.forEach { it.close() } }
	}

	@Test fun httpNeedsTheToken() {
		val port = startServer()
		val none = http(port, "POST", body = initBody())
		assertEquals(401, none.status)
		assertNotNull(none.headers["www-authenticate"])
		assertEquals(401, http(port, "POST", headers = mapOf("Authorization" to "Bearer wrong"), body = initBody()).status)
		assertFalse("the token never appears in a refusal", none.body.contains(token))
	}

	@Test fun httpInitializeWithTheToken() {
		val port = startServer()
		val r = http(port, "POST", headers = auth, body = initBody())
		assertEquals(200, r.status)
		assertNotNull(r.headers["mcp-session-id"])
		assertEquals("2025-06-18", JSONObject(r.body).getJSONObject("result").getString("protocolVersion"))
	}

	@Test fun httpLocalhostHostIsAccepted() {
		val port = startServer()
		assertEquals(200, http(port, "POST", headers = auth + ("Host" to "localhost:$port"), body = initBody()).status)
	}

	@Test fun httpOtherHostIsRefused() {
		val port = startServer()
		assertEquals(403, http(port, "POST", headers = auth + ("Host" to "evil.example"), body = initBody()).status)
		assertEquals(403, http(port, "POST", headers = auth + ("Host" to "127.0.0.1:x"), body = initBody()).status)
	}

	@Test fun httpWebPageOriginIsRefused() {
		val port = startServer()
		assertEquals(403, http(port, "POST", headers = auth + ("Origin" to "https://evil.example"), body = initBody()).status)
		assertEquals(200, http(port, "POST", headers = auth + ("Origin" to "http://localhost:3000"), body = initBody()).status)
	}

	@Test fun httpGetIsNotAllowed() {
		val port = startServer()
		val r = http(port, "GET", headers = auth)
		assertEquals(405, r.status)
		assertEquals("POST, DELETE", r.headers["allow"])
	}

	@Test fun httpOtherPathIsNotFound() {
		assertEquals(404, http(startServer(), "POST", path = "/other", headers = auth, body = initBody()).status)
	}

	@Test fun httpWrongContentTypeIsRefused() {
		val port = startServer()
		Socket("127.0.0.1", port).use { s ->
			val body = initBody().toByteArray()
			s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nConnection: close\r\n" +
				"Content-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
			val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
			assertTrue(text.startsWith("HTTP/1.1 415"))
		}
	}

	@Test fun httpSessionThenDelete() {
		val port = startServer()
		val s = http(port, "POST", headers = auth, body = initBody()).headers["mcp-session-id"]!!
		val ping = request(1, "ping").toString()
		assertEquals(200, http(port, "POST", headers = auth + ("Mcp-Session-Id" to s), body = ping).status)
		assertEquals(204, http(port, "DELETE", headers = auth + ("Mcp-Session-Id" to s)).status)
		assertEquals(404, http(port, "POST", headers = auth + ("Mcp-Session-Id" to s), body = ping).status)
	}
}
