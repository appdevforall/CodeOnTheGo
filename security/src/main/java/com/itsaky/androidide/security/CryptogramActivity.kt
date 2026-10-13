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

package com.itsaky.androidide.security

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import com.itsaky.androidide.security.databinding.ActivityCryptogramBinding

/**
 * The "Code Breaker" cryptogram disguise for Instant Hide (ADFA-40). A fully playable offline
 * monoalphabetic-substitution puzzle that stands in for the IDE while hidden. The deliberate exit
 * gesture (a slow double three-finger tap) returns to the IDE.
 *
 * Ported from the security mockup; the self-destruct half is intentionally omitted.
 */
class CryptogramActivity : AppCompatActivity() {
	companion object {
		private const val USER_LETTER_TEXT_SIZE_SP = 18f
		private const val CIPHER_LETTER_TEXT_SIZE_SP = 14f
		private const val PUNCTUATION_TEXT_SIZE_SP = 18f
		private const val WORD_PADDING_START_PX = 4
		private const val WORD_PADDING_END_PX = 12
		private const val WORD_PADDING_VERTICAL_PX = 8
		private const val LETTER_PADDING_HORIZONTAL_PX = 3
	}

	private lateinit var binding: ActivityCryptogramBinding

	data class Puzzle(
		val text: String,
		val author: String,
	)

	private val puzzles =
		listOf(
			Puzzle("THE ONLY WAY TO DO GREAT WORK IS TO LOVE WHAT YOU DO.", "Steve Jobs"),
			Puzzle("SIMPLICITY IS THE SOUL OF EFFICIENCY.", "Austin Freeman"),
			Puzzle("CODE IS LIKE HUMOR. WHEN YOU HAVE TO EXPLAIN IT, IT IS BAD.", "Cory House"),
			Puzzle("FIRST SOLVE THE PROBLEM THEN WRITE THE CODE.", "John Johnson"),
			Puzzle("EXPERIENCE IS THE NAME EVERYONE GIVES TO THEIR MISTAKES.", "Oscar Wilde"),
			Puzzle("MAKE IT WORK MAKE IT RIGHT MAKE IT FAST.", "Kent Beck"),
			Puzzle(
				"ANY FOOL CAN WRITE CODE THAT A COMPUTER CAN UNDERSTAND. GOOD PROGRAMMERS WRITE CODE THAT HUMANS CAN UNDERSTAND.",
				"Martin Fowler",
			),
			Puzzle("BEFORE SOFTWARE CAN BE REUSABLE IT FIRST HAS TO BE USABLE.", "Ralph Johnson"),
			Puzzle("OPTIMISM IS AN OCCUPATIONAL HAZARD OF PROGRAMMING. FEEDBACK IS THE TREATMENT.", "Kent Beck"),
			Puzzle("THE MOST DISASTROUS THING THAT YOU CAN EVER LEARN IS YOUR FIRST PROGRAMMING LANGUAGE.", "Alan Kay"),
			Puzzle("TRUTH CAN ONLY BE FOUND IN ONE PLACE: THE CODE.", "Robert C. Martin"),
			Puzzle(
				"IT IS NOT AT ALL IMPORTANT TO GET IT RIGHT THE FIRST TIME. IT IS VITALLY IMPORTANT TO GET IT RIGHT THE LAST TIME.",
				"Andrew Hunt",
			),
		)

	private var currentPuzzleIndex = 0
	private val cipherMap = mutableMapOf<Char, Char>() // plain -> cipher
	private val reverseCipherMap = mutableMapOf<Char, Char>() // cipher -> plain
	private val userReplacements = mutableMapOf<Char, Char>() // cipher -> user-entered letter

	private lateinit var exitGestureDetector: GestureDetectorHelper.SlowDoubleThreeFingerTapDetector

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityCryptogramBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// Mask the task description so Recents shows the disguise title.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			setTaskDescription(
				ActivityManager.TaskDescription
					.Builder()
					.setLabel(getString(R.string.stealth_app_name))
					.build(),
			)
		} else {
			@Suppress("DEPRECATION")
			setTaskDescription(ActivityManager.TaskDescription(getString(R.string.stealth_app_name)))
		}

		exitGestureDetector =
			GestureDetectorHelper.SlowDoubleThreeFingerTapDetector(this) { exitDisguiseMode() }

		binding.btnCheckSolution.setOnClickListener { checkSolution() }
		binding.btnNextPuzzle.setOnClickListener {
			currentPuzzleIndex = (currentPuzzleIndex + 1) % puzzles.size
			loadPuzzle(currentPuzzleIndex)
		}
		binding.btnResetPuzzle.setOnClickListener {
			userReplacements.clear()
			renderPuzzleGrid()
			binding.tvSolutionFeedback.text = ""
			updateHintButtonState()
		}
		binding.btnHintLetter.setOnClickListener { revealHintLetter() }
		binding.btnApplySub.setOnClickListener { applyCurrentSubstitution() }

		binding.editReplacementLetter.setOnEditorActionListener { _, actionId, event ->
			if (actionId == EditorInfo.IME_ACTION_DONE ||
				actionId == EditorInfo.IME_ACTION_GO ||
				(event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
			) {
				applyCurrentSubstitution()
				dismissKeyboard()
				true
			} else {
				false
			}
		}

		loadPuzzle(currentPuzzleIndex)
	}

	private fun loadPuzzle(index: Int) {
		val puzzle = puzzles[index]
		binding.tvPuzzleAuthor.text = getString(R.string.puzzle_author_format, puzzle.author)
		binding.tvSolutionFeedback.text = ""
		userReplacements.clear()

		generateCipher()

		val cipherChars =
			puzzle.text
				.filter { it in 'A'..'Z' }
				.map { cipherMap[it] ?: it }
				.distinct()
				.sorted()
				.map { it.toString() }

		binding.spinnerCipherLetters.adapter =
			ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cipherChars)

		renderPuzzleGrid()
		updateHintButtonState()
	}

	private fun generateCipher() {
		cipherMap.clear()
		reverseCipherMap.clear()
		val alphabet = ('A'..'Z').toList()
		var shuffled = alphabet.shuffled()

		// Derangement: no letter may encrypt to itself.
		while (alphabet.indices.any { alphabet[it] == shuffled[it] }) {
			shuffled = alphabet.shuffled()
		}

		for (i in alphabet.indices) {
			cipherMap[alphabet[i]] = shuffled[i]
			reverseCipherMap[shuffled[i]] = alphabet[i]
		}
	}

	private fun renderPuzzleGrid() {
		val puzzle = puzzles[currentPuzzleIndex]
		val container = binding.containerPuzzleGrid
		container.removeAllViews()

		for (word in puzzle.text.split(" ")) {
			val wordLayout =
				LinearLayout(this).apply {
					orientation = LinearLayout.HORIZONTAL
					setPadding(
						WORD_PADDING_START_PX,
						WORD_PADDING_VERTICAL_PX,
						WORD_PADDING_END_PX,
						WORD_PADDING_VERTICAL_PX,
					)
				}

			for (char in word) {
				val letterCol =
					LinearLayout(this).apply {
						orientation = LinearLayout.VERTICAL
						gravity = Gravity.CENTER_HORIZONTAL
						setPadding(LETTER_PADDING_HORIZONTAL_PX, 0, LETTER_PADDING_HORIZONTAL_PX, 0)
					}

				if (char in 'A'..'Z') {
					val cipherChar = cipherMap[char] ?: char
					val userChar = userReplacements[cipherChar] ?: '_'

					val tvUser =
						TextView(this).apply {
							text = userChar.toString()
							textSize = USER_LETTER_TEXT_SIZE_SP
							setTypeface(null, Typeface.BOLD)
							setTextColor(
								ContextCompat.getColor(
									this@CryptogramActivity,
									if (userChar != '_') R.color.success_green else R.color.placeholder_gray,
								),
							)
							gravity = Gravity.CENTER
						}

					val tvCipher =
						TextView(this).apply {
							text = cipherChar.toString()
							textSize = CIPHER_LETTER_TEXT_SIZE_SP
							setTextColor(ContextCompat.getColor(this@CryptogramActivity, R.color.cipher_letter_blue))
							gravity = Gravity.CENTER
						}

					letterCol.addView(tvUser)
					letterCol.addView(tvCipher)

					letterCol.isClickable = true
					letterCol.setOnClickListener {
						val adapter = binding.spinnerCipherLetters.adapter ?: return@setOnClickListener
						val cipherStr = cipherChar.toString()
						for (pos in 0 until adapter.count) {
							if (adapter.getItem(pos)?.toString() == cipherStr) {
								binding.spinnerCipherLetters.setSelection(pos)
								focusAndOpenKeyboard()
								break
							}
						}
					}
				} else {
					val tvPunct =
						TextView(this).apply {
							text = char.toString()
							textSize = PUNCTUATION_TEXT_SIZE_SP
							setTextColor(ContextCompat.getColor(this@CryptogramActivity, R.color.pastel_text_dark))
							gravity = Gravity.CENTER
						}
					letterCol.addView(tvPunct)
				}

				wordLayout.addView(letterCol)
			}

			container.addView(wordLayout)
		}
	}

	private fun checkSolution() {
		val solved = isPuzzleSolved()
		binding.tvSolutionFeedback.setTextColor(
			ContextCompat.getColor(this, if (solved) R.color.success_green else R.color.error_red),
		)
		binding.tvSolutionFeedback.text =
			getString(if (solved) R.string.puzzle_solved else R.string.puzzle_incorrect)
		updateHintButtonState()
	}

	private fun isPuzzleSolved(): Boolean {
		val puzzle = puzzles[currentPuzzleIndex]
		for (char in puzzle.text) {
			if (char in 'A'..'Z') {
				val cipherChar = cipherMap[char] ?: char
				if (userReplacements[cipherChar] != char) {
					return false
				}
			}
		}
		return true
	}

	private fun updateHintButtonState() {
		binding.btnHintLetter.isEnabled = !isPuzzleSolved()
	}

	private fun revealHintLetter() {
		val puzzle = puzzles[currentPuzzleIndex]
		val unsolvedPlainLetters =
			puzzle.text
				.toList()
				.filter { it in 'A'..'Z' }
				.distinct()
				.filter {
					val cipher = cipherMap[it] ?: it
					userReplacements[cipher] != it
				}

		if (unsolvedPlainLetters.isNotEmpty()) {
			val targetPlain = unsolvedPlainLetters.random()
			val targetCipher = cipherMap[targetPlain] ?: targetPlain
			userReplacements[targetCipher] = targetPlain
			renderPuzzleGrid()
			updateHintButtonState()
			binding.tvSolutionFeedback.setTextColor(ContextCompat.getColor(this, R.color.cipher_letter_blue))
			binding.tvSolutionFeedback.text = getString(R.string.hint_used, targetCipher, targetPlain)
		}
	}

	private fun applyCurrentSubstitution() {
		val selectedCipher = binding.spinnerCipherLetters.selectedItem as? String
		val inputLetter =
			binding.editReplacementLetter.text
				.toString()
				.trim()
				.uppercase()
		if (!selectedCipher.isNullOrEmpty() && inputLetter.isNotEmpty()) {
			userReplacements[selectedCipher[0]] = inputLetter[0]
			binding.editReplacementLetter.setText("")
			renderPuzzleGrid()
			updateHintButtonState()
		}
	}

	private fun focusAndOpenKeyboard() {
		binding.editReplacementLetter.requestFocus()
		val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.showSoftInput(binding.editReplacementLetter, InputMethodManager.SHOW_IMPLICIT)
	}

	private fun dismissKeyboard() {
		val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.hideSoftInputFromWindow(binding.editReplacementLetter.windowToken, 0)
		binding.editReplacementLetter.clearFocus()
	}

	/** Leaves the disguise and returns to the IDE via its launcher entry point. */
	private fun exitDisguiseMode() {
		SecurityPreferences.isHiddenStateActive = false
		LauncherAliasManager.setStealthMode(this, false)

		val launch =
			packageManager.getLaunchIntentForPackage(packageName)?.apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			}
		if (launch != null) {
			startActivity(launch)
		}
		finish()
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		exitGestureDetector.onTouchEvent(ev)
		return super.dispatchTouchEvent(ev)
	}
}
