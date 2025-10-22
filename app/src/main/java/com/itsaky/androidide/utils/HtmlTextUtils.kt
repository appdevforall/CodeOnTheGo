package com.itsaky.androidide.utils

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.text.HtmlCompat

val HTML_HYPERLINK_TEXT_COLOR = "#233490".toColorInt()

fun SpannableStringBuilder.appendHtmlWithLinks(
	html: String,
	onLinkClick: (url: String) -> Unit
) {
	val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
	val spannable = SpannableStringBuilder(spanned)

	val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
	for (urlSpan in urlSpans) {
		val start = spannable.getSpanStart(urlSpan)
		val end = spannable.getSpanEnd(urlSpan)
		spannable.removeSpan(urlSpan)

		val clickableSpan = object : ClickableSpan() {
			override fun onClick(widget: View) {
				onLinkClick(urlSpan.url)
			}

			override fun updateDrawState(ds: TextPaint) {
				super.updateDrawState(ds)
				ds.isUnderlineText = true
				ds.color = HTML_HYPERLINK_TEXT_COLOR
			}
		}

		spannable.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
	}

	append(spannable)
}

fun SpannableStringBuilder.appendOrderedList(items: List<String>) {
	appendOrderedList(*items.toTypedArray())
}

fun SpannableStringBuilder.appendOrderedList(vararg items: String) {
	items.forEachIndexed { index, itemHtml ->
		val number = "${index + 1}. "
		val itemSpanned = HtmlCompat.fromHtml(itemHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
		val spannableItem = SpannableStringBuilder()
		spannableItem.append(number)
		spannableItem.append(itemSpanned)
		append(spannableItem)
		append("\n")
	}
}
