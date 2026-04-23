package org.appdevforall.codeonthego.computervision.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyAttributeParserTest {

    @Test
    fun `parse returns empty map for null annotation`() {
        val result = FuzzyAttributeParser.parse(null, "Button")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse returns empty map for blank annotation`() {
        val result = FuzzyAttributeParser.parse("   ", "TextView")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `delimited happy path parses width height and id`() {
        val annotation = "width: 100dp | height: 80dp | id: my_btn"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
        assertEquals("my_btn", result["android:id"])
    }

    @Test
    fun `delimited parses background as backgroundTint for Button`() {
        val annotation = "background: red | width: 100dp"
        val resultButton = FuzzyAttributeParser.parse(annotation, "Button")
        assertEquals("#FF0000", resultButton["app:backgroundTint"])

        val resultText = FuzzyAttributeParser.parse(annotation, "TextView")
        assertEquals("#FF0000", resultText["android:background"])
    }

    @Test
    fun `delimited parses text and hint attributes`() {
        val annotation = "text: Hello World | hint: Enter name"
        val result = FuzzyAttributeParser.parse(annotation, "EditText")

        assertEquals("Hello World", result["android:text"])
        assertEquals("Enter name", result["android:hint"])
    }

    @Test
    fun `delimited parses src attribute`() {
        val annotation = "src: my_image.png | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("@drawable/my_image", result["android:src"])
    }

    @Test
    fun `OCR garbled drawable name wm to m`() {
        val annotation = "src: iwmages | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("@drawable/images", result["android:src"])
    }

    @Test
    fun `OCR garbled drawable name rn to m`() {
        val annotation = "src: irnagebg | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("@drawable/imagebg", result["android:src"])
    }

    @Test
    fun `delimited parses textSize with sp suffix`() {
        val annotation = "text_size: 14 | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("14sp", result["android:textSize"])
    }

    @Test
    fun `delimited parses textColor with color map lookup`() {
        val annotation = "textcolor: blue | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("#0000FF", result["android:textColor"])
    }

    @Test
    fun `OCR garbled keys are fuzzy matched via delimited`() {
        val annotation = "wldth: 100dp | hejght: 80dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
    }

    @Test
    fun `OCR garbled dimension value with spaces is cleaned`() {
        val annotation = "width: 100 dp | height: 80 dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
    }

    @Test
    fun `OCR garbled color name is fuzzy matched`() {
        val annotation = "background: bIue | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("#0000FF", result["android:background"])
    }

    @Test
    fun `OCR garbled match_parent is fuzzy matched`() {
        val annotation = "width: match parcnt | height: 80dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("match_parent", result["android:layout_width"])
    }

    @Test
    fun `OCR garbled wrap_content is fuzzy matched`() {
        val annotation = "height: wrap_contnt | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("wrap_content", result["android:layout_height"])
    }

    @Test
    fun `empty chunks between pipes are skipped`() {
        val annotation = "width: 100dp | | height: 80dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals(2, result.size)
        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
    }

    @Test
    fun `chunk without colon infers key-value boundary`() {
        val annotation = "width 100dp | id my_btn"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("my_btn", result["android:id"])
    }

    @Test
    fun `garbage key below threshold is dropped`() {
        val annotation = "xyzabc: 100dp | width: 200dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("200dp", result["android:layout_width"])
        assertNull(result["xyzabc"])
    }

    @Test
    fun `multiple colons preserves value after first colon`() {
        val annotation = "text: Hello: World | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("Hello: World", result["android:text"])
    }

    @Test
    fun `id value has special characters cleaned`() {
        val annotation = "id: radius slider | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("radius_slider", result["android:id"])
    }

    @Test
    fun `OCR garbled checkbox group id is normalized without changing custom ids`() {
        val checkboxResult = FuzzyAttributeParser.parse("id: cbgraup2 | width: 100dp", "CheckBox")
        val customResult = FuzzyAttributeParser.parse("id: radius_slider | width: 100dp", "ImageView")

        assertEquals("cb_group_2", checkboxResult["android:id"])
        assertEquals("radius_slider", customResult["android:id"])
    }

    @Test
    fun `hex color values pass through unchanged`() {
        val annotation = "background: #FF5733 | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("#FF5733", result["android:background"])
    }

    @Test
    fun `android resource color values pass through unchanged`() {
        val annotation = "background: @android:color/transparent | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("@android:color/transparent", result["android:background"])
    }

    @Test
    fun `entries attribute maps to tools namespace`() {
        val annotation = "entries: @array/items | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "Spinner")

        assertEquals("@array/items", result["tools:entries"])
    }

    @Test
    fun `inputType attribute parses correctly`() {
        val annotation = "input_type: textPassword | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "EditText")

        assertEquals("textPassword", result["android:inputType"])
    }

    @Test
    fun `gravity attribute parses correctly`() {
        val annotation = "gravity: center | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("center", result["android:gravity"])
    }

    @Test
    fun `layout_gravity attribute parses correctly`() {
        val annotation = "layout_gravity: center | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("center", result["android:layout_gravity"])
    }

    @Test
    fun `style attribute parses correctly`() {
        val annotation = "style: @style/Widget.MaterialComponents.Button | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("@style/Widget.MaterialComponents.Button", result["style"])
    }

    @Test
    fun `negative dimension values are handled`() {
        val annotation = "width: -16dp | height: 80dp"
        val result = FuzzyAttributeParser.parse(annotation, "View")

        assertEquals("-16dp", result["android:layout_width"])
    }

    @Test
    fun `purple color is fuzzy matched`() {
        val annotation = "textcolor: pruple | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("#800080", result["android:textColor"])
    }

    @Test
    fun `layout_weight cleans non-numeric noise`() {
        val annotation = "layout_weight: 1 Layout | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("1", result["android:layout_weight"])
    }

    @Test
    fun `multiple attributes with mixed clean and garbled`() {
        val annotation = "width: match_parent | bg: blue | text_size: 20"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("match_parent", result["android:layout_width"])
        assertEquals("#0000FF", result["app:backgroundTint"])
        assertEquals("20sp", result["android:textSize"])
    }

    @Test
    fun `non-delimited colon scanning parses multiple attributes`() {
        val annotation = "width: 100dp height: 80dp id: my_btn"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
        assertEquals("my_btn", result["android:id"])
    }

    @Test
    fun `non-delimited with OCR garbled layout_height`() {
        val annotation = "width: 100 dp Layout _height: 80 dp id: radius_slider"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
        assertEquals("radius_slider", result["android:id"])
    }

    @Test
    fun `non-delimited with OCR garbled id as ld`() {
        val annotation = "height: 80 dp ld: done_button"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("80dp", result["android:layout_height"])
        assertEquals("done_button", result["android:id"])
    }

    @Test
    fun `non-delimited real OCR scenario from user report`() {
        val annotation = "width: 100 dp Layout _height: 80 dp ld: radius_slider"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertNotNull(result["android:layout_width"])
        assertNotNull(result["android:layout_height"])
    }

    @Test
    fun `non-delimited background color for button`() {
        val annotation = "width: 100dp background: red"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("#FF0000", result["app:backgroundTint"])
    }

    @Test
    fun `margin attributes parse correctly`() {
        val annotation = "margin_top: 8dp | margin_bottom: 16dp | padding: 12dp"
        val result = FuzzyAttributeParser.parse(annotation, "View")

        assertEquals("8dp", result["android:layout_marginTop"])
        assertEquals("16dp", result["android:layout_marginBottom"])
        assertEquals("12dp", result["android:padding"])
    }

    @Test
    fun `padding attributes parse correctly`() {
        val annotation = "padding_start: 16dp | padding_end: 16dp"
        val result = FuzzyAttributeParser.parse(annotation, "LinearLayout")

        assertEquals("16dp", result["android:paddingStart"])
        assertEquals("16dp", result["android:paddingEnd"])
    }

    @Test
    fun `visibility attribute parses correctly`() {
        val annotation = "visibility: gone | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "View")

        assertEquals("gone", result["android:visibility"])
    }

    @Test
    fun `orientation attribute parses correctly`() {
        val annotation = "orientation: horizontal | width: match_parent"
        val result = FuzzyAttributeParser.parse(annotation, "LinearLayout")

        assertEquals("horizontal", result["android:orientation"])
    }

    @Test
    fun `text style attributes parse correctly`() {
        val annotation = "text_style: bold | text_alignment: center | max_lines: 2"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("bold", result["android:textStyle"])
        assertEquals("center", result["android:textAlignment"])
        assertEquals("2", result["android:maxLines"])
    }

    @Test
    fun `elevation attribute parses as dp`() {
        val annotation = "elevation: 4 | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "View")

        assertEquals("4dp", result["android:elevation"])
    }

    @Test
    fun `card attributes parse correctly`() {
        val annotation = "corner_radius: 8dp | card_elevation: 4dp | card_background_color: white"
        val result = FuzzyAttributeParser.parse(annotation, "CardView")

        assertEquals("8dp", result["app:cardCornerRadius"])
        assertEquals("4dp", result["app:cardElevation"])
        assertEquals("#FFFFFF", result["app:cardBackgroundColor"])
    }

    @Test
    fun `slider attributes parse correctly`() {
        val annotation = "value_from: 0 | value_to: 100 | step_size: 5"
        val result = FuzzyAttributeParser.parse(annotation, "Slider")

        assertEquals("0", result["app:valueFrom"])
        assertEquals("100", result["app:valueTo"])
        assertEquals("5", result["app:stepSize"])
    }

    @Test
    fun `min and max attributes parse correctly`() {
        val annotation = "min: 0 | max: 100 | progress: 50"
        val result = FuzzyAttributeParser.parse(annotation, "ProgressBar")

        assertEquals("0", result["android:min"])
        assertEquals("100", result["android:max"])
        assertEquals("50", result["android:progress"])
    }

    @Test
    fun `scale_type attribute parses correctly`() {
        val annotation = "scale_type: fitCenter | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("fitCenter", result["android:scaleType"])
    }

    @Test
    fun `stroke attributes parse correctly`() {
        val annotation = "stroke_color: black | stroke_width: 2dp"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("#000000", result["app:strokeColor"])
        assertEquals("2dp", result["app:strokeWidth"])
    }

    @Test
    fun `checked attribute parses correctly`() {
        val annotation = "checked: true | width: wrap_content"
        val result = FuzzyAttributeParser.parse(annotation, "CheckBox")

        assertEquals("true", result["android:checked"])
    }

    @Test
    fun `max_length and single_line parse correctly`() {
        val annotation = "max_length: 50 | single_line: true"
        val result = FuzzyAttributeParser.parse(annotation, "EditText")

        assertEquals("50", result["android:maxLength"])
        assertEquals("true", result["android:singleLine"])
    }

    @Test
    fun `font_family attribute parses correctly`() {
        val annotation = "font_family: sans-serif-medium | width: 100dp"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("sans-serif-medium", result["android:fontFamily"])
    }

    @Test
    fun `non-delimited full garbled scenario`() {
        val annotation = "wldth: 100 dp Layout _height: 80 dp ld: radius_slider background: blue"
        val result = FuzzyAttributeParser.parse(annotation, "ImageView")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
        assertEquals("radius_slider", result["android:id"])
        assertEquals("#0000FF", result["android:background"])
    }

    @Test
    fun `colonless trailing attribute after last colon-scanned key`() {
        val annotation = "width: 100dp background red"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("#FF0000", result["app:backgroundTint"])
    }

    @Test
    fun `colonless trailing color for non-button`() {
        val annotation = "text: Next backgrounli red"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("Next", result["android:text"])
        assertEquals("#FF0000", result["android:background"])
    }

    @Test
    fun `multiple colonless trailing attributes`() {
        val annotation = "width: 100dp background red gravity center"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("#FF0000", result["android:background"])
        assertEquals("center", result["android:gravity"])
    }

    @Test
    fun `standalone trailing color without key is inferred as background`() {
        val annotation = "text: Next red"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("Next", result["android:text"])
        assertEquals("#FF0000", result["app:backgroundTint"])
    }

    @Test
    fun `standalone trailing color for non-button`() {
        val annotation = "text: Submit blue"
        val result = FuzzyAttributeParser.parse(annotation, "TextView")

        assertEquals("Submit", result["android:text"])
        assertEquals("#0000FF", result["android:background"])
    }

    @Test
    fun `standalone trailing fuzzy color is matched`() {
        val annotation = "width: 200dp height: 200dp text: Next pruple"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("200dp", result["android:layout_width"])
        assertEquals("200dp", result["android:layout_height"])
        assertEquals("Next", result["android:text"])
        assertEquals("#800080", result["app:backgroundTint"])
    }

    @Test
    fun `semicolons treated as colons`() {
        val annotation = "width; 100dp height; 80dp id; my_btn"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("100dp", result["android:layout_width"])
        assertEquals("80dp", result["android:layout_height"])
        assertEquals("my_btn", result["android:id"])
    }

    @Test
    fun `colonless key-value in middle of annotation`() {
        val annotation = "id: btn_end text Start background: red"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("btn_end", result["android:id"])
        assertEquals("Start", result["android:text"])
        assertEquals("#FF0000", result["app:backgroundTint"])
    }

    @Test
    fun `real OCR garbled annotation with semicolons and noise`() {
        val annotation = "Ld; btn_start text; Start backgraund: gray"
        val result = FuzzyAttributeParser.parse(annotation, "Button")

        assertEquals("btn_start", result["android:id"])
        assertEquals("Start", result["android:text"])
        assertEquals("#808080", result["app:backgroundTint"])
    }
}
