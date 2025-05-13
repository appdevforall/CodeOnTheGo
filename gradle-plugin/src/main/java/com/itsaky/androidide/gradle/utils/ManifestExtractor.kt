package com.itsaky.androidide.gradle.utils

import org.w3c.dom.Document
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

data class ManifestData(
    val packageName: String?,
    val applicationClass: String?,
)

/**
 * @author Akash Yadav
 */
object ManifestExtractor {

    const val EXPR_PACKAGE_NAME = "//manifest/@package"
    const val EXPR_APPLICATION = "//manifest/application"
    const val EXPR_APPLICATION_NAME = "//manifest/application/@android:name"
    const val EXPR_PERM_INTERNET = "//manifest/uses-permission[@android:name='android.permission.INTERNET']"

    val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    val ANDROID_NS_CONTEXT = object : NamespaceContext {
        override fun getNamespaceURI(p0: String?): String = when (p0) {
            "android" -> ANDROID_NS
            else -> XMLConstants.NULL_NS_URI
        }

        override fun getPrefix(p0: String?): String? = null

        override fun getPrefixes(p0: String?): MutableIterator<String>? = null
    }

    fun extract(input: InputStream): ManifestData {
        val docBuilderFactory = DocumentBuilderFactory.newInstance()
        docBuilderFactory.isNamespaceAware = true
        val docBuilder = docBuilderFactory.newDocumentBuilder()
        val doc = docBuilder.parse(input)
        return extract(doc)
    }

    fun extract(doc: Document) : ManifestData {
        val xpathFactory = XPathFactory.newInstance()
        val xpath = xpathFactory.newXPath()
        xpath.namespaceContext = ANDROID_NS_CONTEXT

        return extract(doc, xpath)
    }

    fun extract(doc: Document, xpath: XPath): ManifestData {
        val packageName = xpath.evaluate(EXPR_PACKAGE_NAME, doc, XPathConstants.STRING)
        val applicationName = xpath.evaluate(EXPR_APPLICATION_NAME, doc, XPathConstants.STRING)

        return ManifestData(
            packageName?.toString(),
            applicationName?.toString(),
        )
    }
}