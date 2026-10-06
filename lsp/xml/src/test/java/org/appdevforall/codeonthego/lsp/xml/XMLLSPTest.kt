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
package org.appdevforall.codeonthego.lsp.xml

import org.appdevforall.codeonthego.lookup.Lookup
import org.appdevforall.codeonthego.lsp.api.ILanguageServerRegistry
import org.appdevforall.codeonthego.lsp.api.LSPTest
import org.appdevforall.codeonthego.projects.api.ModuleProject
import org.appdevforall.codeonthego.projects.util.findAppModule
import org.appdevforall.codeonthego.xml.resources.ResourceTableRegistry
import org.appdevforall.codeonthego.xml.versions.ApiVersions
import org.appdevforall.codeonthego.xml.widgets.WidgetTable
import org.junit.Before
import org.junit.Ignore

/** @author Akash Yadav */
@Ignore("Base class for XML tests")
object XMLLSPTest : LSPTest() {

  @Before
  override fun initProjectIfNeeded() {
    super.initProjectIfNeeded()
    try {
      val module = findAppModule()!!
      val lookup = Lookup.getDefault()

      lookup.update(ModuleProject.COMPLETION_MODULE_KEY, module)

      val versions = module.getApiVersions()
      if (versions != null) {
        lookup.update(ApiVersions.COMPLETION_LOOKUP_KEY, versions)
      }

      val widgets = module.getWidgetTable()
      if (widgets != null) {
        lookup.update(WidgetTable.COMPLETION_LOOKUP_KEY, widgets)
      }

      val frameworkResources = module.getFrameworkResourceTable()
      if (frameworkResources != null) {
        lookup.update(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES, frameworkResources)
      }

      val moduleResources = module.getSourceResourceTables()
      if (moduleResources.isNotEmpty()) {
        lookup.update(ResourceTableRegistry.COMPLETION_MODULE_RES, moduleResources)
      }

      val depResTables = module.getDependencyResourceTables()
      if (depResTables.isNotEmpty()) {
        lookup.update(ResourceTableRegistry.COMPLETION_DEP_RES, depResTables)
      }

      val manifestAttrTable = module.getManifestAttrTable()
      if (manifestAttrTable != null) {
        lookup.update(ResourceTableRegistry.COMPLETION_MANIFEST_ATTR_RES, manifestAttrTable)
      }
    } catch (e: Throwable) {
      throw RuntimeException(e)
    }
  }

  override fun registerServer() {
    ILanguageServerRegistry.default.register(server)
  }

  override fun getServerId(): String {
    return XMLLanguageServer.SERVER_ID
  }

  override fun test() {}

  val server = XMLLanguageServer()
}
