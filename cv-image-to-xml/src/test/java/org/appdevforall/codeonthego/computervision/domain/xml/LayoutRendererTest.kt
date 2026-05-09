package org.appdevforall.codeonthego.computervision.domain.xml

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRendererTest {

    @Test
    fun `checkbox group uses canonical ids when OCR annotation id is noisy`() {
        val first = checkboxBox(y = 0, text = "Option A")
        val second = checkboxBox(y = 20, text = "Option B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: cbgraup2 | textColor: gray"
            )
        )

        renderer.render(LayoutItem.CheckboxGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/cb_group_2_a""""))
        assertTrue(xml.contains("""android:id="@+id/cb_group_2_b""""))
        assertTrue(!xml.contains("cbgraup2"))
    }

    @Test
    fun `radio group ignores group style ids on child radios`() {
        val first = radioBox(y = 0, text = "choice A")
        val second = radioBox(y = 20, text = "choice B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: rb_group_1 | textSize: 16sp"
            )
        )

        renderer.render(LayoutItem.RadioGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/radio_button_unchecked_0""""))
        assertTrue(xml.contains("""android:id="@+id/radio_button_checked_0""""))
        assertTrue(!xml.contains("""android:id="@+id/rb_group_1""""))
    }

    @Test
    fun `radio group ignores group style ids even when parser leaks trailing tokens`() {
        val first = radioBox(y = 0, text = "choice A")
        val second = radioBox(y = 20, text = "choice B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: rb_group_1_text_site_16sp | textColor: black"
            )
        )

        renderer.render(LayoutItem.RadioGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/radio_button_unchecked_0""""))
        assertTrue(!xml.contains("""android:id="@+id/rb_group_1_text_site_16sp""""))
    }

    private fun checkboxBox(y: Int, text: String, checked: Boolean = false): ScaledBox {
        val label = if (checked) "checkbox_checked" else "checkbox_unchecked"
        return ScaledBox(
            label = label,
            text = text,
            x = 0,
            y = y,
            w = 20,
            h = 20,
            centerX = 10,
            centerY = y + 10,
            rect = Rect(0, y, 20, y + 20)
        )
    }

    private fun radioBox(y: Int, text: String, checked: Boolean = false): ScaledBox {
        val label = if (checked) "radio_button_checked" else "radio_button_unchecked"
        return ScaledBox(
            label = label,
            text = text,
            x = 0,
            y = y,
            w = 20,
            h = 20,
            centerX = 10,
            centerY = y + 10,
            rect = Rect(0, y, 20, y + 20)
        )
    }
}
