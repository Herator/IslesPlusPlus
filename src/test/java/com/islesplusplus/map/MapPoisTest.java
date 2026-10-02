package com.islesplusplus.map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapPoisTest {
    private static MapPois.Poi parse(String json) { return MapPois.parse(JsonParser.parseString(json)); }

    @Test
    void testParse() {
        MapPois.Poi p = parse("{\"group\": \"mining\", \"type\": \"Coal\", \"x\": 1, \"y\": 2, \"z\": -3}");
        assertEquals(new MapPois.Poi(MapPois.Group.MINING, "Coal", 1, 2, -3), p);
        MapPois.Poi w = parse("{\"group\": \"WAYSTONES\", \"type\": \"Waystone\", \"name\": \"Harbor\", \"icon\": \"waystone_origin\", \"x\": 0, \"y\": 0, \"z\": 0}");
        assertEquals("Harbor", w.name());
        assertEquals("waystone_origin", w.icon());
    }

    @Test
    void testProfessionToggle() {
        reset();
        MapPois.Poi coal = new MapPois.Poi(MapPois.Group.MINING, "Coal Deposit", 0, 0, 0);
        MapPois.Poi iron = new MapPois.Poi(MapPois.Group.MINING, "Iron", 0, 0, 0);
        MapPois.Poi waystone = new MapPois.Poi(MapPois.Group.WAYSTONES, "Waystone", 0, 0, 0);
        assertFalse(MapPois.shown(MapPois.Group.MINING));  // professions: off until switched on
        assertFalse(MapPois.shown(coal));
        assertTrue(MapPois.shown(waystone));               // everything else: on until switched off
        MapPois.toggle(MapPois.Group.MINING, null);        // Mining on: all its resources show
        assertTrue(MapPois.shown(coal));
        assertTrue(MapPois.shown(iron));
        MapPois.toggle(MapPois.Group.MINING, "Coal Deposit");   // one resource off
        assertFalse(MapPois.shown(coal));
        assertTrue(MapPois.shown(iron));
        reset();
    }

    private static void reset() {
        MapPois.split("", MapPois.hidden);
        MapPois.split("", MapPois.selected);
    }

    @Test
    void clusterCell() {
        // 5 blocks apart is 1.25 px zoomed out (one cluster) and 40 px zoomed in (split up)
        assertEquals(MapPois.cell(100.5, 100.5, 0.25f), MapPois.cell(105.5, 100.5, 0.25f));
        assertNotEquals(MapPois.cell(100.5, 100.5, 8f), MapPois.cell(105.5, 100.5, 8f));
    }

    @Test
    void clusterTooltip() {
        java.util.List<MapPois.Poi> nodes = java.util.List.of(
            new MapPois.Poi(MapPois.Group.MINING, "Iron Ore Deposit", 0, 0, 0),
            new MapPois.Poi(MapPois.Group.WOODCUTTING, "Birch Tree", 0, 0, 0),
            new MapPois.Poi(MapPois.Group.MINING, "Coal Deposit", 0, 0, 0),
            new MapPois.Poi(MapPois.Group.MINING, "Iron Ore Deposit", 0, 0, 0));
        assertEquals(java.util.List.of(
                "3x Mining Node", " 2x Iron ore deposit", " 1x Coal deposit", "",
                "1x Foraging Node", " 1x Birch tree", "",
                "Click to navigate!"),
            MapPois.nodeTooltip(nodes).stream().map(net.minecraft.text.Text::getString).toList());
    }

    @Test
    void clusterIcon() {
        MapPois.Poi coal = new MapPois.Poi(MapPois.Group.MINING, "Coal Deposit", 0, 0, 0);
        MapPois.Poi iron = new MapPois.Poi(MapPois.Group.MINING, "Iron Ore Deposit", 0, 0, 0);
        assertEquals("coal_deposit", MapPois.clusterIcon(java.util.List.of(coal, coal)).get(0));
        assertEquals(java.util.List.of("mining"), MapPois.clusterIcon(java.util.List.of(coal, iron)));
        MapPois.Poi oak = new MapPois.Poi(MapPois.Group.WOODCUTTING, "Oak Tree", 0, 0, 0);
        assertEquals(java.util.List.of(MapPois.PROFESSIONS), MapPois.clusterIcon(java.util.List.of(coal, oak)));
    }

    @Test
    void clusterSize() {
        assertEquals(8, MapPois.clusterSize(1));
        assertEquals(10, MapPois.clusterSize(2));
        assertEquals(10, MapPois.clusterSize(3));
        assertEquals(12, MapPois.clusterSize(4));
        assertEquals(16, MapPois.clusterSize(16));
        assertEquals(16, MapPois.clusterSize(500));
    }

    @Test
    void packIcons() {
        // every node the server lists, long and short names (except the beach spot and wishing well, which use the Fishing icon)
        for (String type : java.util.List.of(
                "Coal", "Copper", "Iron", "Tin", "Salt", "Silver", "Nickel", "Rhodonite", "Rune Essence",
                "Coal Deposit", "Iron Ore Deposit", "Tin Ore", "Copper Ore",
                "Oak", "Birch", "Fungus", "Ash", "Palm", "Whisper", "Oak Tree", "Whisper Tree",
                "Wheat", "Carrot", "Tomato", "Cabbage", "Garlic", "Sugarcane", "Corn", "Wheat Field", "Garlic Field",
                "Basic", "River", "Plains", "Pond", "Warm Pond", "Warm Beach", "Pond Fishing Spot",
                "Smithing", "Fletching", "Runecrafting", "Artisan", "Artisan Accessories", "Artisan Tools", "Cooking", "Cooking Station",
                "Alchemy", "Augmenting", "Bank", "Merchant", "Auction House", "Wandering Zookeeper")) {
            assertTrue(MapPois.typeIcons(type).stream().anyMatch(MapPois.PACK_ICONS::containsKey),
                "no icon for " + type);
        }
    }

    @Test
    void testLowercaseTypes() {
        assertEquals("coal deposit", new MapPois.Poi(MapPois.Group.MINING, "  Coal DEPOSIT ", 0, 0, 0).type());
        assertEquals(new MapPois.Poi(MapPois.Group.MINING, "coal", 0, 0, 0).type(), new MapPois.Poi(MapPois.Group.MINING, "COAL", 0, 0, 0).type());
        assertEquals("Iron ore deposit", MapPois.title("iron ore deposit"));
        assertEquals("McKay", MapPois.title("McKay"));
        reset();
        MapPois.toggle(MapPois.Group.MINING, null);
        MapPois.toggle(MapPois.Group.MINING, "Coal");            // switched off with a capital...
        assertFalse(MapPois.shown(new MapPois.Poi(MapPois.Group.MINING, "coal", 0, 0, 0)));   // ...still matches
        reset();
    }

    @Test
    void shippedPois() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources/assets/islesplusplus");
        var json = JsonParser.parseString(java.nio.file.Files.readString(dir.resolve("map/pois.json")));
        for (var el : json.getAsJsonObject().getAsJsonArray("pois")) {
            MapPois.Poi p = MapPois.parse(el);
            assertNotNull(p, "unreadable point " + el);
            if (p.icon() != null) {
                assertTrue(MapPois.PACK_ICONS.containsKey(MapPois.slug(p.icon())), "no icon for " + p.name());
            }
        }
    }

    @Test
    void stations() {
        reset();
        MapPois.Poi smithing = new MapPois.Poi(MapPois.Group.STATIONS, "Smithing", 0, 0, 0);
        MapPois.Poi cooking = new MapPois.Poi(MapPois.Group.STATIONS, "Cooking", 0, 0, 0);
        assertTrue(MapPois.shown(smithing));
        MapPois.toggle(MapPois.Group.STATIONS, null);
        assertFalse(MapPois.shown(smithing));
        assertFalse(MapPois.shown(cooking));
        reset();
    }

    @Test
    void testClassify() {
        MapPois.Poi w = MapPois.classify("Harbor Waystone\nClick to open the Menu!", 1, 2, 3);
        assertEquals(MapPois.Group.WAYSTONES, w.group());
        assertEquals("Harbor Waystone", w.name());
        MapPois.Poi s = MapPois.classify("Smithing Station\nRight click to craft", 0, 0, 0);
        assertEquals(MapPois.Group.STATIONS, s.group());
        assertEquals("smithing", s.type());
        assertEquals("artisan tools", MapPois.classify("Artisan Station - Tools", 0, 0, 0).type());
        assertNull(MapPois.classify("Welcome to the Isles!", 0, 0, 0));
        assertEquals("merchant", MapPois.classify("Merchant", 0, 0, 0).type());   // the name over an NPC, as seen in game
        assertEquals("bank", MapPois.classify("Bank Manager", 0, 0, 0).type());
        assertNull(MapPois.classify("Riverbank", 0, 0, 0));   // whole words only
        assertNull(MapPois.classify("CLICK ME", 0, 0, 0));
        assertNull(MapPois.classify("WakiQuacki's Worm\n1★ LVL 2", 0, 0, 0));   // a pet's name tag
    }

    @Test
    void classify_unlistedNode() {
        // "Pond Fishing Spot" is a known name inside it, but the label's own line wins
        MapPois.Poi p = MapPois.classify("[Rich]\n12x Swamp Pond Fishing Spot\n3 Fishing Power", 0, 0, 0);
        assertEquals(MapPois.Group.FISHING, p.group());
        assertEquals("swamp pond fishing spot", p.type());
        assertEquals("warm beach fishing spot", MapPois.classify("[Rich] 8x Warm Beach Fishing Spot\n3 Fishing Power", 0, 0, 0).type());
        assertEquals("swamp pond fishing spot", MapPois.canonical("swamp pond fishing spot",
            java.util.List.of("Pond Fishing Spot", "Warm Pond Fishing Spot")));   // and recording keeps it
    }


    @Test
    void testNpcs() {
        java.util.UUID real = java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");   // version 4
        java.util.UUID npc = java.util.UUID.fromString("069a79f4-44e9-2726-a5be-fca90e38aaf5");    // version 2
        assertTrue(MapPois.isRealPlayer(real, "CreatorWodash"));
        assertFalse(MapPois.isRealPlayer(npc, "CreatorWodash"));
        assertFalse(MapPois.isRealPlayer(real, "Wandering Zookeeper"));
        assertFalse(MapPois.isRealPlayer(real, "\u00a76Merchant"));
    }

    @Test
    void testCanonical() {
        java.util.List<String> known = java.util.List.of("Oak Tree", "Tin Ore", "Copper Ore", "Coal", "Salt", "Iron Ore",
            "Wheat Field", "Wishing Well", "Pond Fishing Spot", "Warm Pond Fishing Spot");
        for (String s : java.util.List.of("iron", "Iron Ore", "iron ore deposit", "IRON ORE")) assertEquals("iron ore", MapPois.canonical(s, known));
        for (String s : java.util.List.of("coal", "coal ore", "Coal Deposit")) assertEquals("coal", MapPois.canonical(s, known));
        assertEquals("wishing well", MapPois.canonical("wishing well fishing spot", known));
        assertEquals("oak tree", MapPois.canonical("oak", known));
        assertEquals("pond fishing spot", MapPois.canonical("pond", known));
        assertEquals("warm pond fishing spot", MapPois.canonical("warm pond", known));
        assertEquals("mystery node", MapPois.canonical("Mystery Node", known));   // unknown: kept as written
    }

    @Test
    void testVisible() {
        assertEquals("Bank \\uE012", MapPois.visible("Bank \uE012"));
        assertEquals("\\u200B", MapPois.visible("\u200B"));
        assertEquals("Merchant", MapPois.visible("Merchant"));
    }

    @Test
    void parseBroken() {
        assertNull(parse("{\"group\": \"NOPE\", \"type\": \"x\", \"x\": 0, \"y\": 0, \"z\": 0}"));
        assertNull(parse("{\"group\": \"MINING\", \"x\": 0, \"y\": 0, \"z\": 0}"));
        assertNull(parse("{\"group\": \"PLAYERS\", \"type\": \"Steve\", \"x\": 0, \"y\": 0, \"z\": 0}"));
    }

    @Test
    void testSlug() {
        assertEquals("tin_ore", MapPois.slug("Tin Ore"));
        assertEquals("boss_beacon", MapPois.slug(" Boss  Beacon! "));
        assertEquals("pois", MapPois.slug("POIS"));
    }

    @Test
    void configRoundTrip() {
        reset();
        MapPois.toggle(MapPois.Group.PLAYERS, null);
        MapPois.toggle(MapPois.Group.MINING, null);
        MapPois.toggle(MapPois.Group.MINING, "Tin");
        String off = MapPois.join(MapPois.hidden), on = MapPois.join(MapPois.selected);
        assertEquals("MINING:tin|PLAYERS", off);
        assertEquals("MINING", on);
        reset();
        MapPois.split(off, MapPois.hidden);
        MapPois.split(on, MapPois.selected);
        assertFalse(MapPois.shown(MapPois.Group.MINING, "Tin"));
        assertTrue(MapPois.shown(MapPois.Group.MINING, "Iron"));
        assertFalse(MapPois.shown(MapPois.Group.PLAYERS));
        reset();
    }

    @Test
    void toggleResourceWhenOff() {
        reset();
        MapPois.only(MapPois.Group.MINING, "coal", java.util.List.of("tin ore", "coal", "iron ore"));
        assertTrue(MapPois.shown(MapPois.Group.MINING, "coal"));
        assertFalse(MapPois.shown(MapPois.Group.MINING, "tin ore"));
        assertFalse(MapPois.shown(MapPois.Group.MINING, "iron ore"));
        MapPois.toggle(MapPois.Group.MISC, null);   // Miscellaneous off
        MapPois.only(MapPois.Group.MISC, "plushies", java.util.List.of("egg nests", "plushies"));
        assertTrue(MapPois.shown(MapPois.Group.MISC, "plushies"));
        assertFalse(MapPois.shown(MapPois.Group.MISC, "egg nests"));
        reset();
    }

    @Test
    void resourceOrder() {
        assertEquals(java.util.List.of("tin ore", "copper ore", "coal", "iron ore", "rune essence"),
            MapPois.ordered(MapPois.Group.MINING, java.util.Set.of("rune essence", "coal", "iron ore", "tin ore", "copper ore")));
        assertEquals(java.util.List.of("beach fishing spot", "pond fishing spot", "warm beach fishing spot", "wishing well"),
            MapPois.ordered(MapPois.Group.FISHING, java.util.Set.of("wishing well", "warm beach fishing spot", "pond fishing spot", "beach fishing spot")));
        assertEquals(java.util.List.of("oak tree", "palm tree", "whisper tree"),   // not in the order: last
            MapPois.ordered(MapPois.Group.WOODCUTTING, java.util.Set.of("whisper tree", "palm tree", "oak tree")));
    }

    @Test
    void testPlushies() {
        java.util.List<com.islesplus.features.plushiefinder.PlushieEntry> all = java.util.List.of(
            new com.islesplus.features.plushiefinder.PlushieEntry(1, 10.5, 64, -3.2, null, null, null),
            new com.islesplus.features.plushiefinder.PlushieEntry(2, 0, 0, 0, null, null, null),
            new com.islesplus.features.plushiefinder.PlushieEntry(3, 0, 0, 0, 5.0, 70.0, -8.5));
        var found = MapPois.plushies(all, n -> n == 2, false);   // #2 found
        var left = java.util.List.copyOf(found.keySet());
        assertEquals(java.util.List.of("Plushie #1", "Plushie #3"), left.stream().map(MapPois.Poi::name).toList());
        assertEquals(-4, left.get(0).z());
        // the waypoint goes to the plushie itself, or to its entrance when it has one
        assertArrayEquals(new int[]{10, 64, -4}, found.get(left.get(0)));
        assertArrayEquals(new int[]{5, 70, -9}, found.get(left.get(1)));
        // the finder's "hide first plushie" leaves #1 off too
        assertEquals(1, MapPois.plushies(all, n -> n == 2, true).size());
        // shown under Miscellaneous, with its own toggle
        reset();
        assertTrue(MapPois.shown(left.get(0)));
        MapPois.toggle(MapPois.Group.MISC, MapPois.PLUSHIE);
        assertFalse(MapPois.shown(left.get(0)));
        reset();
    }
}
