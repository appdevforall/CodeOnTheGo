package org.appdevforall.codeonthego.computervision.domain.grammar

interface WidgetGrammar {
    val tag: String
    val attributes: Map<String, AttributeValidator>
}

object SpinnerGrammar : WidgetGrammar {
    override val tag = "Spinner"
    override val attributes = mapOf(
        "android:layout_width" to DimensionValidator,
        "android:layout_height" to DimensionValidator,
        "android:id" to PassThroughValidator,
        "android:text" to PassThroughValidator,
        "android:entries" to PassThroughValidator
    )
}

object ImageViewGrammar : WidgetGrammar {
    override val tag = "ImageView"
    private val gravityValues = listOf("top", "bottom", "left", "right", "center", "center_vertical", "center_horizontal", "start", "end")

    override val attributes = mapOf(
        "android:layout_width" to DimensionValidator,
        "android:layout_height" to DimensionValidator,
        "android:id" to PassThroughValidator,
        "android:src" to PassThroughValidator,
        "android:layout_gravity" to CategoricalValidator(gravityValues)
    )
}

object EditTextGrammar : WidgetGrammar {
    override val tag = "EditText"
    private val inputTypeValues = listOf("text", "textPassword", "number", "numberDecimal", "textEmailAddress", "textUri", "phone")

    override val attributes = mapOf(
        "android:layout_width" to DimensionValidator,
        "android:layout_height" to DimensionValidator,
        "android:id" to PassThroughValidator,
        "android:text" to PassThroughValidator,
        "android:inputType" to CategoricalValidator(inputTypeValues),
        "android:hint" to PassThroughValidator
    )
}

object SliderGrammar : WidgetGrammar {
    override val tag = "com.google.android.material.slider.Slider"
    override val attributes = mapOf(
        "android:layout_width" to DimensionValidator,
        "android:layout_height" to DimensionValidator,
        "android:id" to PassThroughValidator,
        "android:text" to PassThroughValidator,
        "android:layout_weight" to PassThroughValidator,
        "style" to SliderStyleValidator
    )
}
