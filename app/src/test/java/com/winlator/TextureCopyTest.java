package com.winlator;

import static org.junit.Assert.assertEquals;

import android.opengl.GLES20;

import com.winlator.renderer.Texture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// The VirGL present path: copyFromReadBuffer(w, h, sourceId) gives the drawable's texture new storage only for a new
// source or size and copies into that storage otherwise. Gladio's copyFromReadBuffer(w, h) still reallocates every time.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = TextureCopyTest.RecordingGLES20.class)
public class TextureCopyTest {
    @Implements(GLES20.class)
    public static class RecordingGLES20 {
        static final List<String> calls = new ArrayList<>();
        static int nextError = GLES20.GL_NO_ERROR;
        static boolean rejectSubCopy = false;
        private static int lastTextureId = 0;

        @Implementation
        protected static void glGenTextures(int n, int[] textures, int offset) {
            for (int i = 0; i < n; i++) textures[offset + i] = ++lastTextureId;
        }

        @Implementation
        protected static void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
            calls.add("image " + width + "x" + height);
        }

        @Implementation
        protected static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
            calls.add("sub " + width + "x" + height);
            if (rejectSubCopy) nextError = GLES20.GL_INVALID_OPERATION;
        }

        @Implementation
        protected static int glGetError() {
            int error = nextError;
            nextError = GLES20.GL_NO_ERROR;
            return error;
        }
    }

    private static void assertCalls(String... expected) {
        assertEquals(Arrays.asList(expected), RecordingGLES20.calls);
        RecordingGLES20.calls.clear();
    }

    @Before
    public void reset() {
        RecordingGLES20.calls.clear();
        RecordingGLES20.nextError = GLES20.GL_NO_ERROR;
        RecordingGLES20.rejectSubCopy = false;
    }

    @Test
    public void sameSourceCopiesIntoExistingStorage() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)800, (short)600, 1);
        texture.copyFromReadBuffer((short)800, (short)600, 1);
        texture.copyFromReadBuffer((short)800, (short)600, 1);
        assertCalls("image 800x600", "sub 800x600", "sub 800x600");
    }

    @Test
    public void newSourceOrSizeReallocates() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)800, (short)600, 1);
        texture.copyFromReadBuffer((short)800, (short)600, 2);
        texture.copyFromReadBuffer((short)700, (short)540, 2);
        texture.copyFromReadBuffer((short)700, (short)540, 2);
        assertCalls("image 800x600", "image 800x600", "image 700x540", "sub 700x540");
    }

    @Test
    public void rejectedCopyFallsBackToReallocating() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)640, (short)480, 7);
        RecordingGLES20.rejectSubCopy = true;
        texture.copyFromReadBuffer((short)640, (short)480, 7);
        RecordingGLES20.rejectSubCopy = false;
        texture.copyFromReadBuffer((short)640, (short)480, 7);
        assertCalls("image 640x480", "sub 640x480", "image 640x480", "sub 640x480");
    }

    @Test
    public void errorLeftByTheHostDoesNotReallocate() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)640, (short)480, 8);
        // an error from an earlier native GL call in the same context, never read there
        RecordingGLES20.nextError = GLES20.GL_INVALID_ENUM;
        texture.copyFromReadBuffer((short)640, (short)480, 8);
        assertCalls("image 640x480", "sub 640x480");
    }

    @Test
    public void destroyedTextureReallocates() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)800, (short)600, 3);
        texture.destroy();
        texture.copyFromReadBuffer((short)800, (short)600, 3);
        texture.copyFromReadBuffer((short)800, (short)600, 3);
        assertCalls("image 800x600", "image 800x600", "sub 800x600");
    }

    @Test
    public void gladioCopyStillReallocatesEveryTime() {
        Texture texture = new Texture(null);
        texture.copyFromReadBuffer((short)800, (short)600);
        texture.copyFromReadBuffer((short)800, (short)600);
        // storage from Gladio's copy is not taken for a VirGL source
        texture.copyFromReadBuffer((short)800, (short)600, 4);
        texture.copyFromReadBuffer((short)800, (short)600);
        texture.copyFromReadBuffer((short)800, (short)600, 4);
        assertCalls("image 800x600", "image 800x600", "image 800x600", "image 800x600", "image 800x600");
    }
}
