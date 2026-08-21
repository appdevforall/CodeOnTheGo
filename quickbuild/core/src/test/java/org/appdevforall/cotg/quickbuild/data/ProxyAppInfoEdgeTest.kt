package org.appdevforall.cotg.quickbuild.data

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Malformed-input and alias/fallback paths of [ProxyAppInfo.parse], complementing
 * [ProxyAppInfoTest]'s happy paths: a setup.json written by any past or future plugin
 * version must either parse to the right value or fail to null - never crash.
 */
class ProxyAppInfoEdgeTest {
	private val baseDir = File("/project")

	private fun json(extra: String = "") =
		"""
		{
			"proxyAppId": "com.example.app.quickbuild",
			"entryActivity": "com.example.app.MainActivity",
			"apkPath": "/apk/app-debug.apk"
			$extra
		}
		""".trimIndent()

	@Test
	fun `non-JSON text parses to null`() {
		assertThat(ProxyAppInfo.parse("not json at all", baseDir)).isNull()
	}

	@Test
	fun `a JSON array is not a setup object`() {
		assertThat(ProxyAppInfo.parse("""["proxyAppId"]""", baseDir)).isNull()
	}

	@Test
	fun `missing proxyAppId is a parse failure`() {
		val text = """{"entryActivity":"com.example.Main","apkPath":"/apk/app.apk"}"""

		assertThat(ProxyAppInfo.parse(text, baseDir)).isNull()
	}

	@Test
	fun `missing apk is a parse failure`() {
		val text = """{"proxyAppId":"com.example.app.quickbuild"}"""

		assertThat(ProxyAppInfo.parse(text, baseDir)).isNull()
	}

	@Test
	fun `a blank proxyAppId falls through to the next alias`() {
		val text =
			"""{"proxyAppId":"  ","testAppId":"com.example.legacy","apk":"/apk/app.apk"}"""

		val info = ProxyAppInfo.parse(text, baseDir)

		assertThat(info!!.proxyAppPackage).isEqualTo("com.example.legacy")
	}

	@Test
	fun `a non-primitive alias value falls through to the next alias`() {
		val text =
			"""{"proxyAppId":{"v":1},"applicationId":"com.example.obj","apkFile":"/apk/app.apk"}"""

		val info = ProxyAppInfo.parse(text, baseDir)

		assertThat(info!!.proxyAppPackage).isEqualTo("com.example.obj")
		assertThat(info.apk).isEqualTo(File("/apk/app.apk"))
	}

	@Test
	fun `relative paths resolve against the base dir and absolute paths stand`() {
		val info =
			ProxyAppInfo.parse(
				json(
					"""
					,
					"classpath": ["libs/a.jar", "/abs/b.jar"],
					"proxyClassesDir": "build/proxy-classes",
					"manifestPath": "/abs/AndroidManifest.xml"
					""".trimIndent(),
				),
				baseDir,
			)

		assertThat(info!!.classpath)
			.containsExactly(File("/project/libs/a.jar"), File("/abs/b.jar"))
			.inOrder()
		assertThat(info.proxyClassesDir).isEqualTo(File("/project/build/proxy-classes"))
		assertThat(info.transformedManifest).isEqualTo(File("/abs/AndroidManifest.xml"))
	}

	@Test
	fun `payloadJars ride the classpath after the compile classpath`() {
		val info =
			ProxyAppInfo.parse(
				json(""","classpath": ["libs/a.jar"], "payloadJars": ["build/R.jar", {"bad": 1}]"""),
				baseDir,
			)

		assertThat(info!!.classpath)
			.containsExactly(File("/project/libs/a.jar"), File("/project/build/R.jar"))
			.inOrder()
	}

	@Test
	fun `non-primitive classpath entries are dropped`() {
		val info = ProxyAppInfo.parse(json(""","classpath": [["nested"], "libs/a.jar"]"""), baseDir)

		assertThat(info!!.classpath).containsExactly(File("/project/libs/a.jar"))
	}

	@Test
	fun `optional file fields default to null when absent`() {
		val info = ProxyAppInfo.parse(json(), baseDir)

		assertThat(info!!.proxyClassesDir).isNull()
		assertThat(info.transformedManifest).isNull()
	}

	@Test
	fun `transformedManifest alias parses too`() {
		val info = ProxyAppInfo.parse(json(""","transformedManifest": "build/Merged.xml""""), baseDir)

		assertThat(info!!.transformedManifest).isEqualTo(File("/project/build/Merged.xml"))
	}

	@Test
	fun `a numeric composeEnabled reads as false`() {
		val info = ProxyAppInfo.parse(json(""","composeEnabled": 1"""), baseDir)

		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `a non-numeric schema reads as the pre-v2 baseline`() {
		val info = ProxyAppInfo.parse(json(""","schema": "2""""), baseDir)

		assertThat(info!!.schema).isEqualTo(0)
		assertThat(info.supportsComponentInfo).isFalse()
	}

	@Test
	fun `schema at the component version supports component info`() {
		val info = ProxyAppInfo.parse(json(""","schema": ${ProxyAppInfo.COMPONENT_SCHEMA_VERSION}"""), baseDir)

		assertThat(info!!.supportsComponentInfo).isTrue()
	}

	@Test
	fun `blank and non-primitive annotationProcessors entries are dropped`() {
		val info =
			ProxyAppInfo.parse(
				json(""","annotationProcessors": ["androidx.room:room-compiler", "  ", {"o":1}]"""),
				baseDir,
			)

		assertThat(info!!.annotationProcessors).containsExactly("androidx.room:room-compiler")
	}

	@Test
	fun `every declared component kind parses to its enum`() {
		val info =
			ProxyAppInfo.parse(
				json(
					"""
					,
					"schema": 2,
					"components": [
						{"type": "activity", "userClass": "com.example.A"},
						{"type": "service", "userClass": "com.example.S"},
						{"type": "receiver", "userClass": "com.example.R"},
						{"type": "provider", "userClass": "com.example.P"},
						{"type": "application", "userClass": "com.example.App"}
					]
					""".trimIndent(),
				),
				baseDir,
			)

		assertThat(info!!.components.map { it.kind })
			.containsExactly(
				ComponentKind.ACTIVITY,
				ComponentKind.SERVICE,
				ComponentKind.RECEIVER,
				ComponentKind.PROVIDER,
				ComponentKind.APPLICATION,
			).inOrder()
	}

	@Test
	fun `a component with a non-boolean launcher parses as not launcher`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""","components": [{"type": "activity", "userClass": "com.example.A", "launcher": "yes"}]""",
				),
				baseDir,
			)

		assertThat(info!!.components.single().launcher).isFalse()
	}

	@Test
	fun `component supertypes drop non-primitive entries`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""","components": [{"type": "activity", "userClass": "com.example.A",""" +
						""""supertypes": ["android.app.Activity", {"o":1}]}]""",
				),
				baseDir,
			)

		assertThat(info!!.components.single().supertypes).containsExactly("android.app.Activity")
	}

	@Test
	fun `a component without supertypes parses with none`() {
		val info =
			ProxyAppInfo.parse(
				json(""","components": [{"type": "activity", "userClass": "com.example.A"}]"""),
				baseDir,
			)

		val component = info!!.components.single()
		assertThat(component.supertypes).isEmpty()
		assertThat(component.proxyClass).isNull()
	}

	@Test
	fun `an explicit composeEnabled false parses as false`() {
		val info = ProxyAppInfo.parse(json(""","composeEnabled": false"""), baseDir)

		assertThat(info!!.composeEnabled).isFalse()
	}

	@Test
	fun `a JSON-null schema reads as the pre-v2 baseline`() {
		val info = ProxyAppInfo.parse(json(""","schema": null"""), baseDir)

		assertThat(info!!.schema).isEqualTo(0)
	}

	@Test
	fun `a component with an explicit launcher false parses as not launcher`() {
		val info =
			ProxyAppInfo.parse(
				json(
					""","components": [{"type": "activity", "userClass": "com.example.A", "launcher": false}]""",
				),
				baseDir,
			)

		assertThat(info!!.components.single().launcher).isFalse()
	}

	@Test
	fun `a JSON-null alias value falls through to the next alias`() {
		val text =
			"""{"proxyAppId": null, "testAppPackage": "com.example.nulled", "apk": "/apk/app.apk"}"""

		val info = ProxyAppInfo.parse(text, baseDir)

		assertThat(info!!.proxyAppPackage).isEqualTo("com.example.nulled")
	}

	@Test
	fun `sourceRoots resolve against the base dir`() {
		val info =
			ProxyAppInfo.parse(
				json(""","sourceRoots": ["src/main/java", "/abs/generated"]"""),
				baseDir,
			)

		assertThat(info!!.sourceRoots)
			.containsExactly(File("/project/src/main/java"), File("/abs/generated"))
			.inOrder()
	}
}
