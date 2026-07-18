package com.itsaky.androidide.gradle.quickbuild

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * One `<activity>` of the user's merged manifest, paired with the proxy generated for it.
 *
 * @property userClass fully-qualified user activity class.
 * @property proxyClass fully-qualified generated proxy class that replaces it in the manifest.
 * @property isLauncher whether the activity declares the MAIN/LAUNCHER intent filter.
 */
data class ProxiedActivity(
	val userClass: String,
	val proxyClass: String,
	val isLauncher: Boolean,
)

/**
 * Result of rewriting a merged manifest for the Quick Build test app.
 */
class ManifestTransformResult(
	val document: Document,
	val activities: List<ProxiedActivity>,
) {
	/** User class of the LAUNCHER activity, or null when the manifest declares none. */
	val entryActivity: String?
		get() = activities.firstOrNull { it.isLauncher }?.userClass
}

/**
 * Rewrites a merged Android manifest into the Quick Build test-app manifest (plan 2.2):
 * every `<activity>` android:name is replaced with a generated proxy FQN (stable component
 * names while the user's classes stay swappable in the payload dex), and the
 * `<application>` gains an android:appComponentFactory pointing at the quick-build runtime
 * so component instantiation goes through the payload classloader. Everything else
 * (permissions, icon, label, intent filters) is preserved.
 *
 * Pure logic, no Gradle types - unit-testable without a build.
 *
 * @property proxyPackage package for generated proxies, e.g. `com.example.app.quickbuild.proxies`.
 * @property appComponentFactory FQN of the runtime's AppComponentFactory.
 */
class QuickBuildManifestTransformer(
	private val proxyPackage: String,
	private val appComponentFactory: String,
) {
	companion object {
		const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
		private const val ACTION_MAIN = "android.intent.action.MAIN"
		private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

		/** Manifest-order proxy class simple name; must stay stable across pieces. */
		fun proxySimpleName(index: Int): String = "Proxy${index}Activity"
	}

	/**
	 * Parses and rewrites the manifest. Throws [IllegalArgumentException] on a manifest the
	 * quick path cannot handle (no `<application>`, an `<activity>` without android:name) -
	 * the calling task turns that into a failed setup build with the message intact.
	 */
	fun transform(input: InputStream): ManifestTransformResult {
		val document = newDocumentBuilderFactory().newDocumentBuilder().parse(input)
		val manifestPackage = document.documentElement?.getAttribute("package").orEmpty()

		val application =
			document.getElementsByTagName("application").item(0) as? Element
				?: throw IllegalArgumentException("merged manifest has no <application> element")

		application.setAttributeNS(ANDROID_NS, "android:appComponentFactory", appComponentFactory)

		val activities = mutableListOf<ProxiedActivity>()
		application.childElements("activity").forEachIndexed { index, activity ->
			val name = activity.getAttributeNS(ANDROID_NS, "name")
			if (name.isBlank()) {
				throw IllegalArgumentException("<activity> at index $index has no android:name")
			}

			val userClass = resolveClassName(name, manifestPackage)
			val proxyClass = "$proxyPackage.${proxySimpleName(index)}"
			activity.setAttributeNS(ANDROID_NS, "android:name", proxyClass)
			activities.add(ProxiedActivity(userClass, proxyClass, isLauncher(activity)))
		}

		// An <activity-alias> targeting a rewritten activity must follow it to the proxy,
		// or the alias would reference a component the manifest no longer declares.
		val byUserClass = activities.associateBy { it.userClass }
		application.childElements("activity-alias").forEach { alias ->
			val target = alias.getAttributeNS(ANDROID_NS, "targetActivity")
			if (target.isNotBlank()) {
				byUserClass[resolveClassName(target, manifestPackage)]?.let { proxied ->
					alias.setAttributeNS(ANDROID_NS, "android:targetActivity", proxied.proxyClass)
				}
			}
		}

		inlineLibraryResourceRefs(document)

		return ManifestTransformResult(document, activities)
	}

	/**
	 * The on-device resource relink links ONLY the app's own res/ against this manifest, so
	 * a manifest reference to a LIBRARY-provided resource aborts every resource hot reload
	 * with aapt2 "resource not found". CoGo's LogSenderPlugin injects exactly one such
	 * reference into every debug app (android:enabled="@bool/logsender_enabled"; the bool is
	 * true in the logsender AAR), so inline it here. A general fix - relinking against the
	 * base APK's resource table - is a tracked followup; until then any NEW library manifest
	 * ref hits the same wall.
	 */
	private fun inlineLibraryResourceRefs(document: Document) {
		val all = document.getElementsByTagName("*")
		for (i in 0 until all.length) {
			val element = all.item(i) as? Element ?: continue
			val attrs = element.attributes
			for (j in 0 until attrs.length) {
				val attr = attrs.item(j) as? Attr ?: continue
				if (attr.value == "@bool/logsender_enabled") {
					attr.value = "true"
				}
			}
		}
	}

	/** Serializes a transformed manifest to [file]. */
	fun writeTo(
		document: Document,
		file: File,
	) {
		file.parentFile?.mkdirs()
		val transformer = TransformerFactory.newInstance().newTransformer()
		transformer.setOutputProperty(OutputKeys.INDENT, "yes")
		transformer.transform(DOMSource(document), StreamResult(file))
	}

	private fun isLauncher(activity: Element): Boolean =
		activity.childElements("intent-filter").any { filter ->
			filter.childElements("action").any {
				it.getAttributeNS(ANDROID_NS, "name") == ACTION_MAIN
			} &&
				filter.childElements("category").any {
					it.getAttributeNS(ANDROID_NS, "name") == CATEGORY_LAUNCHER
				}
		}

	/**
	 * Resolves manifest class-name shorthand. The merged manifest normally carries FQNs
	 * already (the manifest merger expands them); this is a defensive fallback that follows
	 * the platform's rules against the manifest package attribute.
	 */
	private fun resolveClassName(
		name: String,
		manifestPackage: String,
	): String =
		when {
			name.startsWith(".") -> manifestPackage + name
			'.' !in name && manifestPackage.isNotEmpty() -> "$manifestPackage.$name"
			else -> name
		}

	private fun Element.childElements(tag: String): List<Element> {
		val result = mutableListOf<Element>()
		var node = firstChild
		while (node != null) {
			if (node is Element && node.tagName == tag) {
				result.add(node)
			}
			node = node.nextSibling
		}
		return result
	}

	private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
		DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = true
			setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
			setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			setFeature("http://xml.org/sax/features/external-general-entities", false)
			setFeature("http://xml.org/sax/features/external-parameter-entities", false)
			setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
		}
}
