package com.itsaky.androidide.utils

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths

data class FileMatch(
	val relativePath: String,
	val highlights: List<Int>,
)

sealed interface FileQuery {
	fun search(relativePaths: List<String>): List<FileMatch>

	class Fuzzy internal constructor(
		private val pattern: String,
	) : FileQuery {
		private val matchesPath = '/' in pattern

		override fun search(relativePaths: List<String>): List<FileMatch> =
			relativePaths
				.mapNotNull(::score)
				.sortedWith(
					compareByDescending<ScoredMatch> { it.score }
						.thenBy { nameLength(it.match.relativePath) }
						.thenBy(String.CASE_INSENSITIVE_ORDER) { it.match.relativePath },
				).map { it.match }

		private fun score(relativePath: String): ScoredMatch? {
			val from = subjectStart(relativePath, matchesPath)
			val end = firstMatchEnd(relativePath, from) ?: return null
			val start = latestMatchStart(relativePath, from, end)

			var score = 0
			var inGap = false
			var consecutive = 0
			var firstBonus = 0
			var patternIndex = 0
			val highlights = ArrayList<Int>(pattern.length)
			var prevClass = if (start > from) charClass(relativePath[start - 1]) else CharClass.NON_WORD
			for (i in start..end) {
				val charClass = charClass(relativePath[i])
				if (relativePath[i].equals(pattern[patternIndex], ignoreCase = true)) {
					highlights += i
					var bonus = bonus(prevClass, charClass)
					if (consecutive == 0) {
						firstBonus = bonus
					} else {
						if (bonus >= BONUS_BOUNDARY && bonus > firstBonus) {
							firstBonus = bonus
						}
						bonus = maxOf(bonus, firstBonus, BONUS_CONSECUTIVE)
					}
					score += SCORE_MATCH + if (patternIndex == 0) bonus * FIRST_CHAR_BONUS_MULTIPLIER else bonus
					inGap = false
					consecutive++
					patternIndex++
				} else {
					score += if (inGap) SCORE_GAP_EXTENSION else SCORE_GAP_START
					inGap = true
					consecutive = 0
					firstBonus = 0
				}
				prevClass = charClass
			}
			return ScoredMatch(FileMatch(relativePath, highlights), score)
		}

		private fun firstMatchEnd(
			text: String,
			from: Int,
		): Int? {
			var patternIndex = 0
			for (i in from until text.length) {
				if (text[i].equals(pattern[patternIndex], ignoreCase = true) && ++patternIndex == pattern.length) {
					return i
				}
			}
			return null
		}

		private fun latestMatchStart(
			text: String,
			from: Int,
			end: Int,
		): Int {
			var patternIndex = pattern.length - 1
			for (i in end downTo from) {
				if (text[i].equals(pattern[patternIndex], ignoreCase = true) && --patternIndex < 0) {
					return i
				}
			}
			error("No match of '$pattern' ends at $end in '$text'")
		}

		private class ScoredMatch(
			val match: FileMatch,
			val score: Int,
		)

		private enum class CharClass { LOWER, UPPER, DIGIT, DELIMITER, NON_WORD }

		private fun charClass(char: Char) =
			when {
				char.isUpperCase() -> CharClass.UPPER
				char.isLetter() -> CharClass.LOWER
				char.isDigit() -> CharClass.DIGIT
				char == '/' -> CharClass.DELIMITER
				else -> CharClass.NON_WORD
			}

		private fun bonus(
			prev: CharClass,
			current: CharClass,
		): Int {
			val isWord = current == CharClass.LOWER || current == CharClass.UPPER || current == CharClass.DIGIT
			return when {
				isWord && (prev == CharClass.DELIMITER || prev == CharClass.NON_WORD) -> BONUS_BOUNDARY
				prev == CharClass.LOWER && current == CharClass.UPPER -> BONUS_CAMEL
				prev != CharClass.DIGIT && current == CharClass.DIGIT -> BONUS_CAMEL
				!isWord -> BONUS_BOUNDARY
				else -> 0
			}
		}

		private companion object {
			const val SCORE_MATCH = 16
			const val SCORE_GAP_START = -3
			const val SCORE_GAP_EXTENSION = -1
			const val BONUS_BOUNDARY = SCORE_MATCH / 2
			const val BONUS_CAMEL = BONUS_BOUNDARY + SCORE_GAP_EXTENSION
			const val BONUS_CONSECUTIVE = -(SCORE_GAP_START + SCORE_GAP_EXTENSION)
			const val FIRST_CHAR_BONUS_MULTIPLIER = 2
		}
	}

	class Glob internal constructor(
		pattern: String,
	) : FileQuery {
		private val matchesPath = '/' in pattern
		private val matcher = FileSystems.getDefault().getPathMatcher("glob:${pattern.lowercase()}")

		override fun search(relativePaths: List<String>): List<FileMatch> =
			relativePaths
				.filter { matcher.matches(Paths.get(it.substring(subjectStart(it, matchesPath)).lowercase())) }
				.sortedWith(String.CASE_INSENSITIVE_ORDER)
				.map { FileMatch(it, emptyList()) }
	}

	companion object {
		private const val GLOB_CHARS = "*?[{"

		fun parse(text: String): FileQuery {
			require(text.isNotBlank()) { "A file query needs at least one non-blank character" }
			return if (text.any { it in GLOB_CHARS }) Glob(text) else Fuzzy(text)
		}
	}
}

private fun subjectStart(
	relativePath: String,
	matchesPath: Boolean,
) = if (matchesPath) 0 else relativePath.lastIndexOf('/') + 1

private fun nameLength(relativePath: String) = relativePath.length - subjectStart(relativePath, matchesPath = false)

fun walkProjectFiles(
	root: File,
	excludedDirNames: Set<String>,
): List<String> =
	root
		.walkTopDown()
		.onEnter { it == root || (it.name !in excludedDirNames && !Files.isSymbolicLink(it.toPath())) }
		.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
		.map { it.relativeTo(root).invariantSeparatorsPath }
		.toList()
