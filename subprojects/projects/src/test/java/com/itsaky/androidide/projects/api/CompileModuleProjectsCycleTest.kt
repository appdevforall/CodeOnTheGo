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

package com.itsaky.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.ProjectManagerImpl
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [AndroidModule.getCompileModuleProjects] (via [ModuleProject.getCompileModuleProjects])
 * terminates on a *cyclic* project-dependency graph instead of recursing until the stack overflows
 * (ADFA-4329 / Sentry).
 *
 * This builds a cyclic graph of *real* [AndroidModule] instances backed by protobuf project models
 * (each module's [AndroidModels.VariantDependencies] references the next module as a `Project`
 * library), registers them in a real [Workspace] on the production [ProjectManagerImpl], and drives
 * the actual production traversal in [AndroidModule.getCompileModuleProjects].
 *
 * On the pre-fix code (no visited-set guard) a cycle a -> b -> a recurses
 * `a.getCompileModuleProjects -> b.getCompileModuleProjects -> a.getCompileModuleProjects -> ...`
 * forever and blows the stack with [StackOverflowError]. With the fix it terminates and each module
 * is expanded at most once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CompileModuleProjectsCycleTest {

  @After
  fun tearDown() {
    runCatching { ProjectManagerImpl.getInstance().workspace = null }
  }

  /**
   * Build a real [AndroidModule] at Gradle [path] whose compile-scope project dependencies are
   * [moduleDeps] (Gradle project paths). The dependency graph is encoded exactly the way the tooling
   * layer encodes it: a [AndroidModels.GraphItem] in the main artifact's compile graph keyed to a
   * [AndroidModels.Library] of type [AndroidModels.LibraryType.Project] that points at the dependency
   * module via [AndroidModels.ProjectInfo.getProjectPath].
   */
  private fun androidModule(path: String, moduleDeps: List<String>): AndroidModule {
    val mainArtifact = AndroidModels.ArtifactDependencies.newBuilder()
    val variantDeps = AndroidModels.VariantDependencies.newBuilder().setName("debug")

    for (depPath in moduleDeps) {
      val key = "project$depPath"
      mainArtifact.addCompileDependency(
        AndroidModels.GraphItem.newBuilder().setKey(key).build(),
      )
      variantDeps.putLibraries(
        key,
        AndroidModels.Library.newBuilder()
          .setKey(key)
          .setType(AndroidModels.LibraryType.Project)
          .setProjectInfo(
            AndroidModels.ProjectInfo.newBuilder()
              .setBuildId(":")
              .setProjectPath(depPath)
              .build(),
          )
          .build(),
      )
    }
    variantDeps.setMainArtifact(mainArtifact.build())

    val androidProject = AndroidModels.AndroidProject.newBuilder()
      .setProjectType(AndroidModels.ProjectType.LibraryProject)
      .setVariantDependencies(variantDeps.build())
      .build()

    val gradleProject = GradleModels.GradleProject.newBuilder()
      .setName(path.trimStart(':'))
      .setPath(path)
      .setProjectDirPath("/tmp/cycle-test${path.replace(':', '/')}")
      .setBuildDirPath("/tmp/cycle-test${path.replace(':', '/')}/build")
      .setBuildScriptPath("/tmp/cycle-test${path.replace(':', '/')}/build.gradle")
      .setAndroidProject(androidProject)
      .build()

    return AndroidModule(gradleProject)
  }

  private fun installWorkspace(vararg modules: ModuleProject) {
    val root = GradleProject(
      GradleModels.GradleProject.newBuilder().setName("root").setPath(":").build(),
    )
    ProjectManagerImpl.getInstance().workspace =
      Workspace(rootProject = root, subProjects = modules.toList(), syncIssues = emptyList())
  }

  @Test(timeout = 30_000)
  fun `terminates on a two-node module dependency cycle`() {
    // a -> b -> a (compile scope, real AndroidModule instances)
    val a = androidModule(":a", listOf(":b"))
    val b = androidModule(":b", listOf(":a"))
    installWorkspace(a, b)

    // Pre-fix: a -> b -> a -> b -> ... until StackOverflowError.
    // With the fix: terminates; each module expanded at most once.
    val result = a.getCompileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":b", ":a")
  }

  @Test(timeout = 30_000)
  fun `terminates on a self dependency cycle`() {
    val a = androidModule(":a", listOf(":a"))
    installWorkspace(a)

    val result = a.getCompileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":a")
  }

  @Test(timeout = 30_000)
  fun `terminates on a longer cycle and expands each module at most once`() {
    // a -> b -> c -> a
    val a = androidModule(":a", listOf(":b"))
    val b = androidModule(":b", listOf(":c"))
    val c = androidModule(":c", listOf(":a"))
    installWorkspace(a, b, c)

    val result = a.getCompileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":b", ":c", ":a")
  }
}
