package com.itsaky.androidide.quickbuild

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.ui.StatusLineTapBinding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A status-line tap belongs to the text it was bound for (ADFA-7138). After a refused Quick Build the
 * editor view model writes the indexing line straight to the bottom sheet, so the tap must go when
 * that other text lands, and stay when the refusal's own text is written again.
 */
@RunWith(RobolectricTestRunner::class)
class StatusLineTapBindingTest {
	private val line = TextView(ApplicationProvider.getApplicationContext<Context>())
	private val tap = StatusLineTapBinding(line)
	private var taps = 0

	private fun write(text: String) {
		line.text = text
		tap.onTextWritten(text)
	}

	private fun bindRefusal() {
		line.text = "Quick Build: off - tap for details"
		tap.bind("show why") { taps++ }
	}

	@Test
	fun `other status text replacing the line makes it plain text again`() {
		bindRefusal()
		assertThat(line.isClickable).isTrue()

		write("Indexing 3 of 9")

		assertThat(line.isClickable).isFalse()
		assertThat(line.isFocusable).isFalse()
		assertThat(line.performClick()).isFalse()
		assertThat(taps).isEqualTo(0)
	}

	@Test
	fun `the same text written again keeps the tap`() {
		bindRefusal()

		write("Quick Build: off - tap for details")

		assertThat(line.isClickable).isTrue()
		assertThat(line.performClick()).isTrue()
		assertThat(taps).isEqualTo(1)
	}
}
