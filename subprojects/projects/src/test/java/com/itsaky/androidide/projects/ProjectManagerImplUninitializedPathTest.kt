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

package com.itsaky.androidide.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reproduction test for ADFA-4326.
 *
 * The OS can recreate EditorActivity after process death without ever routing through
 * MainActivity, leaving the [ProjectManagerImpl] singleton's `lateinit projectPath` unset.
 * EditorActivity (via EditorViewModel.getProjectName -> projectDir -> projectDirPath) reads
 * [ProjectManagerImpl.projectDirPath] during onCreate. Before the fix, the getter delegated
 * directly to the uninitialized `lateinit` (`get() = projectPath`), which throws
 * [UninitializedPropertyAccessException] and crashes the editor on recreation.
 *
 * The fix guards the getter with `isInitialized`, returning a blank path so the caller can
 * detect the missing project and route back to MainActivity instead of crashing.
 *
 * Mutation check: if the guard is removed (raw `get() = projectPath`), [readingPathWhenUninitialized_returnsBlankAndDoesNotThrow]
 * throws instead of returning "" -> RED. With the fix it returns "" -> GREEN.
 *
 * @author ADFA verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectManagerImplUninitializedPathTest {

  /**
   * Directly exercises the root-cause line of the bug on a fresh instance whose `projectPath`
   * lateinit has never been assigned (the process-death recreation state).
   */
  @Test
  fun readingPathWhenUninitialized_returnsBlankAndDoesNotThrow() {
    val manager = ProjectManagerImpl()

    // Pre-fix this access throws UninitializedPropertyAccessException; post-fix it returns "".
    val path = manager.projectDirPath

    assertThat(path).isEmpty()
  }

  /**
   * projectDir is derived from projectDirPath (File(projectDirPath)). With the uninitialized
   * path it must not propagate the lateinit access exception either.
   */
  @Test
  fun readingProjectDirWhenUninitialized_doesNotThrow() {
    val manager = ProjectManagerImpl()

    val dir = manager.projectDir

    // File("") -> empty path; the contract here is simply "no UninitializedPropertyAccessException".
    assertThat(dir.path).isEmpty()
  }

  /**
   * Once a path is assigned (the normal MainActivity-routed launch), the getter still reflects it.
   * Guards against a fix that always returns "" regardless of state.
   */
  @Test
  fun readingPathAfterInitialization_reflectsAssignedValue() {
    val manager = ProjectManagerImpl()
    manager.projectPath = "/storage/emulated/0/CodeOnTheGoProjects/Sample"

    assertThat(manager.projectDirPath).isEqualTo("/storage/emulated/0/CodeOnTheGoProjects/Sample")
    assertThat(manager.projectDir.name).isEqualTo("Sample")
  }
}
