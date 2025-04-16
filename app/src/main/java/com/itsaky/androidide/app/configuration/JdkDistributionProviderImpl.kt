/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.app.configuration

import com.google.auto.service.AutoService
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.JdkUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author Akash Yadav
 */
@AutoService(IJdkDistributionProvider::class)
class JdkDistributionProviderImpl : IJdkDistributionProvider {

  companion object {

    private val log = LoggerFactory.getLogger(JdkDistributionProviderImpl::class.java)
  }

  private var _installedDistributions: List<JdkDistribution>? = null

  override val installedDistributions: List<JdkDistribution>
    get() = _installedDistributions ?: emptyList()

  override fun loadDistributions() {
    _installedDistributions = doLoadDistributions()
  }

    private fun doLoadDistributions(): List<JdkDistribution> {
        return JdkUtils.findJavaInstallations().also { distributions ->
            if(distributions.isEmpty()) {
                return emptyList()
            }
            var selectedJavaHome = findDefaultDistribution(distributions)?.javaHome?.let { File(it) }
                ?: distributions.first().javaHome.let { File(it) }

            log.debug("Setting Environment.JAVA_HOME to {}", selectedJavaHome.absolutePath)
            Environment.JAVA_HOME = selectedJavaHome
            Environment.JAVA = selectedJavaHome.resolve("bin/java")
        }
    }

  private fun findDefaultDistribution(distributions: List<JdkDistribution>): JdkDistribution? {
    return distributions.find {
      it.javaVersion.startsWith(IJdkDistributionProvider.DEFAULT_JAVA_VERSION)
    }
  }

  private fun isValidJavaHome(javaHome: File): Boolean {
    val javaExec = javaHome.resolve("bin/java")
    return javaHome.exists() && javaExec.exists() && javaExec.isFile && javaExec.canExecute()
  }
}