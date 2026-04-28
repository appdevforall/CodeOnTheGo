package org.appdevforall.codeonthego.computervision.domain.grammar

import org.appdevforall.codeonthego.computervision.domain.FuzzyAttributeParser.AttributeKey

interface WidgetGrammar {
    val tag: String
    val attributes: Map<String, AttributeValidator>
}

object SpinnerGrammar : WidgetGrammar {
    override val tag = "Spinner"
    override val attributes = mapOf(
        AttributeKey.WIDTH.xmlName to DimensionValidator,
        AttributeKey.HEIGHT.xmlName to DimensionValidator,
        AttributeKey.ID.xmlName to PassThroughValidator,
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.ENTRIES.xmlName to EntriesValidator
    )
}

object ImageViewGrammar : WidgetGrammar {
    override val tag = "ImageView"
    private val gravityValues = listOf("top", "bottom", "left", "right", "center", "center_vertical", "center_horizontal", "start", "end")

    override val attributes = mapOf(
        AttributeKey.WIDTH.xmlName to DimensionValidator,
        AttributeKey.HEIGHT.xmlName to DimensionValidator,
        AttributeKey.ID.xmlName to PassThroughValidator,
        AttributeKey.SRC.xmlName to PassThroughValidator,
        AttributeKey.LAYOUT_GRAVITY.xmlName to CategoricalValidator(gravityValues)
    )
}

object EditTextGrammar : WidgetGrammar {
    override val tag = "EditText"
    private val inputTypeValues = listOf("text", "textPassword", "number", "numberDecimal", "textEmailAddress", "textUri", "phone")

    override val attributes = mapOf(
        AttributeKey.WIDTH.xmlName to DimensionValidator,
        AttributeKey.HEIGHT.xmlName to DimensionValidator,
        AttributeKey.ID.xmlName to PassThroughValidator,
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.INPUT_TYPE.xmlName to CategoricalValidator(inputTypeValues),
        AttributeKey.HINT.xmlName to PassThroughValidator
    )
}

object SliderGrammar : WidgetGrammar {
    override val tag = "com.google.android.material.slider.Slider"
    override val attributes = mapOf(
        AttributeKey.WIDTH.xmlName to DimensionValidator,
        AttributeKey.HEIGHT.xmlName to DimensionValidator,
        AttributeKey.ID.xmlName to PassThroughValidator,
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.LAYOUT_WEIGHT.xmlName to PassThroughValidator,
        AttributeKey.STYLE.xmlName to SliderStyleValidator
    )
}
