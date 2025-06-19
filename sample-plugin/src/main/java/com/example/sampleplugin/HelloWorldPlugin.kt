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

package com.example.sampleplugin

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.UIExtension

class HelloWorldPlugin : IPlugin, UIExtension {
    
    override val metadata = PluginMetadata(
        id = "com.example.helloworld",
        name = "Hello World Plugin",
        version = "1.0.0",
        description = "A simple hello world plugin that demonstrates COGO plugin capabilities",
        author = "COGO Team",
        minIdeVersion = "2.1.0",
        permissions = listOf("IDE_SETTINGS", "FILESYSTEM_READ")
    )
    
    private lateinit var context: PluginContext
    
    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        context.logger.info("HelloWorldPlugin: Initializing plugin")
        return true
    }
    
    override fun activate(): Boolean {
        context.logger.info("HelloWorldPlugin: Activating plugin")
        
        // Register a simple service
        context.services.register(HelloService::class.java, HelloServiceImpl(context))
        
        return true
    }
    
    override fun deactivate(): Boolean {
        context.logger.info("HelloWorldPlugin: Deactivating plugin")
        return true
    }
    
    override fun dispose() {
        context.logger.info("HelloWorldPlugin: Disposing plugin")
    }
    
    // UIExtension implementation
    override fun contributeToMainMenu(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "hello_world_menu",
                title = "Hello World",
                action = {
                    context.logger.info("HelloWorldPlugin: Menu item clicked!")
                }
            )
        )
    }
    
    override fun contributeToContextMenu(context: ContextMenuContext): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "hello_world_context",
                title = "Say Hello",
                action = {
                    this.context.logger.info("HelloWorldPlugin: Context menu item clicked!")
                }
            )
        )
    }
}

interface HelloService {
    fun sayHello(): String
    fun getPluginInfo(): String
}

class HelloServiceImpl(private val context: PluginContext) : HelloService {
    override fun sayHello(): String {
        return "Hello from HelloWorld Plugin!"
    }
    
    override fun getPluginInfo(): String {
        return "Plugin ID: ${context.pluginId}, Running in AndroidIDE"
    }
}