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
 * Kind of manifest component the proxy app proxies.
 *
 * @property jsonName the `type` value in the setup.json / manifest-info `components` array
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
 *
 * The custom Application appears here with a null [proxyClass]: nothing addresses it by manifest
 * name, so it keeps the user FQN and the runtime's instantiateApplication routes it through the
 * payload loader.
 *
 * @property type which manifest element this came from; the Application is the only type that
 *   gets no proxy.
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
 * A component left under its real manifest name because [ComponentProxiabilityResolver] rejected
 * it, carrying that resolver's reason so the calling task can log what it skipped and why.
 *
 * @property userClass fully-qualified user class, still the component's manifest android:name.
 * @property reason the resolver's phrase for why it was rejected; log text only.
 */
data class UnproxiedComponent(
	val userClass: String,
	val reason: String,
)

/**
 * The rewritten manifest plus what the rewrite did to each component.
 *
 * @property document the transformed manifest, mutated in place from the parsed input.
 * @property components every proxied component, in manifest order per type, plus the proxy-less
 *   Application entry when the manifest declares one.
 * @property unproxied components left under their real name, for the caller to log.
 */
class ManifestTransformResult(
	val document: Document,
	val components: List<ProxiedComponent>,
	val unproxied: List<UnproxiedComponent> = emptyList(),
) {
	/** The proxied activities, in manifest order. */
	val activities: List<ProxiedComponent>
		get() = components.filter { it.type == ComponentType.ACTIVITY }

	/** User class of the LAUNCHER activity, or null when the manifest declares none. */
	val entryActivity: String?
		get() = activities.firstOrNull { it.isLauncher }?.userClass
}

/**
 * Rewrites a merged Android manifest into the proxy-app manifest: each component's android:name
 * becomes a generated proxy FQN, and the `<application>` gains an android:appComponentFactory
 * pointing at the quick-build runtime. Everything else is preserved verbatim.
 *
 * Components [proxiability] rejects keep their real manifest name, get no proxy class and no
 * [ProxiedComponent] entry, and are reported in [ManifestTransformResult.unproxied].
 *
 * Attributes the proxy app cannot host yet (android:process anywhere, isolated services,
 * multiprocess providers) throw with the component named, so the build fails loud rather than
 * silently dropping behavior.
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
		/** The android XML namespace every attribute here is read and written through. */
		const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
		private const val ACTION_MAIN = "android.intent.action.MAIN"
		private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

		/**
		 * Names the [index]-th proxy of [type] (Proxy0Activity, Proxy0Service, ...). The manifest,
		 * the generated sources and the report all derive names here, so the scheme must not drift.
		 *
		 * @param index 0-based position among the proxied components of [type] only; a skipped
		 *   component must not consume an index or every later one of that type shifts.
		 * @param type the component kind, whose json name becomes the capitalized suffix.
		 * @return the simple class name, with no package.
		 * @throws IllegalArgumentException if [type] is [ComponentType.APPLICATION].
		 */
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
	 * Parses and rewrites a merged manifest.
	 *
	 * @param input the merged manifest's bytes; read to the end, and not closed here.
	 * @return the rewritten document plus the per-component record of what was proxied.
	 * @throws IllegalArgumentException on a manifest the quick path cannot handle - no
	 *   `<application>`, a component without android:name, or an unsupported attribute. The
	 *   calling task turns that into a failed build with the message intact.
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
	 * Records a component [proxiability] rejects and reports whether the caller should skip it.
	 *
	 * A skipped component is left verbatim and must not consume a per-type proxy index, or every
	 * later component of that type would shift.
	 *
	 * @param userClass fully-qualified class named by the component's android:name.
	 * @param unproxied accumulator appended to when the component is rejected.
	 * @return true if the caller must leave this component alone.
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

	/**
	 * Renames every proxiable `<activity>` to its proxy, then repoints matching aliases.
	 *
	 * @param application the `<application>` element, mutated in place.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @param unproxied accumulator for components [skipProxy] rejects.
	 * @return the proxied activities, in manifest order; skipped ones are absent.
	 */
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

	/**
	 * Renames every proxiable `<service>` to its proxy.
	 *
	 * @param application the `<application>` element, mutated in place.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @param unproxied accumulator for components [skipProxy] rejects.
	 * @return the proxied services, in manifest order, each carrying its foregroundServiceType.
	 * @throws IllegalArgumentException if a service declares android:isolatedProcess.
	 */
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

	/**
	 * Renames every proxiable `<receiver>` to its proxy.
	 *
	 * @param application the `<application>` element, mutated in place.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @param unproxied accumulator for components [skipProxy] rejects.
	 * @return the proxied receivers, in manifest order.
	 */
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

	/**
	 * Renames every proxiable `<provider>` to its proxy.
	 *
	 * @param application the `<application>` element, mutated in place.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @param unproxied accumulator for components [skipProxy] rejects.
	 * @return the proxied providers, in manifest order, each carrying its authorities.
	 * @throws IllegalArgumentException if a provider declares android:multiprocess.
	 */
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
	 * Turns auto-backup off and strips the backup hooks.
	 *
	 * android:backupAgent points at a class that travels only in the payload dex, so the OS
	 * backup pass would instantiate it through the APK classloader and crash the proxy app in
	 * the background, where the user cannot connect the crash to Quick Build. Backing up a
	 * throwaway dev harness has no value, so stripping loses nothing.
	 *
	 * @param application the `<application>` element, mutated in place.
	 */
	private fun neutralizeBackup(application: Element) {
		application.setAttributeNS(ANDROID_NS, "android:allowBackup", "false")
		listOf("backupAgent", "fullBackupContent", "fullBackupOnly", "dataExtractionRules").forEach {
			application.removeAttributeNS(ANDROID_NS, it)
		}
	}

	/**
	 * Records the custom Application, if the manifest declares one; it gets no proxy.
	 *
	 * @param application the `<application>` element, whose android:name is rewritten in place to
	 *   the fully-qualified user class.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @return the Application entry, or null when the manifest names no custom Application.
	 */
	private fun applicationComponent(
		application: Element,
		manifestPackage: String,
	): ProxiedComponent? {
		val name = application.getAttributeNS(ANDROID_NS, "name")
		if (name.isBlank()) return null
		val userClass = resolveClassName(name, manifestPackage)
		// Keep the user class but write it fully qualified: instantiateApplication resolves this
		// name against the payload dex, so shorthand left verbatim is fragile. Merged manifests
		// normally carry FQNs already; this makes it unconditional.
		application.setAttributeNS(ANDROID_NS, "android:name", userClass)
		return ProxiedComponent(
			type = ComponentType.APPLICATION,
			userClass = userClass,
			proxyClass = null,
		)
	}

	/**
	 * Reads a provider's declared authorities, split on `;`, for the report only.
	 *
	 * The proxy app installs under the project's real applicationId, so authorities pass through
	 * the manifest verbatim and need no rewrite.
	 *
	 * @param provider the `<provider>` element; not modified.
	 * @return the authorities, blanks dropped, or empty when the attribute is absent.
	 */
	private fun readAuthorities(provider: Element): List<String> {
		val raw = provider.getAttributeNS(ANDROID_NS, "authorities")
		if (raw.isBlank()) return emptyList()
		return raw.split(';').filter { it.isNotBlank() }
	}

	/**
	 * Reads a component's android:name, insisting it is present.
	 *
	 * @param element an `<activity>`, `<service>`, `<receiver>`, or `<provider>` element; read
	 *   for its android:name only, and not modified.
	 * @param tag its manifest tag, for the error message only.
	 * @param index its position among same-tag siblings, for the error message only.
	 * @param manifestPackage the manifest's package, for expanding android:name shorthand.
	 * @return the fully-qualified class name.
	 * @throws IllegalArgumentException if android:name is absent or blank.
	 */
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

	/**
	 * Fails the build on a component asking for its own process; Quick Build is single-process.
	 *
	 * @param element the component element to vet; only android:process is inspected, and
	 *   nothing is modified.
	 * @param tag its manifest tag, for the error message only.
	 * @param userClass its resolved class name, for the error message only.
	 * @throws IllegalArgumentException if the component declares a non-blank android:process.
	 */
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
	 * Replaces the one known library-provided resource reference with its literal value.
	 *
	 * The on-device relink links only the app's own res/, so a manifest reference to a library
	 * resource aborts every resource hot reload with aapt2 "resource not found". CoGo's
	 * LogSenderPlugin injects exactly one (`@bool/logsender_enabled`). Relinking against the base
	 * APK's resource table would fix this generally; until then a new one hits the same wall.
	 *
	 * @param document the whole manifest, scanned attribute by attribute and edited in place.
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

	/**
	 * Serializes a transformed manifest to [file].
	 *
	 * @param document the transformed manifest.
	 * @param file destination; overwritten, and its parent directories created if missing.
	 */
	fun writeTo(
		document: Document,
		file: File,
	) {
		file.parentFile?.mkdirs()
		val transformer = TransformerFactory.newInstance().newTransformer()
		transformer.setOutputProperty(OutputKeys.INDENT, "yes")
		transformer.transform(DOMSource(document), StreamResult(file))
	}

	/**
	 * Reports whether an activity is the launcher entry point.
	 *
	 * @param activity the `<activity>` element; not modified.
	 * @return true if one intent filter carries both the MAIN action and the LAUNCHER category -
	 *   split across two filters does not count, matching the framework's own rule.
	 */
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
	 * Expands manifest class-name shorthand (`.Foo`, `Foo`) against the manifest package.
	 *
	 * A fallback: the manifest merger normally expands these already.
	 *
	 * @param name the raw android:name value.
	 * @param manifestPackage the manifest's package; an empty one leaves a bare name unchanged.
	 * @return the fully-qualified name, returned as-is when it already carries a package.
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

	/**
	 * Lists this element's direct children with the given tag.
	 *
	 * @param tag the tag name to match exactly.
	 * @return the matching children in document order; direct children only, so a nested
	 *   `<activity>` inside another element is never picked up.
	 */
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

	/**
	 * A parser factory hardened against XXE: no DOCTYPE, no external entities or DTDs.
	 *
	 * @return a namespace-aware factory; a manifest that declares a DOCTYPE is rejected outright.
	 */
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
