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

package com.itsaky.androidide.templates.base.root

import com.itsaky.androidide.templates.base.ProjectTemplateBuilder

fun ProjectTemplateBuilder.composeTomlFileSrc() = """
[versions]
agp = "8.5.0"
kotlin = "1.9.22"
coreKtx = "1.15.0"
junit = "4.13.2"
junitVersion = "1.2.1"
espressoCore = "3.6.1"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.04.01"
composeUiVersion = "1.6.6"
material3Version = "1.3.0"
uiToolingPreviewAndroid = "1.6.6"
collectionVersion = "1.4.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui", version.ref = "composeUiVersion" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics", version.ref = "composeUiVersion" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling", version.ref = "composeUiVersion" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview", version.ref = "composeUiVersion" }
androidx-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest", version.ref = "composeUiVersion" }
androidx-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3Version" }
androidx-ui-tooling-preview-android = { group = "androidx.compose.ui", name = "ui-tooling-preview-android", version.ref = "uiToolingPreviewAndroid" }
collection-jvm = { group = "androidx.collection", name = "collection-jvm", version.ref = "collectionVersion" }
collection-ktx = { group = "androidx.collection", name = "collection-ktx", version.ref = "collectionVersion"}

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
jetbrains-kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
""".trimIndent()