package com.itsaky.androidide.plugins.services;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdeFileServiceTest {

    @Test
    public void testFileOperationResultSuccess() {
        IdeFileService.FileOperationResult result =
            IdeFileService.FileOperationResult.success("File read", "content");

        assertTrue(result.success);
        assertEquals("File read", result.message);
        assertEquals("content", result.data);
        assertNull(result.error);
    }

    @Test
    public void testFileOperationResultFailure() {
        IdeFileService.FileOperationResult result =
            IdeFileService.FileOperationResult.failure("File not found");

        assertFalse(result.success);
        assertEquals("Operation failed", result.message);
        assertNull(result.data);
        assertEquals("File not found", result.error);
    }
}
