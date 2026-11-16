package com.akshaykadam.pixelboard.extension.clipboard;

import org.junit.Assert;
import org.junit.Test;

public class GboardClipboardClearAllRuntimeTest {

    @Test
    public void confirmMessageCountsUnpinnedClips() {
        Assert.assertEquals("This deletes 1 unpinned clip. Pinned clips are kept.",
                GboardClipboardClearAllRuntime.confirmMessage(1));
        Assert.assertEquals("This deletes 12 unpinned clips. Pinned clips are kept.",
                GboardClipboardClearAllRuntime.confirmMessage(12));
    }

    @Test
    public void helperNamesMatchThePatch() {
        // GboardClipboardClearAllPatch adds these public methods to ClipboardKeyboard.
        Assert.assertEquals("pixelboardCountUnpinnedClips", GboardClipboardClearAllRuntime.COUNT_METHOD);
        Assert.assertEquals("pixelboardClearUnpinnedClips", GboardClipboardClearAllRuntime.CLEAR_METHOD);
    }

    @Test
    public void modeColorIgnoresTransparentCornersAndText() {
        int card = 0xFF3A2858;
        int text = 0xFFFFFFFF;
        int[] pixels = {0x00000000, 0x00000000, 0x10FFFFFF, card, card, card, text, card, text};
        Assert.assertEquals(Integer.valueOf(card), GboardClipboardClearAllRuntime.modeOpaqueColor(pixels));
        Assert.assertNull(GboardClipboardClearAllRuntime.modeOpaqueColor(new int[] {0, 0x80FFFFFF}));
    }

    @Test
    public void detectsDarkAndLightSurfaces() {
        Assert.assertTrue(GboardClipboardClearAllRuntime.isDark(0xFF241040));
        Assert.assertTrue(GboardClipboardClearAllRuntime.isDark(0xFF3A2858));
        Assert.assertFalse(GboardClipboardClearAllRuntime.isDark(0xFFF3EDF7));
        Assert.assertFalse(GboardClipboardClearAllRuntime.isDark(0xFFFFFFFF));
    }

    @Test
    public void withAlphaKeepsTheColor() {
        Assert.assertEquals(0xCC123456, GboardClipboardClearAllRuntime.withAlpha(0xFF123456, 0xCC));
    }

    @Test
    public void ignoresMissingKeyboard() {
        GboardClipboardClearAllRuntime.onClearAllLongPress(null, null);
    }
}
