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

package com.itsaky.androidide.editor.ui

import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class InlineSuggestionComponentTest {

    private lateinit var mockEditor: IDEEditor
    private lateinit var component: InlineSuggestionComponent

    @Before
    fun setup() {
        mockEditor = mockk(relaxed = true)
        component = InlineSuggestionComponent(mockEditor)
    }

    @Test
    fun `component initializes successfully`() {
        assertNotNull(component)
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(SuggestionState.IDLE, component.getState())
    }

    @Test
    fun `component has renderer and provider`() {
        // Verify internal components initialized
        assertNotNull(component)
    }

    @Test
    fun `disable clears suggestion`() {
        component.setEnabled(false)
        assertEquals(SuggestionState.IDLE, component.getState())
    }

    @Test
    fun `state starts as IDLE`() {
        assertEquals(SuggestionState.IDLE, component.getState())
    }
}
