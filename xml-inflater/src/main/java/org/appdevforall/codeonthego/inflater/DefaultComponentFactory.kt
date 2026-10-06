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

package org.appdevforall.codeonthego.inflater

import android.view.View
import android.view.ViewGroup
import org.appdevforall.codeonthego.inflater.internal.AttributeImpl
import org.appdevforall.codeonthego.inflater.internal.LayoutFile
import org.appdevforall.codeonthego.inflater.internal.ViewGroupImpl
import org.appdevforall.codeonthego.inflater.internal.ViewImpl

/** Default implementation of [IComponentFactory]. */
open class DefaultComponentFactory : IComponentFactory {

  override fun createView(file: LayoutFile, name: String, view: View): IView {
    return if (view is ViewGroup) {
      ViewGroupImpl(file, name, view)
    } else ViewImpl(file, name, view)
  }

  override fun createAttr(
    view: IView,
    namespace: INamespace?,
    name: String,
    value: String
  ): IAttribute {
    return AttributeImpl(namespace = namespace, name = name, value = value)
  }
}