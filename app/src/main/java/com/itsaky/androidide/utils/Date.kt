package com.itsaky.androidide.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDate(dateString: String): String {
	return try {
		if (dateString.all { it.isDigit() }) {
			val millis = dateString.toLong()
			val date = Date(millis)
			val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
			return outputFormat.format(date)
		}

		val inputFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)
		val date = inputFormat.parse(dateString)
		val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
		outputFormat.format(date)
	} catch (_: Exception) {
		dateString.take(5)
	}
}
