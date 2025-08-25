package org.appdevforall.codeonthego.layouteditor.editor.convert

import android.content.Context
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class ConvertImportedXml(private val xml: String?) {

    fun getXmlConverted(context: Context): String? {
        var convertedXml = xml

        if (isWellFormed(xml)) {
            val pattern = "<([a-zA-Z0-9]+\\.)*([a-zA-Z0-9]+)"

            val matcher = Pattern.compile(pattern).matcher(
                xml.toString()
            )
            try {
                while (matcher.find()) {
                    val fullTag = matcher.group(0)?.replace("<", "")
                    val widgetName = matcher.group(2)

                    val classes =
                        JSONObject(FileUtil.readFromAsset("widgetclasses.json", context))

                    val widgetClass = widgetName?.let {
                        try {
                            classes.getString(it)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // If the widget isn't found in the mapping, use the original widget name
                            fullTag
                        }
                    }
                    if (convertedXml != null) {
                        convertedXml = convertedXml.replace("<$fullTag", "<$widgetClass")
                    }
                    if (convertedXml != null) {
                        convertedXml = convertedXml.replace("</$fullTag", "</$widgetClass")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        } else {
            return null
        }
        return convertedXml
    }

    private fun isWellFormed(xml: String?): Boolean {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val source = InputSource(StringReader(xml))
            builder.parse(source)
            return true
        } catch (e: Exception) {
            return false
        }
    }

}