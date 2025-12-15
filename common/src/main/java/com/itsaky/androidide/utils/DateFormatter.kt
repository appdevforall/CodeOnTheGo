package com.itsaky.androidide.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDate(dateString: String): String {
	return runCatching {
		val outputFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)

		if (dateString.all { it.isDigit() }) {
			val millis = dateString.toLong()
			val date = Date(millis)
			return@runCatching outputFormat.format(date)
		}

		val inputFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)
		val date = inputFormat.parse(dateString) ?: return dateString.take(5)
		outputFormat.format(date)
	}.getOrElse {
		dateString.take(5)
	}
}
