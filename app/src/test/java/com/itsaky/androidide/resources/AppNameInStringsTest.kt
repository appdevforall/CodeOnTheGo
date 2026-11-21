package com.itsaky.androidide.resources

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ADFA-7237: the app name is `app_name`, and every other string takes it as a format argument,
 * so a build that replaces `app_name` renames the app everywhere.
 *
 * Reads the `values* /strings.xml` files straight from the source tree, so it needs no Android.
 */
class AppNameInStringsTest {
	private val resDir: File by lazy {
		generateSequence(File("").absoluteFile) { it.parentFile }
			.map { File(it, "resources/src/main/res") }
			.first { it.isDirectory }
	}

	private val stringFiles: List<File> by lazy {
		resDir
			.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
			.map { File(it, "strings.xml") }
			.filter { it.isFile }
			.sortedBy { it.parentFile.name }
	}

	private val defaultFile: File get() = File(resDir, "values/strings.xml")

	private class Entry(
		val key: String,
		val text: String,
	)

	/** `<string>` values, plus every `<item>` of a `<string-array>`, keyed `array[index]`. */
	private fun entries(file: File): List<Entry> {
		val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
		val out = mutableListOf<Entry>()
		val strings = doc.getElementsByTagName("string")
		for (i in 0 until strings.length) {
			val el = strings.item(i) as Element
			out += Entry(el.getAttribute("name"), el.textContent)
		}
		val arrays = doc.getElementsByTagName("string-array")
		for (i in 0 until arrays.length) {
			val array = arrays.item(i) as Element
			val items = array.getElementsByTagName("item")
			for (j in 0 until items.length) {
				out += Entry("${array.getAttribute("name")}[$j]", items.item(j).textContent)
			}
		}
		return out
	}

	@Test
	fun `no string spells out the app name unless it is on a list`() {
		val offenders = mutableListOf<String>()
		for (file in stringFiles) {
			for (entry in entries(file)) {
				if (entry.key == "app_name" || entry.key in KEEP_LITERAL) continue
				if (entry.text.contains(APP_NAME, ignoreCase = true)) {
					offenders += "${file.parentFile.name}/${entry.key}"
				}
			}
		}
		assertThat(offenders).isEmpty()
	}

	@Test
	fun `every parameterised string takes the app name at its position in the default`() {
		val defaults = entries(defaultFile).associate { it.key to it.text }
		for ((key, position) in NAME_POSITION) {
			val text = defaults[key] ?: error("values/strings.xml has no $key")
			assertThat(positions(text)).contains(position)
		}
	}

	@Test
	fun `a locale never uses a placeholder the default lacks`() {
		val defaults = entries(defaultFile).associate { it.key to positions(it.text) }
		val bad = mutableListOf<String>()
		for (file in stringFiles) {
			for (entry in entries(file)) {
				if (entry.key !in NAME_POSITION) continue
				val inDefault = defaults[entry.key] ?: continue
				val here = positions(entry.text)
				// A translation may leave the name out; it may not invent or move an argument.
				if (!inDefault.containsAll(here)) bad += "${file.parentFile.name}/${entry.key}"
				// Mixed numbered and unnumbered arguments make the numbering ambiguous.
				if (BARE_ARGUMENT.containsMatchIn(entry.text)) bad += "${file.parentFile.name}/${entry.key} (bare argument)"
			}
		}
		assertThat(bad).isEmpty()
	}

	@Test
	fun `a string array is formatted item by item so its items hold no stray percent sign`() {
		val bad = mutableListOf<String>()
		for (file in stringFiles) {
			for (entry in entries(file)) {
				val array = entry.key.substringBefore('[')
				if (array !in FORMATTED_ARRAYS) continue
				val withoutArgument = entry.text.replace("%1\$s", "")
				if (withoutArgument.contains('%')) bad += "${file.parentFile.name}/${entry.key}"
			}
		}
		assertThat(bad).isEmpty()
	}

	private fun positions(text: String): Set<Int> =
		NUMBERED_ARGUMENT
			.findAll(text)
			.map { it.groupValues[1].toInt() }
			.toSet()

	private companion object {
		const val APP_NAME = "code on the go"

		val NUMBERED_ARGUMENT = Regex("%(\\d+)\\$[a-zA-Z]")
		val BARE_ARGUMENT = Regex("%[sd]")

		/**
		 * Protected: legal or attribution text, or text that names the upstream product rather
		 * than this app.
		 */
		val PROTECTED =
			setOf(
				// Credit line written into Git commits; must keep naming Code on the Go.
				"made_with_code_on_the_go",
				// Describes that credit line.
				"idepref_git_add_commit_watermark_summary",
				// Sends the user to the upstream product's download page.
				"download_codeonthego_message",
				// Invites people to contribute to the upstream open-source project.
				"summary_contribute",
			)

		/** Referenced from XML (the manifest), which cannot pass a format argument. */
		val LEFT_LITERAL_XML =
			setOf(
				// AndroidManifest.xml android:description of the logger permission.
				"permdesc_bind_logger_service",
			)

		val KEEP_LITERAL = PROTECTED + LEFT_LITERAL_XML

		val FORMATTED_ARRAYS = setOf("overlay_restricted_settings_steps", "debugger_setup_pairing_steps")

		/** Strings changed by ADFA-7237 and the position the app name takes in each. */
		val NAME_POSITION =
			mapOf(
				"about_footer_alternate" to 2,
				"about_footer" to 3,
				"msg_need_help" to 1,
				"msg_unsupported_device" to 3,
				"msg_first_build" to 1,
				"msg_deeplink_setup_incomplete" to 1,
				"idepref_about_title" to 1,
				"msg_open_projects" to 1,
				"msg_confirm_project_open" to 1,
				"msg_ide_crashed" to 1,
				"msg_help_hint" to 1,
				"msg_long_press_help" to 1,
				"idepref_about_summary" to 1,
				"title_gradle_service_notification_channel" to 1,
				"idepref_general_themeSelector_summary" to 1,
				"privacy_disclosure_message" to 1,
				"idepref_devOptions_summary" to 1,
				"idepref_devOptions_dumpLogs_summary" to 1,
				"idepref_devOptions_enableLogsender_summary" to 1,
				"msg_experimental_flavor" to 3,
				"idepref_general_localeSelector_summary" to 1,
				"permission_desc_storage" to 1,
				"permission_desc_install_packages" to 1,
				"permission_desc_overlay_window" to 1,
				"permission_overlay_restricted_settings_hint" to 1,
				"permission_overlay_restricted_dialog_message" to 1,
				"permission_desc_notifications" to 1,
				"permission_desc_accessibility" to 1,
				"onboarding_subtitle_permissions" to 1,
				"ide_setup_complete" to 1,
				"debugger_error_errno_eperm" to 1,
				"debugger_error_errno_econnrefused" to 1,
				"debugger_error_debugger_startup_failure" to 1,
				"debugger_error_suggestion_network_restriction" to 1,
				"feedback_email_subject" to 1,
				"quick_build_switch_unknown_app_message" to 1,
				"quick_build_reload_crashed" to 1,
				"notification_adb_pairing_succeed_text" to 1,
				"adb_pairing_tutorial_content_notification" to 2,
				"err_foreground_service_denial" to 1,
				"debugger_setup_description_footer" to 1,
				"back_to_cogo" to 1,
				"url_consent_message" to 2,
			)
	}
}
