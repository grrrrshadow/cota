package com.winlator.xserver.extensions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GLXContextVersionTest {
    @Test
    public void acceptsEveryVersionUpTo33() {
        assertTrue(GLXExtension.ContextVersion.isSupported(1, 0));
        assertTrue(GLXExtension.ContextVersion.isSupported(1, 4));
        assertTrue(GLXExtension.ContextVersion.isSupported(1, 5));
        assertTrue(GLXExtension.ContextVersion.isSupported(2, 0));
        assertTrue(GLXExtension.ContextVersion.isSupported(2, 1));
        assertTrue(GLXExtension.ContextVersion.isSupported(3, 0));
        assertTrue(GLXExtension.ContextVersion.isSupported(3, 2));
        assertTrue(GLXExtension.ContextVersion.isSupported(3, 3));
    }

    @Test
    public void rejectsVersionsAbove33() {
        // OpenTTD asks for 4.5 core first, then falls back to 3.2
        assertFalse(GLXExtension.ContextVersion.isSupported(3, 4));
        assertFalse(GLXExtension.ContextVersion.isSupported(4, 0));
        assertFalse(GLXExtension.ContextVersion.isSupported(4, 5));
        assertFalse(GLXExtension.ContextVersion.isSupported(4, 6));
    }

    @Test
    public void rejectsInvalidVersions() {
        assertFalse(GLXExtension.ContextVersion.isSupported(0, 0));
        assertFalse(GLXExtension.ContextVersion.isSupported(-1, 0));
    }
}
