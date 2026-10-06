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

package org.appdevforall.codeonthego.inflater.internal.adapters

import android.content.Context
import android.view.TextureView
import android.view.View
import org.appdevforall.codeonthego.annotations.uidesigner.IncludeInDesigner
import org.appdevforall.codeonthego.annotations.uidesigner.IncludeInDesigner.Group.WIDGETS
import org.appdevforall.codeonthego.inflater.AttributeHandlerScope
import org.appdevforall.codeonthego.inflater.internal.ui.DesignerTextureView
import org.appdevforall.codeonthego.inflater.models.UiWidget
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string

/**
 * Attribute adapter for [TextureView].
 *
 * @author Akash Yadav
 */
@org.appdevforall.codeonthego.annotations.inflater.ViewAdapter(TextureView::class)
@IncludeInDesigner(group = WIDGETS)
open class TextureViewAdapter<T : TextureView> : ViewAdapter<T>() {

  private val unsupportedAttrs = arrayOf("background", "foreground")

  override fun postCreateAttrHandlers(
    handlers: MutableMap<String, AttributeHandlerScope<T>.() -> Unit>) {

    unsupportedAttrs.forEach(handlers::remove)
  }

  override fun createUiWidgets(): List<UiWidget> {
    return listOf(
      UiWidget(DesignerTextureView::class.java, string.widget_textureview,
        drawable.ic_widget_textureview)
    )
  }

  override fun onCreateView(name: String, context: Context): View? {
    if (name == TextureView::class.java.name) {
      return DesignerTextureView(context)
    }
    return super.onCreateView(name, context)
  }
}
