package com.islesplusplus.map;

import net.minecraft.block.MapColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IslesMapTest {
    @Test
    void tileOf_negative() {
        assertEquals(0, IslesMap.tileOf(0));
        assertEquals(0, IslesMap.tileOf(511));
        assertEquals(1, IslesMap.tileOf(512));
        assertEquals(-1, IslesMap.tileOf(-1));
        assertEquals(-1, IslesMap.tileOf(-512));
        assertEquals(-2, IslesMap.tileOf(-513));
    }

    @Test
    void parseTileName() {
        assertArrayEquals(new int[]{3, -2}, IslesMap.parseTileName("3_-2"));
        assertNull(IslesMap.parseTileName("3"));
        assertNull(IslesMap.parseTileName("a_b"));
    }

    @Test
    void waypointOnMap() {
        assertArrayEquals(new int[]{10, -20, 1}, IslesMap.edgePoint(10, -20, 46, 46));
    }

    @Test
    void heightEncoding() {
        for (int y : new int[]{-64, -1, 0, 63, 120, 255, 256, 319}) {
            int argb = IslesMap.encodeHeight(y);
            assertEquals(0xFF, argb >>> 24, "opaque");
            assertEquals(y, IslesMap.decodeHeight(argb));
        }
        assertEquals(IslesMap.NO_HEIGHT, IslesMap.decodeHeight(0));   // see-through: not explored
    }

    @Test
    void parseCoords() {
        assertArrayEquals(new int[]{100, 0, 200}, IslesMap.parseCoords("100, 0, 200", 64));
        assertArrayEquals(new int[]{100, 0, 200}, IslesMap.parseCoords("100 0 200", 64));
        assertArrayEquals(new int[]{100, 0, 200}, IslesMap.parseCoords("100,0,200,", 64));
        assertArrayEquals(new int[]{-35, 64, 590}, IslesMap.parseCoords("-35, 590", 64));   // no y: your height
        assertNull(IslesMap.parseCoords("100", 64));
        assertNull(IslesMap.parseCoords("1, 2, 3, 4", 64));
        assertNull(IslesMap.parseCoords("100, up, 200", 64));
    }

    @Test
    void testNear() {
        assertTrue(IslesMap.near(100.5, 50.5, 100, 50, 5));
        assertTrue(IslesMap.near(100, 50, 105, 45, 5));      // corner of the pick box
        assertFalse(IslesMap.near(100, 50, 106, 50, 5));
        assertFalse(IslesMap.near(100, 50, 100, 44, 5));
    }

    @Test
    void edgePoint() {
        assertArrayEquals(new int[]{46, 0, 0}, IslesMap.edgePoint(500, 0, 46, 46));      // due east
        assertArrayEquals(new int[]{0, -46, 0}, IslesMap.edgePoint(0, -500, 46, 46));    // due north
        assertArrayEquals(new int[]{46, 46, 0}, IslesMap.edgePoint(300, 300, 46, 46));   // corner
        assertArrayEquals(new int[]{-46, 23, 0}, IslesMap.edgePoint(-200, 100, 46, 46)); // along the line
    }

    @Test
    void testShade() {
        assertEquals(MapColor.Brightness.HIGH, IslesMap.shade(70, 64));
        assertEquals(MapColor.Brightness.LOW, IslesMap.shade(60, 64));
        assertEquals(MapColor.Brightness.NORMAL, IslesMap.shade(64, 64));
    }
}
