package com.islesplusplus.itempickup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemPickupLogTest {
    @Test
    void testMergeAndExpire() {
        ItemPickupLog.reset();
        ItemPickupLog.add("Whisperleaf", null, 0, 2, 0);
        ItemPickupLog.add("Driftstone", null, 0, 1, 100);
        ItemPickupLog.add("Whisperleaf", null, 0, 3, 200);
        assertEquals(2, ItemPickupLog.entries.size());
        assertEquals("Whisperleaf", ItemPickupLog.entries.get(0).name);
        assertEquals(5, ItemPickupLog.entries.get(0).amount);

        assertEquals(1f, ItemPickupLog.alpha(ItemPickupLog.entries.get(0), 200));
        assertEquals(1f, ItemPickupLog.alpha(ItemPickupLog.entries.get(0), 200 + ItemPickupLog.HOLD_MS));
        assertEquals(0.5f, ItemPickupLog.alpha(ItemPickupLog.entries.get(0), 200 + ItemPickupLog.HOLD_MS + ItemPickupLog.FADE_MS / 2));

        ItemPickupLog.prune(100 + ItemPickupLog.SHOW_MS + 1);   // Driftstone is past its time
        assertEquals(1, ItemPickupLog.entries.size());

        for (int i = 0; i < 10; i++) ItemPickupLog.add("Item " + i, null, 0, 1, 300);
        assertEquals(ItemPickupLog.MAX_ROWS, ItemPickupLog.entries.size());
        assertEquals("Item 9", ItemPickupLog.entries.get(0).name);
    }
}
