package com.islesplusplus.healthborder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthBorderTest {
    @Test
    void testIntensity() {
        assertEquals(0f, HealthBorder.intensity(20f, 20f));
        assertEquals(0.5f, HealthBorder.intensity(10f, 20f));
        assertEquals(1f, HealthBorder.intensity(0f, 20f));
        assertEquals(0f, HealthBorder.intensity(5f, 0f));
    }

    @Test
    void testRingAlpha() {
        int edge = HealthBorder.ringAlpha(0, 40, 1f, 1f);
        assertTrue(edge < 255, "edge must stay see-through");
        assertTrue(HealthBorder.ringAlpha(20, 40, 1f, 1f) < edge);
        assertEquals(0, HealthBorder.ringAlpha(0, 40, 0f, 1f));
    }
}
