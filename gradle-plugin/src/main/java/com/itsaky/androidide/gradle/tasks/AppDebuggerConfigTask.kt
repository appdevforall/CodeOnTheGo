package com.itsaky.androidide.gradle.tasks

import com.itsaky.androidide.gradle.utils.JdwpOptions
import com.itsaky.androidide.gradle.utils.JdwpOptions.Companion.jdwpOptions
import com.itsaky.androidide.gradle.utils.ManifestData
import com.itsaky.androidide.gradle.utils.ManifestExtractor
import com.itsaky.androidide.gradle.utils.ManifestExtractor.ANDROID_NS
import com.itsaky.androidide.gradle.utils.ManifestExtractor.EXPR_APPLICATION
import com.itsaky.androidide.gradle.utils.ManifestExtractor.EXPR_PERM_INTERNET
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * @author Akash Yadav
 */
abstract class AppDebuggerConfigTask : DefaultTask() {

    /**
     * Options for configuring JDWP in target application.
     */
    @get:Input
    abstract val jdwpOptions: Property<JdwpOptions>

    /**
     * The input manifest file of the application.
     */
    @get:InputFile
    abstract val manifestIn: RegularFileProperty

    /**
     * The output manifest file of the application.
     */
    @get:OutputFile
    abstract val manifestOut: RegularFileProperty

    /**
     * The output directory for generated Java sources.
     */
    @get:OutputDirectory
    abstract val javaOut: DirectoryProperty

    companion object {
        private const val COGO_APP_PACKAGE = "org.adfa.cogo"
        private const val COGO_APP_NAME = "CogoDebuggableApp"
        private const val COGO_APP_CLASS = "$COGO_APP_PACKAGE.$COGO_APP_NAME"
    }

    @TaskAction
    fun run() {
        val jdwpOptions = jdwpOptions.get()
        val manifestIn = manifestIn.asFile.get()
        val manifestOut = manifestOut.asFile.get()
        val javaOut = javaOut.asFile.get()

        val docBuilderFactory = DocumentBuilderFactory.newInstance()
        docBuilderFactory.isNamespaceAware = true

        val xpathFactory = XPathFactory.newInstance()
        val xpath = xpathFactory.newXPath()
        xpath.namespaceContext = ManifestExtractor.ANDROID_NS_CONTEXT

        val docBuilder = docBuilderFactory.newDocumentBuilder()
        val doc = docBuilder.parse(manifestIn)

        val manifestData = ManifestExtractor.extract(doc)

        if (jdwpOptions.libName.isBlank()) {
            throw GradleException("Missing or blank JDWP library name")
        }

        if (jdwpOptions.libName.contains('=')) {
            throw GradleException("Invalid JDWP library name: ${jdwpOptions.libName} (cannot contain '=')")
        }

        logger.info(
            "Using JDWP options: libName={}, options={}",
            jdwpOptions.libName,
            jdwpOptions.options
        )

        val applicationSource = createDebuggerApplicationSource(manifestData, jdwpOptions)
        val applicationFile =
            javaOut.resolve("${COGO_APP_PACKAGE.replace('.', '/')}/${COGO_APP_NAME}.java")
        applicationFile.parentFile.mkdirs()
        applicationFile.writeText(applicationSource)

        val appNode = xpath.evaluate(EXPR_APPLICATION, doc, XPathConstants.NODE) as Node?
        checkNotNull(appNode) {
            "<application> tag not found in $manifestIn"
        }

        check(appNode.nodeType == Node.ELEMENT_NODE) {
            "<application> tag is not an element in $manifestIn"
        }

        logger.info("Set application class from '${manifestData.applicationClass}' to '$COGO_APP_CLASS'")
        (appNode as Element).setAttributeNS(ANDROID_NS, "android:name", COGO_APP_CLASS)

        val internetPermNode = xpath.evaluate(EXPR_PERM_INTERNET, doc, XPathConstants.NODE)
        if (internetPermNode == null) {
            logger.info("Adding INTERNET permissiont to manifest")
            val manifest = run {
                val manifests = doc.getElementsByTagName("manifest")
                if (manifests.length == 0) {
                    throw GradleException("<manifest> tag not found in $manifestIn")
                }

                manifests.item(0)
            }

            val permNode = doc.createElement("uses-permission")
            permNode.setAttributeNS(ANDROID_NS, "android:name", "android.permission.INTERNET")
            manifest.appendChild(permNode)
        } else {
            logger.info("No need to add INTERNET permission to manifest")
        }

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.transform(DOMSource(doc), StreamResult(manifestOut))
    }

    private fun createDebuggerApplicationSource(
        manifestData: ManifestData,
        jdwpOptions: JdwpOptions,
    ) = """
        package ${COGO_APP_PACKAGE};
        
        import android.os.Build;
        import android.os.Debug;
        import android.util.Log;
        
        public class $COGO_APP_NAME extends ${if (manifestData.applicationClass.isNullOrBlank()) "android.app.Application" else "${manifestData.applicationClass}"} {
        
            private static final String TAG = "$COGO_APP_NAME";
        
            @Override
            public void onCreate() {
                Log.d(TAG, "onCreate");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        Log.i(TAG, "Attaching debugger agent: libName=${jdwpOptions.libName}, options=${jdwpOptions.options}");
                        Debug.attachJvmtiAgent("lib${jdwpOptions.libName}.so", "${jdwpOptions.options}", getClassLoader());
                    } catch (Throwable err) {
                        Log.e(TAG, "Failed to attach JVM TI agent", err);
                    }
                } else {
                    Log.i(TAG, "Debugger agent is only supported on API >= 28");
                }
                
                super.onCreate();
            }
        }
    """.trimIndent()
}