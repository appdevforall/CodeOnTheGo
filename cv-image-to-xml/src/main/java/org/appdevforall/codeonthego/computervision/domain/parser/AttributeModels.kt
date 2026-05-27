package org.appdevforall.codeonthego.computervision.domain.parser

interface AttributeValueSet {
    val values: List<String>
}

object GravityValueSet : AttributeValueSet {
    override val values = listOf(
        "top",
        "bottom",
        "left",
        "right",
        "center",
        "center_vertical",
        "center_horizontal",
        "start",
        "end"
    )
}

object DimensionValueSet : AttributeValueSet {
    const val WRAP_CONTENT = "wrap_content"
    const val MATCH_PARENT = "match_parent"

    override val values = listOf(WRAP_CONTENT, MATCH_PARENT)

    val matchKeywords = setOf("match", "parent")
    val wrapKeywords = setOf("wrap", "content", "wrapcan")

    val allKeywords = matchKeywords + wrapKeywords
}

object VisibilityValueSet : AttributeValueSet {
    override val values = listOf(
        "visible",
        "invisible",
        "gone"
    )
}

object InputTypeValueSet : AttributeValueSet {
    override val values = listOf(
        "text",
        "textPassword",
        "number",
        "numberDecimal",
        "textEmailAddress",
        "textUri",
        "phone",
        "textVisiblePassword",
        "textPersonName",
        "textCapSentences",
        "textCapWords",
        "textMultiLine",
        "textNoSuggestions",
        "date",
        "time",
        "datetime"
    )
}

enum class ValueType {
    RAW,
    TEXT_CONTENT,
    DIMENSION,
    SP_DIMENSION,
    COLOR,
    ID,
    DRAWABLE,
    INTEGER,
    FLOAT,
    TEXT_STYLE
}

enum class AttributeKey(
    val xmlName: String,
    val aliases: List<String>,
    val valueType: ValueType = ValueType.RAW
) {
    WIDTH("android:layout_width", listOf("layout_width", "width"), ValueType.DIMENSION),
    HEIGHT("android:layout_height", listOf("layout_height", "height"), ValueType.DIMENSION),
    ID("android:id", listOf("id"), ValueType.ID),
    TEXT("android:text", listOf("text"), ValueType.TEXT_CONTENT),
    HINT("android:hint", listOf("hint"), ValueType.TEXT_CONTENT),
    BACKGROUND("android:background", listOf("background", "bg"), ValueType.COLOR),
    BACKGROUND_TINT("app:backgroundTint", listOf("backgroundtint", "background_tint", "bg_tint"), ValueType.COLOR),
    SRC("android:src", listOf("src", "scr", "sre", "5rc"), ValueType.DRAWABLE),
    CONTENT_DESCRIPTION("android:contentDescription", listOf("contentdescription", "content_description")),

    TEXT_SIZE("android:textSize", listOf("textsize", "text_size"), ValueType.SP_DIMENSION),
    TEXT_COLOR("android:textColor", listOf("textcolor", "text_color", "color", "text_colar", "textcolar"), ValueType.COLOR),
    TEXT_STYLE("android:textStyle", listOf("textstyle", "text_style"), ValueType.TEXT_STYLE),
    TEXT_ALIGNMENT("android:textAlignment", listOf("textalignment", "text_alignment")),
    TEXT_ALL_CAPS("android:textAllCaps", listOf("textallcaps", "text_all_caps")),
    FONT_FAMILY("android:fontFamily", listOf("fontfamily", "font_family", "font")),
    MAX_LINES("android:maxLines", listOf("maxlines", "max_lines"), ValueType.INTEGER),
    MIN_LINES("android:minLines", listOf("minlines", "min_lines"), ValueType.INTEGER),
    LINES("android:lines", listOf("lines"), ValueType.INTEGER),
    SINGLE_LINE("android:singleLine", listOf("singleline", "single_line")),
    ELLIPSIZE("android:ellipsize", listOf("ellipsize")),
    LINE_SPACING_EXTRA("android:lineSpacingExtra", listOf("linespacingextra", "line_spacing_extra"), ValueType.SP_DIMENSION),
    LETTER_SPACING("android:letterSpacing", listOf("letterspacing", "letter_spacing")),
    HINT_TEXT_COLOR("android:textColorHint", listOf("hinttextcolor", "hint_text_color", "textcolorhint", "text_color_hint"), ValueType.COLOR),
    IME_OPTIONS("android:imeOptions", listOf("imeoptions", "ime_options")),

    INPUT_TYPE("android:inputType", listOf("inputtype", "input_type")),
    MAX_LENGTH("android:maxLength", listOf("maxlength", "max_length"), ValueType.INTEGER),

    VISIBILITY("android:visibility", listOf("visibility")),
    ENABLED("android:enabled", listOf("enabled")),
    CLICKABLE("android:clickable", listOf("clickable")),
    FOCUSABLE("android:focusable", listOf("focusable")),
    ALPHA("android:alpha", listOf("alpha")),
    ELEVATION("android:elevation", listOf("elevation"), ValueType.DIMENSION),
    ROTATION("android:rotation", listOf("rotation")),

    PADDING("android:padding", listOf("padding"), ValueType.DIMENSION),
    PADDING_TOP("android:paddingTop", listOf("paddingtop", "padding_top"), ValueType.DIMENSION),
    PADDING_BOTTOM("android:paddingBottom", listOf("paddingbottom", "padding_bottom"), ValueType.DIMENSION),
    PADDING_START("android:paddingStart", listOf("paddingstart", "padding_start"), ValueType.DIMENSION),
    PADDING_END("android:paddingEnd", listOf("paddingend", "padding_end"), ValueType.DIMENSION),
    PADDING_LEFT("android:paddingLeft", listOf("paddingleft", "padding_left"), ValueType.DIMENSION),
    PADDING_RIGHT("android:paddingRight", listOf("paddingright", "padding_right"), ValueType.DIMENSION),

    LAYOUT_MARGIN("android:layout_margin", listOf("layout_margin", "margin"), ValueType.DIMENSION),
    LAYOUT_MARGIN_TOP("android:layout_marginTop", listOf("layout_margintop", "layout_margin_top", "margin_top", "margintop"), ValueType.DIMENSION),
    LAYOUT_MARGIN_BOTTOM("android:layout_marginBottom", listOf("layout_marginbottom", "layout_margin_bottom", "margin_bottom", "marginbottom"), ValueType.DIMENSION),
    LAYOUT_MARGIN_START("android:layout_marginStart", listOf("layout_marginstart", "layout_margin_start", "margin_start", "marginstart"), ValueType.DIMENSION),
    LAYOUT_MARGIN_END("android:layout_marginEnd", listOf("layout_marginend", "layout_margin_end", "margin_end", "marginend"), ValueType.DIMENSION),
    LAYOUT_MARGIN_LEFT("android:layout_marginLeft", listOf("layout_marginleft", "layout_margin_left", "margin_left"), ValueType.DIMENSION),
    LAYOUT_MARGIN_RIGHT("android:layout_marginRight", listOf("layout_marginright", "layout_margin_right", "margin_right"), ValueType.DIMENSION),

    LAYOUT_WEIGHT("android:layout_weight", listOf("layout_weight", "weight"), ValueType.FLOAT),
    LAYOUT_GRAVITY("android:layout_gravity", listOf("layout_gravity", "layaut_gravity")),
    GRAVITY("android:gravity", listOf("gravity")),
    ORIENTATION("android:orientation", listOf("orientation")),

    MIN_WIDTH("android:minWidth", listOf("minwidth", "min_width"), ValueType.DIMENSION),
    MIN_HEIGHT("android:minHeight", listOf("minheight", "min_height"), ValueType.DIMENSION),
    MAX_WIDTH("android:maxWidth", listOf("maxwidth", "max_width"), ValueType.DIMENSION),
    MAX_HEIGHT("android:maxHeight", listOf("maxheight", "max_height"), ValueType.DIMENSION),

    SCALE_TYPE("android:scaleType", listOf("scaletype", "scale_type")),
    ADJUST_VIEW_BOUNDS("android:adjustViewBounds", listOf("adjustviewbounds", "adjust_view_bounds")),
    TINT("android:tint", listOf("tint"), ValueType.COLOR),

    STYLE("style", listOf("style")),
    ENTRIES("android:entries", listOf("entries")),
    CHECKED("android:checked", listOf("checked")),

    CARD_CORNER_RADIUS("app:cardCornerRadius", listOf("cardcornerradius", "card_corner_radius", "cornerradius", "corner_radius"), ValueType.DIMENSION),
    CARD_ELEVATION("app:cardElevation", listOf("cardelevation", "card_elevation"), ValueType.DIMENSION),
    CARD_BACKGROUND_COLOR("app:cardBackgroundColor", listOf("cardbackgroundcolor", "card_background_color"), ValueType.COLOR),
    STROKE_COLOR("app:strokeColor", listOf("strokecolor", "stroke_color"), ValueType.COLOR),
    STROKE_WIDTH("app:strokeWidth", listOf("strokewidth", "stroke_width"), ValueType.DIMENSION),

    PROGRESS("android:progress", listOf("progress"), ValueType.INTEGER),
    MAX("android:max", listOf("max"), ValueType.INTEGER),
    MIN("android:min", listOf("min"), ValueType.INTEGER),
    VALUE_FROM("app:valueFrom", listOf("valuefrom", "value_from")),
    VALUE_TO("app:valueTo", listOf("valueto", "value_to")),
    STEP_SIZE("app:stepSize", listOf("stepsize", "step_size")),
    TRACK_COLOR("app:trackColor", listOf("trackcolor", "track_color"), ValueType.COLOR),
    THUMB_COLOR("app:thumbTint", listOf("thumbcolor", "thumb_color", "thumbtint", "thumb_tint"), ValueType.COLOR),

    FOREGROUND("android:foreground", listOf("foreground"), ValueType.COLOR),
    SPINNER_MODE("android:spinnerMode", listOf("spinnermode", "spinner_mode")),
    DRAWABLE_START("android:drawableStart", listOf("drawablestart", "drawable_start"), ValueType.DRAWABLE),
    DRAWABLE_END("android:drawableEnd", listOf("drawableend", "drawable_end"), ValueType.DRAWABLE),
    DRAWABLE_PADDING("android:drawablePadding", listOf("drawablepadding", "drawable_padding"), ValueType.DIMENSION);

    companion object {
        val allAliases: List<String> by lazy { entries.flatMap { it.aliases } }

        fun findByAlias(alias: String): AttributeKey? =
            entries.firstOrNull { key -> key.aliases.any { it == alias } }
    }
}
