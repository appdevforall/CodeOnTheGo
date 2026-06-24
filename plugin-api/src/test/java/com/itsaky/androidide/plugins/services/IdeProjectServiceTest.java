package com.itsaky.androidide.plugins.services;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdeProjectServiceTest {

    @Test
    public void testProjectOperationResultSuccess() {
        IdeProjectService.ProjectOperationResult result =
            IdeProjectService.ProjectOperationResult.success("Sync started", "data");

        assertTrue(result.success);
        assertEquals("Sync started", result.message);
        assertEquals("data", result.data);
        assertNull(result.error);
    }

    @Test
    public void testProjectOperationResultFailure() {
        IdeProjectService.ProjectOperationResult result =
            IdeProjectService.ProjectOperationResult.failure("Build failed");

        assertFalse(result.success);
        assertEquals("Operation failed", result.message);
        assertNull(result.data);
        assertEquals("Build failed", result.error);
    }
}
