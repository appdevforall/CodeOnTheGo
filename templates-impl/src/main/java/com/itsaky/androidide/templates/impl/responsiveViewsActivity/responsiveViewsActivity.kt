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

package com.itsaky.androidide.templates.impl.responsiveViewsActivity

import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.base.AndroidModuleTemplateBuilder
import com.itsaky.androidide.templates.base.modules.android.defaultAppModule
import com.itsaky.androidide.templates.base.util.AndroidModuleResManager.ResourceType.LAYOUT
import com.itsaky.androidide.templates.base.util.SourceWriter
import com.itsaky.androidide.templates.impl.R
import com.itsaky.androidide.templates.impl.base.createRecipe
import com.itsaky.androidide.templates.impl.base.emptyThemesAndColors
import com.itsaky.androidide.templates.impl.base.writeMainActivity
import com.itsaky.androidide.templates.impl.baseProjectImpl
import com.itsaky.androidide.templates.impl.basicActivity.basicActivitySrcJava

fun responsiveViewsActivity() : ProjectTemplate {
    return baseProjectImpl {
        templateName = R.string.template_responsive_views_activity
        thumb = R.drawable.template_bottom_navigation_activity //TODO JMT replace with icon
        defaultAppModule {
            recipe = createRecipe {
                sources {
                    writeResponsiveViewsActivitySrc(this)
                }

                res {
                    writeResponsiveViewsActivityLayout()
                    emptyThemesAndColors()
                }
            }
        }
    }
}

private fun AndroidModuleTemplateBuilder.writeResponsiveViewsActivitySrc(
    writer: SourceWriter
) {
    writeMainActivity(
        writer = writer, ktSrc = ::responsiveViewsActivitySrcKt,
        javaSrc = ::basicActivitySrcJava
    )
}

internal fun AndroidModuleTemplateBuilder.writeResponsiveViewsActivityLayout() {
    res.apply {
        writeXmlResource("activity_main", LAYOUT, source = ::responsiveViewsActivityLayout)
    }
}