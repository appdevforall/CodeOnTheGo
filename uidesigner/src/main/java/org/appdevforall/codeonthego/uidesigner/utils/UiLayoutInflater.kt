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

package org.appdevforall.codeonthego.uidesigner.utils

import org.appdevforall.codeonthego.inflater.ILayoutInflater
import org.appdevforall.codeonthego.inflater.internal.LayoutInflaterImpl
import org.appdevforall.codeonthego.lookup.Lookup

/**
 * Layout inflater implmentation for the UI designer.
 *
 * @author Akash Yadav
 */
class UiLayoutInflater : LayoutInflaterImpl() {

  init {
    this.componentFactory = UiInflaterComponentFactory()
    Lookup.getDefault().update(ILayoutInflater.LOOKUP_KEY, this)
  }
}
