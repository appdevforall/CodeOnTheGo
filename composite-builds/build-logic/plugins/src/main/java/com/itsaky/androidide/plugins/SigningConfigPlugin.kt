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

package com.itsaky.androidide.plugins

import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.itsaky.androidide.build.config.KEY_ALIAS
import com.itsaky.androidide.build.config.KEY_PASS
import com.itsaky.androidide.build.config.KEY_STORE_PASS
import com.itsaky.androidide.build.config.isFDroidBuild
import com.itsaky.androidide.build.config.signingKey
import com.itsaky.androidide.plugins.util.SigningKeyUtils.downloadSigningKey
import com.itsaky.androidide.plugins.util.SigningKeyUtils.getEnvOrProp
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configures the signing keys to application modules.
 *
 * @author Akash Yadav
 */
class SigningConfigPlugin : Plugin<Project> {
	companion object {
		private var warned = false
	}

	override fun apply(target: Project) {
		target.run {
			if (isFDroidBuild) {
				logger.warn("!!! Do not apply ${javaClass.simpleName} when building or F-Droid.")
				return
			}

			// Download the signing key
			downloadSigningKey()

			// Keystore credentials
			val alias = getEnvOrProp(KEY_ALIAS)
			val storePass = getEnvOrProp(KEY_STORE_PASS)
			val keyPass = getEnvOrProp(KEY_PASS)

			val signingKey = signingKey.get().asFile

			if (alias == null || storePass == null || keyPass == null || !signingKey.exists()) {
				if (!warned) {
					warned = true
					logger.warn(
						"Signing info not configured. " +
							"keystoreFile=$signingKey[exists=${signingKey.exists()}]",
					)
				}
				return
			}

			val configureKey: ApkSigningConfig.() -> Unit = {
				storeFile = signingKey
				keyAlias = alias
				storePassword = storePass
				keyPassword = keyPass
			}

			when (val android = extensions.getByType(CommonExtension::class.java)) {
				is ApplicationExtension -> {
					val config = android.signingConfigs.create("common", configureKey)
					android.buildTypes.forEach { it.signingConfig = config }
				}

				is LibraryExtension -> {
					val config = android.signingConfigs.create("common", configureKey)
					android.buildTypes.forEach { it.signingConfig = config }
				}
			}
		}
	}
}
