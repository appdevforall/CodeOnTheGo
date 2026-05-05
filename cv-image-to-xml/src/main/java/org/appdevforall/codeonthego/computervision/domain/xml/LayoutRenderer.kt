package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox

class LayoutRenderer(
    private val context: XmlContext,
    annotations: Map<ScaledBox, String>,
    selectedImageOverrides: Map<ScaledBox, String> = emptyMap()
) {
    private val widgetFactory = WidgetFactory(context, annotations, selectedImageOverrides)

    fun render(item: LayoutItem, indent: String = "        ") {
        val widgets = widgetFactory.createWidgets(item)

        widgets.forEach { widget ->
            widget.render(context, indent)
            context.appendLine()
        }
    }
}
