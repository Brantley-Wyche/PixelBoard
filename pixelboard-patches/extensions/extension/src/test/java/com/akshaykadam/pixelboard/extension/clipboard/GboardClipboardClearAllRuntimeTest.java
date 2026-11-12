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
    public void ignoresMissingKeyboard() {
        GboardClipboardClearAllRuntime.onClearAllLongPress(null, null);
    }
}
