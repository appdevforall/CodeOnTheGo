package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.preferences.internal.EditorPreferences
import com.itsaky.androidide.preferences.utils.indentationString
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.reflect.KProperty0

/**
 * @author Akash Yadav
 */
@RunWith(JUnit4::class)
class ClassBuilderTest {

	@Test
	fun givenUseAppCompatFlagTrue_whenCreatingActivity_thenExtendsAppCompatActivity() {
		mockkObject(EditorPreferences)
		every { EditorPreferences.useSoftTab } returns true
		every { EditorPreferences.tabSize } returns 4

		val activitySrc = ClassBuilder.createActivity(
			packageName = "com.example",
			className = "MyActivity",
			appCompatActivity = true
		)

		assertThat(activitySrc).apply {
			contains("import androidx.appcompat.app.AppCompatActivity;")
			contains("MyActivity extends AppCompatActivity")
		}
	}

	@Test
	fun givenUseAppCompatFlagFalse_whenCreatingActivity_thenExtendsActivity() {
		mockkObject(EditorPreferences)
		every { EditorPreferences.useSoftTab } returns true
		every { EditorPreferences.tabSize } returns 4

		val activitySrc = ClassBuilder.createActivity(
			packageName = "com.example",
			className = "MyActivity",
			appCompatActivity = false
		)

		assertThat(activitySrc).apply {
			contains("import android.app.Activity;")
			contains("MyActivity extends Activity")
		}
	}
}