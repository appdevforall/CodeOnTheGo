package com.itsaky.androidide.gradle.utils

import com.itsaky.androidide.tooling.api.ToolingConfig.JDWP_LIBNAME_DEFAULT
import com.itsaky.androidide.tooling.api.ToolingConfig.JDWP_OPTIONS_DEFAULT
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_LIBNAME
import com.itsaky.androidide.tooling.api.ToolingConfig.PROP_JDWP_OPTIONS
import org.gradle.api.Project

/**
 * @author Akash Yadav
 */
data class JdwpOptions(
    val libName: String = JDWP_LIBNAME_DEFAULT,
    val options: String = JDWP_OPTIONS_DEFAULT,
): java.io.Serializable {

    companion object {

        @JvmField
        val serialVersionUID = 1L

        /**
         * The default values for [JdwpOptions].
         */
        val DEFAULT = JdwpOptions()

        /**
         * Creates a [JdwpOptions] from the system properties.
         */
        fun Project. jdwpOptions(): JdwpOptions {
            var libName = JDWP_LIBNAME_DEFAULT
            if (hasProperty(PROP_JDWP_LIBNAME)) {
                libName = findProperty(PROP_JDWP_LIBNAME)!!.toString()
            }

            var options = JDWP_OPTIONS_DEFAULT
            if (hasProperty(PROP_JDWP_OPTIONS)) {
                options = findProperty(PROP_JDWP_OPTIONS)!!.toString()
            }

            return JdwpOptions(libName = libName, options = options)
        }
    }
}