package com.itsaky.androidide.plugins.aicodehelper

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.ServiceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class MenuActionsTest {

    private lateinit var plugin: AiCodeHelperPlugin
    private lateinit var mockContext: PluginContext

    @Before
    fun setup() {
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        val mockServiceRegistry = mockk<ServiceRegistry>(relaxed = true)
        mockContext = mockk {
            every { logger } returns mockLogger
            every { services } returns mockServiceRegistry
        }

        plugin = AiCodeHelperPlugin()
        plugin.initialize(mockContext)
        plugin.activate()
    }

    @Test
    fun testExplainCodeMenuItemExists() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns "val x = 42"
        }

        val items = plugin.getContextMenuItems(menuContext)

        assertTrue("Should have menu items when text selected", items.isNotEmpty())
        val explainItem = items.find { it.title == "Explain Code" }
        assertNotNull("Should have 'Explain Code' menu item", explainItem)
        assertTrue("Explain Code should be enabled", explainItem!!.isEnabled)
    }

    @Test
    fun testGenerateCodeMenuItemExists() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns "create function"
        }

        val items = plugin.getContextMenuItems(menuContext)

        val generateItem = items.find { it.title == "Generate Code" }
        assertNotNull("Should have 'Generate Code' menu item", generateItem)
        assertTrue("Generate Code should be enabled", generateItem!!.isEnabled)
    }

    @Test
    fun testNoMenuItemsWhenNoSelection() {
        val menuContext = mockk<ContextMenuContext> {
            every { selectedText } returns null
        }

        val items = plugin.getContextMenuItems(menuContext)

        assertTrue("Should have no menu items when no text selected", items.isEmpty())
    }
}
