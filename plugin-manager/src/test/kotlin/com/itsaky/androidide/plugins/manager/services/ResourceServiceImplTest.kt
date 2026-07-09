package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeResourceService
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ResourceServiceImplTest {

    @Test
    fun testGetStringSuccess() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ResourceServiceImpl(projectRoot)

        val result = service.getString("app_name")

        assertTrue(result.success)
        assertNotNull(result.data)
    }

    @Test
    fun testGetDrawableSuccess() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ResourceServiceImpl(projectRoot)

        val result = service.getDrawable("ic_launcher")

        assertTrue(result.success)
        assertNotNull(result.data)
    }

    @Test
    fun testGetColorSuccess() {
        val projectRoot = File.createTempFile("project", "").parentFile!!
        val service = ResourceServiceImpl(projectRoot)

        val result = service.getColor("primary_color")

        assertTrue(result.success)
        assertNotNull(result.data)
    }
}
