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
 * Kind of a manifest component the Quick Build proxy app proxies. [jsonName] is the
 * `type` value in the setup.json/manifest-info `components` array.
 */
enum class ComponentType(
	val jsonName: String,
) {
	ACTIVITY("activity"),
	SERVICE("service"),
	RECEIVER("receiver"),
	PROVIDER("provider"),
	APPLICATION("application"),
}

/**
 * One component of the user's merged manifest, paired with the proxy generated for it.
 * The custom Application is carried as a component with a null [proxyClass]: nothing
 * addresses it by manifest name, so it keeps the user FQN (the runtime's
 * instantiateApplication already routes it through the payload loader).
 *
 * @property userClass fully-qualified user class.
 * @property proxyClass fully-qualified generated proxy class that replaces it in the
 *   manifest, or null for the Application entry.
 * @property isLauncher whether an activity declares the MAIN/LAUNCHER intent filter.
 * @property foregroundServiceType a service's android:foregroundServiceType, verbatim,
 *   for CoGo's restart-status messaging only.
 * @property authorities a provider's post-rewrite authorities, for diagnostics.
 */
data class ProxiedComponent(
	val type: ComponentType,
	val userClass: String,
	val proxyClass: String?,
	val isLauncher: Boolean = false,
	val foregroundServiceType: String? = null,
	val authorities: List<String> = emptyList(),
)

/**
 * A component left under its real manifest name because
 * [ComponentProxiabilityResolver] rejected it, with that resolver's [reason]. Reported so
 * the calling task can log what it skipped and why.
 */
data class UnproxiedComponent(
	val userClass: String,
	val reason: String,
)

/**
 * Result of rewriting a merged manifest for the Quick Build proxy app.
 */
class ManifestTransformResult(
	val document: Document,
	val components: List<ProxiedComponent>,
	val unproxied: List<UnproxiedComponent> = emptyList(),
) {
	/** The proxied activities, in manifest order (view kept for activity-only consumers). */
	val activities: List<ProxiedComponent>
		get() = components.filter { it.type == ComponentType.ACTIVITY }

	/** User class of the LAUNCHER activity, or null when the manifest declares none. */
	val entryActivity: String?
		get() = activities.firstOrNull { it.isLauncher }?.userClass
}

/**
 * Rewrites a merged Android manifest into the Quick Build proxy-app manifest: every
 * component's android:name is replaced with a
 * generated proxy FQN (stable component names while the user's classes stay swappable in
 * the payload dex), provider authorities that embed the real applicationId move to the
 * proxy-app id, and the `<application>` gains an android:appComponentFactory pointing at
 * the quick-build runtime. Everything else (permissions, icon, label, intent filters,
 * exported/permission attributes, meta-data) is preserved verbatim.
 *
 * The one exception to "every component is proxied" is whatever [proxiability] rejects:
 * that component keeps its real manifest name, emits no proxy class and no
 * [ProxiedComponent] entry, and is reported in [ManifestTransformResult.unproxied]. The
 * decision lives entirely in [ComponentProxiabilityResolver] - read that, not a second list
 * here.
 *
 * Attribute combinations the proxy app cannot host yet (android:process on any component,
 * isolated services, multiprocess providers) throw with the component named - the proxy app
 * build fails loud and the session never starts, instead of silently dropping behavior.
 *
 * Pure logic, no Gradle types - unit-testable without a build.
 *
 * @property proxyPackage package for generated proxies, e.g. `com.example.app.quickbuild.proxies`.
 * @property appComponentFactory FQN of the runtime's AppComponentFactory.
 * @property proxiability decides which components are skipped; defaults to the by-name rules
 *   alone, for callers with no dependency classpath to search.
 */
class QuickBuildManifestTransformer(
	private val proxyPackage: String,
	private val appComponentFactory: String,
	private val proxiability: ComponentProxiabilityResolver = ComponentProxiabilityResolver.byNameOnly(),
) {
	companion object {
		const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
		private const val ACTION_MAIN = "android.intent.action.MAIN"
		private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

		/** Manifest-order-per-type proxy simple name (Proxy0Activity, Proxy0Service, ...); must stay stable across pieces. */
		fun proxySimpleName(
			index: Int,
			type: ComponentType,
		): String {
			require(type != ComponentType.APPLICATION) { "the Application gets no proxy" }
			val suffix = type.jsonName.replaceFirstChar { it.uppercase() }
			return "Proxy$index$suffix"
		}
	}

	/**
	 * Parses and rewrites the manifest. Throws [IllegalArgumentException] on a manifest the
	 * quick path cannot handle (no `<application>`, a component without android:name, or an
	 * unsupported-for-v1 attribute) - the calling task turns that into a failed proxy app build
	 * with the message intact.
	 */
	fun transform(input: InputStream): ManifestTransformResult {
		val document = newDocumentBuilderFactory().newDocumentBuilder().parse(input)
		val manifestPackage = document.documentElement?.getAttribute("package").orEmpty()

		val application =
			document.getElementsByTagName("application").item(0) as? Element
				?: throw IllegalArgumentException("merged manifest has no <application> element")

		application.setAttributeNS(ANDROID_NS, "android:appComponentFactory", appComponentFactory)
		neutralizeBackup(application)

		val components = mutableListOf<ProxiedComponent>()
		val unproxied = mutableListOf<UnproxiedComponent>()
		components += transformActivities(application, manifestPackage, unproxied)
		components += transformServices(application, manifestPackage, unproxied)
		components += transformReceivers(application, manifestPackage, unproxied)
		components += transformProviders(application, manifestPackage, unproxied)
		applicationComponent(application, manifestPackage)?.let { components += it }

		inlineLibraryResourceRefs(document)

		return ManifestTransformResult(document, components, unproxied)
	}

	/**
	 * Records and reports a component [proxiability] rejects, so each caller can bail out of
	 * its loop with one line. The rejected component is left verbatim - and, critically, must
	 * not consume a per-type proxy index, or every LATER component of that type would shift.
	 */
	private fun skipProxy(
		userClass: String,
		unproxied: MutableList<UnproxiedComponent>,
	): Boolean {
		val resolution = proxiability.resolve(userClass)
		if (resolution !is ComponentProxiabilityResolver.Resolution.Skip) return false
		unproxied += UnproxiedComponent(userClass, resolution.reason)
		return true
	}

	private fun transformActivities(
		application: Element,
		manifestPackage: String,
		unproxied: MutableList<UnproxiedComponent>,
	): List<ProxiedComponent> {
		val activities = mutableListOf<ProxiedComponent>()
		var proxyIndex = 0
		application.childElements("activity").forEachIndexed { index, activity ->
			val userClass = requireComponentName(activity, "activity", index, manifestPackage)
			rejectUnsupported(activity, "activity", userClass)
			// An alias targeting a skipped activity (below) then finds no proxy mapping
			// and correctly leaves its targetActivity pointed at the real class.
			if (skipProxy(userClass, unproxied)) {
				return@forEachIndexed
			}
			val proxyClass = "$proxyPackage.${proxySimpleName(proxyIndex, ComponentType.ACTIVITY)}"
			proxyIndex++
			activity.setAttributeNS(ANDROID_NS, "android:name", proxyClass)
			activities.add(
				ProxiedComponent(
					type = ComponentType.ACTIVITY,
					userClass = userClass,
					proxyClass = proxyClass,
					isLauncher = isLauncher(activity),
				),
			)
		}

		// An <activity-alias> targeting a rewritten activity must follow it to the proxy,
		// or the alias would reference a component the manifest no longer declares.
		val byUserClass = activities.associateBy { it.userClass }
		application.childElements("activity-alias").forEach { alias ->
			val target = alias.getAttributeNS(ANDROID_NS, "targetActivity")
			if (target.isNotBlank()) {
				byUserClass[resolveClassName(target, manifestPackage)]?.proxyClass?.let { proxy ->
					alias.setAttributeNS(ANDROID_NS, "android:targetActivity", proxy)
				}
			}
		}
		return activities
	}

	private fun transformServices(
		application: Element,
		manifestPackage: String,
		unproxied: MutableList<UnproxiedComponent>,
	): List<ProxiedComponent> {
		var proxyIndex = 0
		return application.childElements("service").mapIndexedNotNull { index, service ->
			val userClass = requireComponentName(service, "service", index, manifestPackage)
			rejectUnsupported(service, "service", userClass)
			if (service.getAttributeNS(ANDROID_NS, "isolatedProcess") == "true") {
				throw IllegalArgumentException(
					"<service> '$userClass' sets android:isolatedProcess=\"true\", which Quick Build " +
						"does not support yet; use a Standard Run",
				)
			}
			if (skipProxy(userClass, unproxied)) {
				return@mapIndexedNotNull null
			}
			val proxyClass = "$proxyPackage.${proxySimpleName(proxyIndex, ComponentType.SERVICE)}"
			proxyIndex++
			service.setAttributeNS(ANDROID_NS, "android:name", proxyClass)
			ProxiedComponent(
				type = ComponentType.SERVICE,
				userClass = userClass,
				proxyClass = proxyClass,
				foregroundServiceType =
					service.getAttributeNS(ANDROID_NS, "foregroundServiceType").ifBlank { null },
			)
		}
	}

	private fun transformReceivers(
		application: Element,
		manifestPackage: String,
		unproxied: MutableList<UnproxiedComponent>,
	): List<ProxiedComponent> {
		var proxyIndex = 0
		return application.childElements("receiver").mapIndexedNotNull { index, receiver ->
			val userClass = requireComponentName(receiver, "receiver", index, manifestPackage)
			rejectUnsupported(receiver, "receiver", userClass)
			if (skipProxy(userClass, unproxied)) {
				return@mapIndexedNotNull null
			}
			val proxyClass = "$proxyPackage.${proxySimpleName(proxyIndex, ComponentType.RECEIVER)}"
			proxyIndex++
			receiver.setAttributeNS(ANDROID_NS, "android:name", proxyClass)
			ProxiedComponent(
				type = ComponentType.RECEIVER,
				userClass = userClass,
				proxyClass = proxyClass,
			)
		}
	}

	private fun transformProviders(
		application: Element,
		manifestPackage: String,
		unproxied: MutableList<UnproxiedComponent>,
	): List<ProxiedComponent> {
		var proxyIndex = 0
		return application.childElements("provider").mapIndexedNotNull { index, provider ->
			val userClass = requireComponentName(provider, "provider", index, manifestPackage)
			rejectUnsupported(provider, "provider", userClass)
			if (provider.getAttributeNS(ANDROID_NS, "multiprocess") == "true") {
				throw IllegalArgumentException(
					"<provider> '$userClass' sets android:multiprocess=\"true\", which Quick Build " +
						"does not support yet; use a Standard Run",
				)
			}
			if (skipProxy(userClass, unproxied)) {
				return@mapIndexedNotNull null
			}
			val proxyClass = "$proxyPackage.${proxySimpleName(proxyIndex, ComponentType.PROVIDER)}"
			proxyIndex++
			provider.setAttributeNS(ANDROID_NS, "android:name", proxyClass)
			ProxiedComponent(
				type = ComponentType.PROVIDER,
				userClass = userClass,
				proxyClass = proxyClass,
				authorities = readAuthorities(provider),
			)
		}
	}

	/**
	 * Neutralizes auto-backup on the proxy app. android:backupAgent points at a class that
	 * travels only in the payload dex (never the installed APK), so the OS backup pass
	 * would instantiate it through the APK classloader and crash the proxy app in the
	 * background - a crash the user cannot connect to Quick Build. Backing up a throwaway
	 * dev harness is pointless anyway, so force allowBackup="false" and drop the backup
	 * hooks (design contract, application attribute table). Consistent with the loud-reject
	 * of android:process: here stripping is the semantically-correct handling, not silent
	 * behavior loss.
	 */
	private fun neutralizeBackup(application: Element) {
		application.setAttributeNS(ANDROID_NS, "android:allowBackup", "false")
		listOf("backupAgent", "fullBackupContent", "fullBackupOnly", "dataExtractionRules").forEach {
			application.removeAttributeNS(ANDROID_NS, it)
		}
	}

	private fun applicationComponent(
		application: Element,
		manifestPackage: String,
	): ProxiedComponent? {
		val name = application.getAttributeNS(ANDROID_NS, "name")
		if (name.isBlank()) return null
		val userClass = resolveClassName(name, manifestPackage)
		// Keep the USER class but write it fully qualified, mirroring the FQN rewrite every
		// proxied component gets: instantiateApplication resolves this name against the payload
		// dex, so a shorthand left verbatim is fragile. Merged manifests normally carry FQNs
		// already; this makes it unconditional.
		application.setAttributeNS(ANDROID_NS, "android:name", userClass)
		return ProxiedComponent(
			type = ComponentType.APPLICATION,
			userClass = userClass,
			proxyClass = null,
		)
	}

	/**
	 * The provider's declared authorities, split on `;`. The proxy app installs under the
	 * project's real applicationId, so `${applicationId}`-derived authorities already resolve
	 * correctly and need no rewrite - they pass through verbatim (recorded here only so the
	 * proxy app report can carry them).
	 */
	private fun readAuthorities(provider: Element): List<String> {
		val raw = provider.getAttributeNS(ANDROID_NS, "authorities")
		if (raw.isBlank()) return emptyList()
		return raw.split(';').filter { it.isNotBlank() }
	}

	private fun requireComponentName(
		element: Element,
		tag: String,
		index: Int,
		manifestPackage: String,
	): String {
		val name = element.getAttributeNS(ANDROID_NS, "name")
		if (name.isBlank()) {
			throw IllegalArgumentException("<$tag> at index $index has no android:name")
		}
		return resolveClassName(name, manifestPackage)
	}

	/** v1 is single-process: any component asking for its own process routes to Standard Run. */
	private fun rejectUnsupported(
		element: Element,
		tag: String,
		userClass: String,
	) {
		val process = element.getAttributeNS(ANDROID_NS, "process")
		if (process.isNotBlank()) {
			throw IllegalArgumentException(
				"<$tag> '$userClass' sets android:process=\"$process\", which Quick Build does not " +
					"support yet; use a Standard Run",
			)
		}
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
