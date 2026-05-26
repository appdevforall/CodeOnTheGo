package org.appdevforall.codeonthego.computervision.domain.grammar

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.GravityValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.InputTypeValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.VisibilityValueSet

interface WidgetGrammar {
    val tag: String
    val attributes: Map<String, AttributeValidator>
        get() = mapOf(
            AttributeKey.WIDTH.xmlName to DimensionValidator,
            AttributeKey.HEIGHT.xmlName to DimensionValidator,
            AttributeKey.ID.xmlName to PassThroughValidator
        )
}

interface LayoutGrammar : WidgetGrammar {
    override val attributes: Map<String, AttributeValidator>
        get() = super.attributes + mapOf(
            AttributeKey.LAYOUT_MARGIN.xmlName to DimensionValidator,
            AttributeKey.LAYOUT_MARGIN_TOP.xmlName to DimensionValidator,
            AttributeKey.LAYOUT_MARGIN_BOTTOM.xmlName to DimensionValidator,
            AttributeKey.LAYOUT_MARGIN_START.xmlName to DimensionValidator,
            AttributeKey.LAYOUT_MARGIN_END.xmlName to DimensionValidator,
            AttributeKey.LAYOUT_GRAVITY.xmlName to CategoricalValidator(GravityValueSet.values),
            AttributeKey.GRAVITY.xmlName to CategoricalValidator(GravityValueSet.values),
            AttributeKey.LAYOUT_WEIGHT.xmlName to PassThroughValidator,
            AttributeKey.PADDING.xmlName to DimensionValidator,
            AttributeKey.VISIBILITY.xmlName to CategoricalValidator(VisibilityValueSet.values),
            AttributeKey.BACKGROUND.xmlName to PassThroughValidator,
            AttributeKey.BACKGROUND_TINT.xmlName to PassThroughValidator
        )
}

interface TextGrammar : LayoutGrammar {
    override val attributes: Map<String, AttributeValidator>
        get() = super.attributes + mapOf(
            AttributeKey.TEXT_COLOR.xmlName to PassThroughValidator,
            AttributeKey.TEXT_SIZE.xmlName to PassThroughValidator,
            AttributeKey.TEXT_STYLE.xmlName to PassThroughValidator,
            AttributeKey.TEXT_ALIGNMENT.xmlName to PassThroughValidator,
            AttributeKey.FONT_FAMILY.xmlName to PassThroughValidator
        )
}

interface CompoundButtonGrammar : TextGrammar {
    override val attributes: Map<String, AttributeValidator>
        get() = super.attributes + mapOf(
            AttributeKey.TEXT.xmlName to PassThroughValidator,
            AttributeKey.CHECKED.xmlName to BooleanValidator,
            AttributeKey.TEXT_SIZE.xmlName to SpDimensionRangeValidator(minSp = 8, maxSp = 32)
        )
}


object SpinnerGrammar : LayoutGrammar {
    override val tag = "Spinner"
    override val attributes = super.attributes + mapOf(
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.ENTRIES.xmlName to EntriesValidator
    )
}

object ImageViewGrammar : LayoutGrammar {
    override val tag = "ImageView"

    override val attributes = super.attributes + mapOf(
        AttributeKey.SRC.xmlName to PassThroughValidator
    )
}

object EditTextGrammar : TextGrammar {
    override val tag = "EditText"

    override val attributes = super.attributes + mapOf(
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.INPUT_TYPE.xmlName to FlagsCategoricalValidator(InputTypeValueSet.values),
        AttributeKey.HINT.xmlName to PassThroughValidator
    )
}

object RadioButtonGrammar : CompoundButtonGrammar {
    override val tag = "RadioButton"
}

object CheckBoxGrammar : CompoundButtonGrammar {
    override val tag = "CheckBox"
}

object SwitchGrammar : CompoundButtonGrammar {
    override val tag = "Switch"
}

object RadioGroupGrammar : TextGrammar {
    override val tag = "RadioGroup"
    override val attributes = super.attributes + mapOf(
        AttributeKey.ORIENTATION.xmlName to CategoricalValidator(listOf("horizontal", "vertical")),
        AttributeKey.TEXT_SIZE.xmlName to SpDimensionRangeValidator(minSp = 8, maxSp = 32)
    )
}

object SliderGrammar : LayoutGrammar {
    override val tag = "com.google.android.material.slider.Slider"
    override val attributes = super.attributes + mapOf(
        AttributeKey.TEXT.xmlName to PassThroughValidator,
        AttributeKey.STYLE.xmlName to SliderStyleValidator
    )
}

object TextViewGrammar : TextGrammar {
    override val tag = "TextView"
    override val attributes = super.attributes + mapOf(
        AttributeKey.TEXT.xmlName to PassThroughValidator
    )
}

object ButtonGrammar : TextGrammar {
    override val tag = "Button"
    override val attributes = super.attributes + mapOf(
        AttributeKey.TEXT.xmlName to PassThroughValidator
    )
}
