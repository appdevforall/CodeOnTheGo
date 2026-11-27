/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.activities

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.SimpleIconTitleDescriptionAdapter
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.databinding.ActivityAboutBinding
import com.itsaky.androidide.models.IconTitleDescriptionItem
import com.itsaky.androidide.models.SimpleIconTitleDescriptionItem
import com.itsaky.androidide.utils.BasicBuildInfo
import com.itsaky.androidide.utils.BuildInfoUtils
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.copyToClipboard
import com.itsaky.androidide.utils.dpToPx
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.resolveAttr

/** One row of About's socials list: [value] is both the text shown and the link it opens. */
internal data class SocialRow(
	val id: Int,
	@param:DrawableRes val icon: Int,
	@param:StringRes val title: Int,
	val value: String,
)

/**
 * The rows to show, values trimmed. A row whose value is blank is left out, so a copy made by the
 * Custom IDE Builder hides it by emptying its string (ADFA-7313).
 */
internal fun visibleRows(rows: List<SocialRow>): List<SocialRow> =
	rows.mapNotNull { row ->
		row.value
			.trim()
			.takeIf { it.isNotEmpty() }
			?.let { row.copy(value = it) }
	}

class AboutActivity : EdgeToEdgeIDEActivity() {
	@Suppress("ktlint:standard:backing-property-naming")
	private var _binding: ActivityAboutBinding? = null
	private val binding: ActivityAboutBinding
		get() =
			checkNotNull(_binding) {
				"Activity has been destroyed"
			}

	override fun bindLayout(): View {
		_binding = ActivityAboutBinding.inflate(layoutInflater)
		return _binding!!.root
	}

	private var feedbackButtonManager: FeedbackButtonManager? = null

	companion object {
		private var id = 0
		private val ACTION_WEBSITE = id++
		private val ACTION_EMAIL = id++
		private val ACTION_TG_CHANNEL = id++
		private val ACTION_GH_FORUM = id++
		private val ACTION_YOUTUBE = id++
		private val ACTION_BILIBILI = id++
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		binding.apply {
			setSupportActionBar(toolbar)
			supportActionBar!!.setDisplayHomeAsUpEnabled(true)
			supportActionBar!!.setTitle(R.string.about)
			toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
			feedbackButtonManager = FeedbackButtonManager(this@AboutActivity, fabFeedback.root)
			feedbackButtonManager?.setupDraggableFab()

			aboutHeader.apply {
				ideVersion.text = createVersionText()
				ideVersion.isClickable = true
				ideVersion.isFocusable = true
				ideVersion.setBackgroundResource(R.drawable.bg_ripple)
				ideVersion.setOnClickListener {
					this@AboutActivity.copyToClipboard(BuildInfoUtils.getBuildInfoHeader())
					flashSuccess(R.string.copied)
				}

				// An empty github_sponsors_url (a Custom IDE Builder copy, ADFA-7313) hides the button.
				val sponsorUrl = getString(R.string.github_sponsors_url).trim()
				supportButton.isVisible = sponsorUrl.isNotEmpty()
				supportButton.setOnClickListener {
					UrlManager.openUrl(sponsorUrl, null, this@AboutActivity)
				}
			}

			socials.apply {
				sectionTitle.setText(R.string.title_socials)
				sectionItems.adapter = AboutSocialItemsAdapter(createSocialItems(), ::handleActionClick)
			}
		}
	}

	override fun onApplySystemBarInsets(insets: Insets) {
		binding.toolbar.apply {
			setPaddingRelative(
				paddingStart + insets.left,
				paddingTop,
				paddingEnd + insets.right,
				paddingBottom,
			)
		}
	}

	// The row's value is both the text it shows and where it leads, so a copy that changes the string
	// changes both (ADFA-7313).
	private fun handleActionClick(action: SimpleIconTitleDescriptionItem) {
		val value = action.description.toString()
		when (action.id) {
			ACTION_EMAIL -> UrlManager.openUrl("mailto:$value", null, this)
			ACTION_TG_CHANNEL -> UrlManager.openUrl(value, "org.telegram.messenger", this)
			else -> UrlManager.openUrl(value, context = this)
		}
	}

	private fun createSocialItems(): List<IconTitleDescriptionItem> =
		visibleRows(
			listOf(
				SocialRow(
					ACTION_WEBSITE,
					R.drawable.ic_website,
					R.string.about_option_website,
					getString(R.string.about_website_url),
				),
				SocialRow(
					ACTION_EMAIL,
					R.drawable.ic_email,
					R.string.about_option_email,
					getString(R.string.adfa_email),
				),
				SocialRow(
					ACTION_GH_FORUM,
					R.drawable.ic_github,
					R.string.discussions_on_telegram,
					getString(R.string.github_discussions_url),
				),
				SocialRow(
					ACTION_TG_CHANNEL,
					R.drawable.ic_telegram,
					R.string.official_tg_channel,
					getString(R.string.telegram_channel_url),
				),
				SocialRow(
					ACTION_YOUTUBE,
					R.drawable.ic_youtube,
					R.string.about_option_youtube,
					getString(R.string.youtube_channel_url),
				),
				SocialRow(
					ACTION_BILIBILI,
					R.drawable.ic_bilibili,
					R.string.about_option_bilibili,
					getString(R.string.bilibili_video_url),
				),
			),
		).map { createSimpleIconTextItem(this, it.id, it.icon, it.title, it.value) }

	private fun createSimpleIconTextItem(
		context: Context,
		id: Int,
		@DrawableRes icon: Int,
		@StringRes title: Int,
		description: CharSequence,
	): SimpleIconTitleDescriptionItem =
		SimpleIconTitleDescriptionItem(
			id,
			ContextCompat.getDrawable(context, icon),
			ContextCompat.getString(context, title),
			description,
		)

/**
* Create the version name string that should be displayed to the user.
*
* Format of the version name string is :
*
* `v[version-name]-[variant] ([build-type]/[[UN]OFFICIAL])`
*/
	@Suppress("KDocUnresolvedReference")
	private fun createVersionText(): CharSequence {
		val builder = SpannableStringBuilder()
		builder.append("v")
		builder.append(BasicBuildInfo.formatVersion())
		builder.append("-")
		builder.append(IDEBuildConfigProvider.getInstance().cpuAbiName)
		builder.append(" ")

		val colorPositive = ContextCompat.getColor(this, R.color.color_success)
		val colorNegative = ContextCompat.getColor(this, R.color.color_error)

		appendBuildType(builder, colorPositive, colorNegative)

		return builder
	}

	private fun appendBuildType(
		builder: SpannableStringBuilder,
		@ColorInt
		colorPositive: Int,
		@ColorInt
		colorNegative: Int,
	) {
		@Suppress("KotlinConstantConditions")
		var color =
			if (BuildConfig.BUILD_TYPE != "release") {
				colorNegative
			} else {
				colorPositive
			}

		builder.append("(")
		appendForegroundSpan(builder, BuildConfig.BUILD_TYPE, color)

		val isOfficialBuild = BuildInfoUtils.isOfficialBuild(this)

		color =
			if (isOfficialBuild) {
				colorPositive
			} else {
				colorNegative
			}

		builder.append("/")
		appendForegroundSpan(
			builder,
			BuildInfoUtils.getBuildType(this).lowercase(),
			color,
		)

		builder.append(")")
	}

	private fun appendForegroundSpan(
		builder: SpannableStringBuilder,
		text: CharSequence,
		color: Int,
	) {
		builder.append(
			text,
			ForegroundColorSpan(color),
			SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
	}

	override fun onResume() {
		super.onResume()
		feedbackButtonManager?.loadFabPosition()
	}

	override fun onDestroy() {
		super.onDestroy()
		_binding = null
	}

	class AboutSocialItemsAdapter(
		items: List<IconTitleDescriptionItem>,
		private val onClickListener: (SimpleIconTitleDescriptionItem) -> Unit,
	) : SimpleIconTitleDescriptionAdapter(items) {
		override fun onBindViewHolder(
			holder: ViewHolder,
			position: Int,
		) {
			super.onBindViewHolder(holder, position)
			val binding = holder.binding
			val item = getItem(position) as SimpleIconTitleDescriptionItem
			val dp8 = binding.icon.context.dpToPx(8f)
			binding.icon.updatePaddingRelative(dp8, dp8, dp8, dp8)
			binding.title.setTextAppearance(R.style.TextAppearance_Material3_TitleSmall)

			binding.description.maxLines = 3
			binding.description.setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
			binding.description.setTextColor(binding.description.context.resolveAttr(R.attr.colorPrimary))

			binding.root.isClickable = true
			binding.root.isFocusable = true
			binding.root.setBackgroundResource(R.drawable.bg_ripple)
			binding.root.setOnClickListener {
				onClickListener(item)
			}
		}
	}
}
