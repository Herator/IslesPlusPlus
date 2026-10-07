package com.islesplusplus.map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SavedWaypointsTest {
    @Test
    void testSaveLoad() {
        SavedWaypoints.Waypoint a = new SavedWaypoints.Waypoint("Home", 100, 64, -200);
        a.color = 0x12AB34;
        a.inWorld = false;
        a.beam = false;
        SavedWaypoints.Waypoint b = new SavedWaypoints.Waypoint("Mine", -3, -10, 7);
        List<SavedWaypoints.Waypoint> back = SavedWaypoints.fromJson(JsonParser.parseString(SavedWaypoints.toJson(List.of(a, b)).toString()));
        assertEquals(2, back.size());
        SavedWaypoints.Waypoint r = back.get(0);
        assertEquals("Home", r.name);
        assertArrayEquals(new int[]{100, 64, -200}, new int[]{r.x, r.y, r.z});
        assertEquals(0x12AB34, r.color);
        assertFalse(r.inWorld);
        assertFalse(r.beam);
        assertTrue(back.get(1).inWorld && back.get(1).beam);
    }

    @Test
    void testDefaults() {
        var w = SavedWaypoints.fromJson(JsonParser.parseString("{\"waypoints\": [{\"x\": 1, \"y\": 2, \"z\": 3}]}")).get(0);
        assertEquals(SavedWaypoints.DEFAULT_COLOR, w.color);
        assertTrue(w.inWorld && w.beam);
    }

    @Test
    void parseFields() {
        assertArrayEquals(new int[]{5, -64, 300}, WaypointsScreen.parse(new String[]{"5", " -64", "300"}));
        assertNull(WaypointsScreen.parse(new String[]{"5", "", "300"}));
        assertNull(WaypointsScreen.parse(new String[]{"5", "-", "300"}));
    }

    @Test
    void notInPoisFile() {
        assertNull(MapPois.parse(JsonParser.parseString("{\"group\": \"WAYPOINTS\", \"type\": \"x\", \"x\": 1, \"y\": 2, \"z\": 3}")));
    }
}
