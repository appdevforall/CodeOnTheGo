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
import org.junit.Test

/**
 * Verifies that the visited-set guard used by
 * [ModuleProject.getCompileModuleProjects] (see [AndroidModule] and [JavaModule]) terminates on a
 * cyclic module-dependency graph instead of recursing until the stack overflows (Sentry issue Z0).
 *
 * Constructing real [AndroidModule]/[JavaModule] instances requires fully-wired protobuf project
 * models, a registered workspace and variant dependencies, so this test exercises the exact
 * guard pattern (`if (!visited.add(path)) return emptyList()`) over a minimal in-memory graph that
 * mirrors the production traversal.
 *
 * @author Akash Yadav
 */
class CompileModuleProjectsCycleTest {

  /** Minimal stand-in for a module that mirrors the production traversal + cycle guard. */
  private class FakeModule(val path: String) {
    val dependencies = mutableListOf<FakeModule>()

    fun compileModuleProjects(visited: MutableSet<String> = HashSet()): List<FakeModule> {
      // Same guard as AndroidModule/JavaModule.getCompileModuleProjects(visited).
      if (!visited.add(path)) {
        return emptyList()
      }

      val result = mutableListOf<FakeModule>()
      for (dependency in dependencies) {
        result.add(dependency)
        result.addAll(dependency.compileModuleProjects(visited))
      }
      return result
    }
  }

  @Test
  fun `terminates on a two-node dependency cycle`() {
    val a = FakeModule(":a")
    val b = FakeModule(":b")
    // a -> b -> a
    a.dependencies.add(b)
    b.dependencies.add(a)

    // Without the visited-set guard this would recurse until StackOverflowError.
    val result = a.compileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":b", ":a")
  }

  @Test
  fun `terminates on a self dependency cycle`() {
    val a = FakeModule(":a")
    a.dependencies.add(a)

    val result = a.compileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":a")
  }

  @Test
  fun `terminates on a longer cycle and expands each module at most once`() {
    val a = FakeModule(":a")
    val b = FakeModule(":b")
    val c = FakeModule(":c")
    // a -> b -> c -> a
    a.dependencies.add(b)
    b.dependencies.add(c)
    c.dependencies.add(a)

    val result = a.compileModuleProjects()

    assertThat(result.map { it.path }).containsExactly(":b", ":c", ":a")
  }
}
