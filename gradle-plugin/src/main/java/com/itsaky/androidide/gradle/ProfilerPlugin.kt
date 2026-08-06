package com.itsaky.androidide.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Ensures the final APK is profileable by the shell (so it can be profiled with simpleperf) by
 * transforming the merged manifest to contain `<profileable android:shell="true"/>` under the
 * `<application>` element.
 *
 * Applied by [AndroidIDEGradlePlugin] when [com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_PROFILEABLE_ENABLED] is set.
 *
 * @author Akash Yadav
 */
class ProfilerPlugin : Plugin<Project> {
	override fun apply(target: Project): Unit =
		target
			.run {
				if (!target.plugins.hasPlugin(APP_PLUGIN)) {
					return
				}

				logger.info("Applying {} to project '${target.path}'", ProfilerPlugin::class.simpleName)

				extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
					// Make the release APK installable for profiling: if the release build type has
					// no signing config, fall back to the debug signing config that AGP creates for
					// application modules. A project with its own release keystore is left untouched.
					// Done in finalizeDsl (AGP's sanctioned last-chance DSL hook) so it applies
					// regardless of plugin-application ordering. This checks signing at the
					// build-type level only.
					finalizeDsl { ext ->
						ext.buildTypes.findByName("release")?.let { release ->
							if (release.signingConfig == null) {
								ext.signingConfigs.findByName("debug")?.let { debug ->
									target.logger.lifecycle(
										"Configuring release build type to use debug signing config",
									)
									release.signingConfig = debug
								}
							}
						}
					}

					onVariants { variant ->
						val profileableManifestTransformer =
							tasks.register(
								variant.generateTaskName("transform", "ProfileableManifest"),
								ProfileableManifestTransformerTask::class.java,
							)

						variant.artifacts
							.use(profileableManifestTransformer)
							.wiredWithFiles(
								taskInput = ProfileableManifestTransformerTask::mergedManifest,
								taskOutput = ProfileableManifestTransformerTask::updatedManifest,
							).toTransform(SingleArtifact.MERGED_MANIFEST)
					}
				}
			}.let { }
}

abstract class ProfileableManifestTransformerTask : DefaultTask() {
	@get:InputFile
	abstract val mergedManifest: RegularFileProperty

	@get:OutputFile
	abstract val updatedManifest: RegularFileProperty

	@TaskAction
	fun transform() {
		val inputFile = mergedManifest.get().asFile
		val outputFile = updatedManifest.get().asFile

		val factory =
			DocumentBuilderFactory.newInstance().apply {
				isNamespaceAware = true
				setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
				setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
				setFeature("http://xml.org/sax/features/external-general-entities", false)
				setFeature("http://xml.org/sax/features/external-parameter-entities", false)
				setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
			}

		val documentBuilder = factory.newDocumentBuilder()
		val document = documentBuilder.parse(inputFile)
		val androidNs = "http://schemas.android.com/apk/res/android"

		val application = document.getElementsByTagName("application").item(0) as? Element
		if (application == null) {
			logger.warn("No <application> element found in manifest; leaving it unchanged")
		} else {
			// <profileable> is a direct child of <application>. If it already exists, force
			// android:shell="true"; otherwise add the element.
			val existing = document.getElementsByTagName("profileable").item(0) as? Element
			if (existing != null) {
				logger.info("Setting android:shell=\"true\" on existing <profileable> tag")
				existing.setAttributeNS(androidNs, "android:shell", "true")
			} else {
				logger.info("Adding <profileable android:shell=\"true\"/> to <application>")
				val profileable = document.createElement("profileable")
				profileable.setAttributeNS(androidNs, "android:shell", "true")
				application.appendChild(profileable)
			}
		}

		val transformer = TransformerFactory.newInstance().newTransformer()
		transformer.setOutputProperty(OutputKeys.INDENT, "yes")
		transformer.transform(
			DOMSource(document),
			StreamResult(outputFile),
		)
	}
}
