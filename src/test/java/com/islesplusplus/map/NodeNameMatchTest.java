package com.islesplusplus.map;

import com.islesplus.features.nodealertmanager.NodeTracker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeNameMatchTest {
    // the plain name comes first, like in the server's list
    private static final List<String> NAMES = List.of(
        "Beach Fishing Spot", "Pond Fishing Spot", "Warm Beach Fishing Spot", "Warm Pond Fishing Spot", "Cold Pond Fishing Spot");

    private static String match(String label) {
        return MapPois.matchKnownNodeName(NodeTracker.normalizeNodeText(label), NAMES);
    }

    @Test void plainSpot() {
        assertEquals("Beach Fishing Spot", match("12x Beach Fishing Spot\n3 Fishing Power"));
    }

    @Test void testWarmSpot() {
        assertEquals("Warm Beach Fishing Spot", match("12x Warm Beach Fishing Spot\n3 Fishing Power"));
        assertEquals("Warm Pond Fishing Spot", match("[Rich]\n8x Warm Pond Fishing Spot"));
    }

    @Test void coldSpotToo() {
        assertEquals("Cold Pond Fishing Spot", match("5x Cold Pond Fishing Spot"));
    }

    @Test void unknownLabel() {
        assertNull(match("Merchant"));
    }
}
