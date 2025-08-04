package com.example.sampleplugin.services

import com.itsaky.androidide.plugins.PluginContext

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