package com.itsaky.androidide.plugins.services;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdeResourceServiceTest {

    @Test
    public void testResourceOperationResultSuccess() {
        IdeResourceService.ResourceOperationResult result =
            IdeResourceService.ResourceOperationResult.success("Resource added");

        assertTrue(result.success);
        assertEquals("Resource added", result.message);
        assertNull(result.error);
    }

    @Test
    public void testResourceOperationResultFailure() {
        IdeResourceService.ResourceOperationResult result =
            IdeResourceService.ResourceOperationResult.failure("Resource exists");

        assertFalse(result.success);
        assertEquals("Operation failed", result.message);
        assertEquals("Resource exists", result.error);
    }
}
