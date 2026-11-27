package com.itsaky.androidide.activities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Custom IDE Builder (ADFA-7241) finds these strings by name in a copy's resources.arsc and
 * rewrites them (ADFA-7313), so a rename breaks it without a compile error anywhere. Looked up by
 * name on purpose: an `R.string` reference would follow a rename.
 */
@RunWith(RobolectricTestRunner::class)
class ContactStringsContractTest {
	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private fun id(name: String): Int {
		val id = context.resources.getIdentifier(name, "string", context.packageName)
		assertWithMessage("string resource $name").that(id).isNotEqualTo(0)
		return id
	}

	private fun string(name: String): String = context.getString(id(name))

	@Test
	fun `the strings the builder writes keep their names and App Dev For All's values`() {
		assertThat(string("about_website_url")).isEqualTo("https://www.appdevforall.org/")
		assertThat(string("adfa_email")).isEqualTo("info@appdevforall.org")
		assertThat(string("github_discussions_url")).isEqualTo("https://github.com/appdevforall/CodeOnTheGo/discussions")
		assertThat(string("telegram_channel_url")).isEqualTo("https://t.me/CodeOnTheGoOfficial")
		assertThat(string("youtube_channel_url")).isEqualTo("https://youtube.com/@appdevforall")
		assertThat(string("github_sponsors_url")).isEqualTo("https://github.com/sponsors/appdevforall")
		assertThat(string("feedback_email")).isEqualTo("feedback@appdevforall.org")
		assertThat(string("bilibili_video_url")).isNotEmpty()
	}

	private fun assertMessagesNameTheGivenAddress() {
		for (name in listOf("email_feedback_warning_prompt", "msg_contact_app_dev_description")) {
			val message = context.getString(id(name), "someone@example.org")
			assertWithMessage(name).that(message).contains("someone@example.org")
			assertWithMessage(name).that(message).doesNotContain("appdevforall")
		}
	}

	@Test
	fun `the feedback messages name the address they are given`() = assertMessagesNameTheGivenAddress()

	@Test
	@Config(qualifiers = "in-rID")
	fun `the feedback messages name the address they are given, in Indonesian`() = assertMessagesNameTheGivenAddress()

	@Test
	@Config(qualifiers = "pt-rBR")
	fun `the feedback messages name the address they are given, in Portuguese`() = assertMessagesNameTheGivenAddress()

	@Test
	@Config(qualifiers = "zh-rCN")
	fun `the feedback messages name the address they are given, in Chinese`() = assertMessagesNameTheGivenAddress()
}
