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
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class JdwpPlugin : Plugin<Project> {
	override fun apply(target: Project) =
		target
			.run {
				if (!target.plugins.hasPlugin(APP_PLUGIN)) {
					return
				}

				logger.info("Applying {} to project '${target.path}'", JdwpPlugin::class.simpleName)

				extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
					onDebuggableVariants { variant ->
						val jdwpManifestTransformer =
							tasks.register(
								variant.generateTaskName("transform", "JdwpManifest"),
								JdwpManifestTransformerTask::class.java,
							)

						variant.artifacts
							.use(jdwpManifestTransformer)
							.wiredWithFiles(
								taskInput = JdwpManifestTransformerTask::mergedManifest,
								taskOutput = JdwpManifestTransformerTask::updatedManifest,
							).toTransform(SingleArtifact.MERGED_MANIFEST)
					}
				}
			}.let { }
}

abstract class JdwpManifestTransformerTask : DefaultTask() {
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

		val manifest = document.documentElement
		val androidNs = "http://schemas.android.com/apk/res/android"

		// Check if INTERNET permission already exists
		val existingPermissions = document.getElementsByTagName("uses-permission")
		var hasInternetPermission = false

		for (i in 0 until existingPermissions.length) {
			val node = existingPermissions.item(i)
			val nameAttr = node.attributes?.getNamedItemNS(androidNs, "name")
			if (nameAttr?.nodeValue == "android.permission.INTERNET") {
				hasInternetPermission = true
				break
			}
		}

		if (!hasInternetPermission) {
			logger.info("Adding INTERNET permission")
			val permissionNode = document.createElement("uses-permission")
			permissionNode.setAttributeNS(
				androidNs,
				"android:name",
				"android.permission.INTERNET",
			)
			manifest.appendChild(permissionNode)
		}

		val transformer = TransformerFactory.newInstance().newTransformer()
		transformer.setOutputProperty(OutputKeys.INDENT, "yes")
		transformer.transform(
			DOMSource(document),
			StreamResult(outputFile),
		)
	}
}
