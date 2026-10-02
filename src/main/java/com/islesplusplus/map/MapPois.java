package com.islesplusplus.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.islesplus.features.plushiefinder.PlushieEntry;
import com.islesplus.features.plushiefinder.PlushieFinder;
import com.islesplus.features.plushiefinder.PlushieRepository;
import com.islesplus.logging.IslesLog;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Premade points on the minimap and full map, kept in a points file (written by hand or with
 * {@code /poi record}), plus the players around you.
 *
 * <p>The file is {@code config/islesplusplus/map/pois.json} when it exists, else the one shipped in the
 * jar ({@code assets/islesplusplus/map/pois.json}); {@code /poi reload} reads it again. Each point:
 * <pre>{"group": "WOODCUTTING", "type": "Oak Tree", "x": 0, "y": 64, "z": 0}
 * {"group": "MINING", "type": "Coal Deposit", "x": 0, "y": 64, "z": 0}
 * {"group": "POIS", "type": "Boss Beacon", "name": "Frog Boss", "icon": "boss_frog", "x": 0, "y": 64, "z": 0}</pre>
 * The type is whatever the node calls itself (each profession gets a toggle per type). {@code name} (hover text)
 * defaults to it. Icon names are lowercase with _ for spaces, looked up in {@link #PACK_ICONS}.
 * It tries the point's {@code icon} first, then its type's ({@code coal_deposit}, {@code oak_tree}), then
 * its group's ({@code mining}), and falls back to a square in the group's colour. A cluster of several types
 * uses its profession's icon, and a cluster mixing professions shows all four profession icons
 * in a grid. Players show up as their own head.
 */
public final class MapPois {
    public enum Group {
        POIS("POIs", Theme.HUD_VOID, null),
        PLAYERS("Players", Theme.HUD_TEXT, null),
        WAYPOINTS("Saved Waypoints", Theme.HUD_RED, null),
        WAYSTONES("Waystones", Theme.HUD_VERDIGRIS, null),
        SHRINES("Shrines", 0xFFE0C068, null),
        STATIONS("Crafting Stations", Theme.HUD_GOLD, null),
        MISC("Miscellaneous", 0xFFB58AE0, null),
        // profession colours are the ones the game's own node tooltips use
        MINING("Mining", 0xFFF08A10, "Mining Node"),
        FARMING("Farming", 0xFFD9C23A, "Farming Node"),
        WOODCUTTING("Woodcutting", 0xFF7C9F35, "Foraging Node"),
        FISHING("Fishing", 0xFF4FA3E0, "Fishing Node");

        public final String label;
        public final int colour;
        // what the game calls it ("Foraging Node"), null if not a profession
        public final String node;
        final String icon = name().toLowerCase(Locale.ROOT);

        Group(String label, int colour, String node) {
            this.label = label;
            this.colour = colour;
            this.node = node;
        }

        public boolean profession() { return ordinal() >= MINING.ordinal(); }

        public boolean expands() { return profession() || this == MISC; }
    }

    // icon: overrides the type's icon, null = use the type's
    public record Poi(Group group, String type, String name, String icon, int x, int y, int z) {
        // lowercase so "Coal" and "coal" are one type / one toggle
        public Poi {
            type = type.trim().toLowerCase(Locale.ROOT);
        }

        public Poi(Group group, String type, int x, int y, int z) { this(group, type, type, null, x, y, z); }
    }

    // 16 chunks. on the full map they only show once you're zoomed in, otherwise it's covered in them
    static final int STATION_RANGE = 16 * 16;
    static final int ICON = 8;
    static final int PARTY_FRAME = Theme.HUD_LIME;

    // switched off in the Show panel: a group ("PLAYERS") or a resource ("MINING:coal")
    public static final Set<String> hidden = new HashSet<>();
    // professions switched on. they start off
    public static final Set<String> selected = new HashSet<>();

    private static final List<Poi> pois = new ArrayList<>();
    private static boolean loaded, unsaved;

    private MapPois() {}

    public static boolean shown(Group g) { return g.profession() ? selected.contains(g.name()) : !hidden.contains(g.name()); }

    public static boolean shown(Group g, String resource) { return shown(g) && !hidden.contains(key(g, resource)); }

    public static boolean shown(Poi p) {
        return p.group().expands() ? shown(p.group(), p.type()) : shown(p.group());
    }

    static String key(Group g, String type) { return g.name() + ":" + type.toLowerCase(Locale.ROOT); }

    // "iron ore deposit" -> "Iron ore deposit"
    public static String title(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // clicking a resource of a group that's off turns the group on with just that one
    public static void toggle(Group g, String resource) {
        if (resource != null && !shown(g)) {
            only(g, resource, resources(g));
            return;
        }
        Set<String> set = resource == null && g.profession() ? selected : hidden;
        String k = resource == null ? g.name() : key(g, resource);
        if (!set.remove(k)) set.add(k);
    }

    public static List<Poi> all() {
        ensureLoaded();
        return pois;
    }

    static void only(Group g, String resource, List<String> all) {
        if (g.profession()) selected.add(g.name()); else hidden.remove(g.name());
        for (String r : all) hidden.add(key(g, r));
        hidden.remove(key(g, resource));
    }

    public static List<String> resources(Group g) {
        Set<String> t = new HashSet<>();
        for (Poi p : all()) if (p.group() == g) t.add(p.type());
        if (g == Group.MISC) { t.add(PLUSHIE); t.add(EGG_NEST); }
        return ordered(g, t);
    }

    // by when you can gather them, matched on first words ("tin" = "tin ore")
    private static final Map<Group, List<String>> ORDER = Map.of(
        Group.MINING, List.of("tin", "copper", "coal", "iron", "salt", "silver", "nickel", "rhodonite", "rune essence"),
        Group.FARMING, List.of("wheat", "carrot", "tomato", "cabbage", "corn", "garlic", "coconut"),
        Group.WOODCUTTING, List.of("oak", "birch", "fungus", "ash", "palm", "tropical", "coconut"),
        Group.FISHING, List.of("basic", "river", "beach", "plains", "pond", "warm beach", "warm pond", "wishing well"));

    static List<String> ordered(Group g, java.util.Collection<String> types) {
        List<String> order = ORDER.getOrDefault(g, List.of());
        java.util.function.ToIntFunction<String> rank = t -> {
            for (int i = 0; i < order.size(); i++) if (t.equals(order.get(i)) || t.startsWith(order.get(i) + " ")) return i;
            return order.size();
        };
        return types.stream().sorted(java.util.Comparator.comparingInt(rank).thenComparing(t -> t)).toList();
    }

    static final String EGG_NEST = "egg nests";
    private static final int[][] EGG_NEST_AT = {
        {-356, 107, 15}, {-202, 89, 156}, {-441, 80, 359}, {-94, 120, 320}, {-328, 120, 818}, {-158, 119, 867},
        {271, 65, 811}, {409, 65, 821}, {652, 112, 943}, {407, 101, 1013}, {623, 119, 1035},
        {419, 88, 1662}, {552, 111, 1581}, {294, 61, 1626}, {670, 25, 1836}};
    // built in, not from the points file, so they show even with your own file
    static final List<Poi> EGG_NESTS = java.util.stream.IntStream.range(0, EGG_NEST_AT.length)
        .mapToObj(i -> new Poi(Group.MISC, EGG_NEST, "Egg Nest #" + (i + 1), "egg_nest", EGG_NEST_AT[i][0], EGG_NEST_AT[i][1], EGG_NEST_AT[i][2]))
        .toList();

    static final String PLUSHIE = "plushies";

    /** Plushies you haven't found yet, the same ones the Plushie Finder points at (so #1 is left out
     * when the finder hides it). These come live from the finder, never from the points file, so a
     * plushie drops off the map the moment you find it. Each one maps to where clicking it sends the
     * waypoint: its entrance when it has one (it's usually tucked away inside something), else itself. */
    static Map<Poi, int[]> plushies(List<PlushieEntry> all, java.util.function.IntPredicate owned, boolean hideFirst) {
        Map<Poi, int[]> out = new LinkedHashMap<>();
        for (PlushieEntry p : all) {
            if (owned.test(p.num) || p.num == 1 && hideFirst) continue;
            Poi poi = new Poi(Group.MISC, PLUSHIE, "Plushie #" + p.num, "plushie", block(p.xReal), block(p.yReal), block(p.zReal));
            out.put(poi, p.hasEntrance()
                ? new int[]{block(p.xEntrance), block(p.yEntrance), block(p.zEntrance)}
                : new int[]{poi.x(), poi.y(), poi.z()});
        }
        return out;
    }

    private static int block(double v) { return (int) Math.floor(v); }

    // ==============================
    // Recording (dev tool)
    // ==============================

    // dev tool, /poi record
    public static boolean recording = false;
    private static int recordTicks;
    // blocks, ignoring y
    private static final int SAME_WITHIN = 2;
    // {whole word in the npc's name, station type}
    private static final String[][] NPC_STATIONS = {
        {"merchant", "merchant"}, {"bank", "bank"}, {"auction", "auction house"}, {"zookeeper", "wandering zookeeper"}};

    // "45x Iron Ore Deposit" (maybe after a [Rich] tag), group 1 = name
    private static final java.util.regex.Pattern NODE_NAME = java.util.regex.Pattern.compile("(?m)^\\s*(?:\\[[^\\]]*\\]\\s*)*\\d+x\\s+(.+?)\\s*$");
    private static final java.util.regex.Pattern WAYSTONE = java.util.regex.Pattern.compile("^(.+?) Waystone\\b");

    /** The text of a floating label. That's a text display's text, an NPC's name (NPCs are player
     * entities that aren't real players, like the bank, auction house, zookeeper...), or a named
     * entity's name (an invisible armor stand), else null. */
    public static String labelText(net.minecraft.entity.Entity e) {
        try {
            if (e instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity td) return td.getText().getString();
            MinecraftClient client = MinecraftClient.getInstance();
            if (e instanceof AbstractClientPlayerEntity pl) return pl != client.player && !isRealPlayer(client, pl) ? pl.getName().getString() : null;
            if (e.hasCustomName() && e.getCustomName() != null) return e.getCustomName().getString();
        } catch (RuntimeException ignored) {
            // label mid-update, skip
        }
        return null;
    }

    /** The longest of {@code names} in the text, so a variant wins over the name inside it
     * ("Warm Beach Fishing Spot" over "Beach Fishing Spot", likewise a "Cold ..." one). */
    static String matchKnownNodeName(String normalizedText, List<String> names) {
        String best = null;
        for (String nodeName : names) {
            if (normalizedText.contains(nodeName.toLowerCase(Locale.ROOT))
                && (best == null || nodeName.length() > best.length())) {
                best = nodeName;
            }
        }
        return best;
    }

    static Poi classify(String text, int x, int y, int z) {
        String first = text.split("\n", 2)[0].trim();
        java.util.regex.Matcher w = WAYSTONE.matcher(first);
        if (w.find()) return new Poi(Group.WAYSTONES, "waystone", w.group(1).trim() + " Waystone", null, x, y, z);
        String normalized = com.islesplus.features.nodealertmanager.NodeTracker.normalizeNodeText(text);
        com.islesplus.entity.NodeSkill skill = com.islesplus.entity.NodeSkill.fromLabel(normalized);
        if (skill != null) {
            // the label's own "<amount>x <name>" line ("Rich / 45x Swamp Pond Fishing Spot / 4 Fishing Power"),
            // so a kind of node nobody has listed yet still gets its full name; record() then writes a known
            // one the server's way ("Iron Ore Deposit" is "iron ore"). A label without that line ("Depleted
            // Oak Tree") falls back to the longest known node name in it.
            java.util.regex.Matcher named = NODE_NAME.matcher(text);
            String node = named.find() ? named.group(1)
                : matchKnownNodeName(normalized, com.islesplus.features.nodealertmanager.NodeRepository.getNodeNames());
            Group g = Group.valueOf(skill.name());
            return new Poi(g, node != null ? node : first, x, y, z);
        }
        String lower = first.toLowerCase(Locale.ROOT);
        if (lower.contains(" station")) return new Poi(Group.STATIONS, lower.replace(" station", "").replace(" - ", " ").trim(), x, y, z);
        // NPCs: the name over one is really an invisible armor stand ("Merchant", with "CLICK ME" under it).
        // "Merchant" and "Bank Manager" have been checked in game. Whole words only, so "Riverbank" isn't a bank.
        List<String> words = List.of(lower.split("[^a-z]+"));
        for (String[] npc : NPC_STATIONS) if (words.contains(npc[0])) return new Poi(Group.STATIONS, npc[1], x, y, z);
        return null;
    }

    static boolean record(Poi p) {
        ensureLoaded();
        Poi c = canonical(p), o = sameNear(c);
        if (o == null) {
            add(c);
            return true;
        }
        // already there (say it was added by hand where you stood), so move it onto the label but keep its name and icon
        if (o.x() != c.x() || o.y() != c.y() || o.z() != c.z()) {
            pois.set(pois.indexOf(o), new Poi(o.group(), o.type(), o.name(), o.icon(), c.x(), c.y(), c.z()));
            unsaved = true;
        }
        return false;
    }

    // once a second while recording
    public static void recordTick(MinecraftClient client) {
        if (!recording || client.world == null || client.player == null || !IslesMap.available()) return;
        if (++recordTicks % 20 != 0) return;
        for (net.minecraft.entity.Entity e : client.world.getEntities()) {
            String text = labelText(e);
            if (text == null) continue;
            Poi p = classify(text, (int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
            if (p != null && record(p)) {
                client.player.sendMessage(Text.literal("Recorded " + title(p.type()) + " (" + p.group().label + ")").withColor(NAVIGATE), true);
            }
        }
        if (recordTicks % 600 == 0) save();
    }

    // /poi labels
    public static List<String> describeLabels(MinecraftClient client, int radius) {
        List<String> out = new ArrayList<>();
        if (client.world == null || client.player == null) return out;
        double farthest = 0;
        List<net.minecraft.entity.Entity> near = new ArrayList<>();
        for (net.minecraft.entity.Entity e : client.world.getEntities()) {
            if (labelText(e) == null || e == client.player) continue;
            double d = e.distanceTo(client.player);
            farthest = Math.max(farthest, d);
            if (d <= radius) near.add(e);
        }
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceTo(client.player)));
        for (net.minecraft.entity.Entity e : near) {
            String kind = e instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity ? "text display"
                : e instanceof AbstractClientPlayerEntity ? "npc" : e.getType().getUntranslatedName();
            String text = visible(labelText(e));
            // a name that looks empty is made of hidden characters or images, so show what's in it
            if ((text.isBlank() || text.contains("\\u")) && e.getCustomName() != null) {
                text += "  raw: " + abbreviate(e.getCustomName().toString(), 300);
            }
            out.add(String.format(Locale.ROOT, "%s \"%s\" at %d %d %d (%.0fm)", kind, text.replace("\n", " / "),
                (int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()), e.distanceTo(client.player)));
        }
        out.add(String.format(Locale.ROOT, "%d labels within %d blocks; the farthest you are receiving is %.0f blocks away.", near.size(), radius, farthest));
        return out;
    }

    // private-use glyphs, control chars etc spelled out as backslash-u codes so you can see them in chat
    static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            int type = Character.getType(c);
            boolean hidden = type == Character.PRIVATE_USE || type == Character.CONTROL && c != '\n' || type == Character.FORMAT
                || type == Character.UNASSIGNED || type == Character.SURROGATE || Character.isSpaceChar(c) && c != ' ';
            sb.append(hidden ? String.format(Locale.ROOT, "\\u%04X", (int) c) : String.valueOf(c));
        }
        return sb.toString();
    }

    private static String abbreviate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }

    // ==============================
    // The points file
    // ==============================

    public static void add(Poi p) {
        ensureLoaded();
        pois.add(canonical(p));
        unsaved = true;
    }

    /** Endings the same node is written with: "iron ore deposit", "coal ore", "wishing well
     * fishing spot", "oak tree"... */
    private static final List<String> ENDINGS = List.of(" deposit", " ore", " fishing spot", " tree", " field", " node");

    /**
     * A node type written the server's way, so the same node always ends up as one type with one toggle.
     * Matches {@code known} names (the server's node list) with or without an ending, or by their
     * first words ("iron" is "Iron Ore", "pond" is "Pond Fishing Spot"). Returns lowercase. A type that's
     * not a known node is left as it is.
     */
    static String canonical(String type, List<String> known) {
        String t = type.trim().toLowerCase(Locale.ROOT);
        List<String> tries = new ArrayList<>(List.of(t));
        for (String end : ENDINGS) if (t.endsWith(end)) tries.add(t.substring(0, t.length() - end.length()));
        for (String c : tries) {
            for (String k : known) if (k.equalsIgnoreCase(c)) return k.toLowerCase(Locale.ROOT);
        }
        for (String c : tries) {
            for (String k : known) if (k.toLowerCase(Locale.ROOT).startsWith(c + " ")) return k.toLowerCase(Locale.ROOT);
        }
        return t;
    }

    private static Poi canonical(Poi p) {
        if (!p.group().profession()) return p;
        String t = canonical(p.type(), com.islesplus.features.nodealertmanager.NodeRepository.getNodeNames());
        if (t.equals(p.type())) return p;
        return new Poi(p.group(), t, p.name().equalsIgnoreCase(p.type()) ? t : p.name(), p.icon(), p.x(), p.y(), p.z());
    }

    private static Poi sameNear(Poi p) {
        for (Poi o : pois) {
            if (o.group() == p.group() && o.type().equals(p.type())
                && Math.abs(o.x() - p.x()) <= SAME_WITHIN && Math.abs(o.z() - p.z()) <= SAME_WITHIN) return o;
        }
        return null;
    }

    public static Poi removeNearest(double x, double z, double range) {
        Poi best = null;
        double bestD = range * range;
        for (Poi p : all()) {
            double d = sq(p.x() + 0.5 - x) + sq(p.z() + 0.5 - z);
            if (d <= bestD) { bestD = d; best = p; }
        }
        if (best != null) {
            pois.remove(best);
            unsaved = true;
        }
        return best;
    }

    private static double sq(double v) { return v * v; }

    // the server pack changes per server
    public static void forgetIcons() { icons.clear(); items.clear(); }

    // cache, typeIcons runs regexes
    private static final Map<String, List<String>> typeIcons = new HashMap<>();

    public static int reload() {
        pois.clear();
        icons.clear();
        loaded = false;
        unsaved = false;
        return all().size();
    }

    private static Path localFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("islesplusplus").resolve("map").resolve("pois.json");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path local = localFile();
        try {
            if (Files.exists(local)) {
                try (Reader r = Files.newBufferedReader(local, StandardCharsets.UTF_8)) { read(JsonParser.parseReader(r), true); }
            } else {
                var res = MinecraftClient.getInstance().getResourceManager().getResource(Identifier.of("islesplusplus", "map/pois.json"));
                if (res.isPresent()) {
                    try (Reader r = new InputStreamReader(res.get().getInputStream(), StandardCharsets.UTF_8)) { read(JsonParser.parseReader(r), false); }
                }
            }
        } catch (IOException | RuntimeException e) {
            IslesLog.runtimeInfo("[Isles++] map points file unreadable: " + e);
        }
    }

    /** Reads the points, writing each node type the server's way and dropping duplicates. Your own
     * file is saved again if that changed it, so it reads the same way every time. */
    static void read(JsonElement json, boolean local) {
        boolean changed = false;
        for (JsonElement el : json.getAsJsonObject().getAsJsonArray("pois")) {
            Poi p = parse(el);
            if (p == null) {
                IslesLog.runtimeInfo("[Isles++] map point skipped (needs group, type, x, y, z): " + el);
                continue;
            }
            Poi c = canonical(p);
            changed |= c != p;
            if (sameNear(c) != null) {
                changed = true;
                continue;
            }
            pois.add(c);
        }
        if (local && changed) unsaved = true;
    }

    static Poi parse(JsonElement el) {
        try {
            JsonObject o = el.getAsJsonObject();
            Group g = Group.valueOf(o.get("group").getAsString().trim().toUpperCase(Locale.ROOT));
            if (g == Group.PLAYERS || g == Group.WAYPOINTS) return null;   // live, or in their own file
            String type = o.get("type").getAsString().trim();
            String name = o.has("name") ? o.get("name").getAsString().trim() : type;
            String icon = o.has("icon") ? o.get("icon").getAsString().trim() : null;
            return new Poi(g, type, name, icon, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void save() {
        if (!unsaved) return;
        JsonArray arr = new JsonArray();
        for (Poi p : pois) {
            JsonObject o = new JsonObject();
            o.addProperty("group", p.group().name());
            o.addProperty("type", p.type());
            if (!p.name().equalsIgnoreCase(p.type())) o.addProperty("name", p.name());
            if (p.icon() != null) o.addProperty("icon", p.icon());
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("pois", arr);
        try {
            Files.createDirectories(localFile().getParent());
            Files.writeString(localFile(), GSON.toJson(root), StandardCharsets.UTF_8);
            unsaved = false;
        } catch (IOException e) {
            IslesLog.runtimeInfo("[Isles++] could not save map points: " + e);
        }
    }

    // "MINING:Tin|MINING:Copper"
    public static String join(Set<String> set) { return String.join("|", new TreeSet<>(set)); }

    public static void split(String s, Set<String> into) {
        into.clear();
        for (String part : s.split("\\|")) if (!part.isBlank()) into.add(part.trim());
    }

    // ==============================
    // Drawing
    // ==============================

    // "Tin Ore" -> "tin_ore"
    static String slug(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** The icon names a type might have. Its own name, or for a short name the game's full one
     * ("Coal" finds coal_deposit.png, "Copper" copper_ore_deposit.png, "Oak" oak_tree.png,
     * "Wheat" wheat_field.png, "Pond" pond_fishing_spot.png, "Smithing" smithing_station.png). */
    static List<String> typeIcons(String type) {
        return typeIcons.computeIfAbsent(type, k -> {
            String t = slug(k);
            return List.of(t, t + "_deposit", t + "_ore_deposit", t + "_tree", t + "_field", t + "_fishing_spot", t + "_station");
        });
    }

    // all of these come from the server's pack, the mod doesn't ship any of its art
    static final Map<String, String> PACK_ICONS = Map.ofEntries(
        // Ores
        Map.entry("adamantium_ore_deposit", "isles:textures/custom/mining/adamantium_ore.png"),
        Map.entry("coal_deposit", "isles:textures/custom/mining/coal_ore.png"),
        Map.entry("cobalt_ore_deposit", "isles:textures/custom/mining/cobalt_ore.png"),
        Map.entry("copper_ore_deposit", "isles:textures/custom/mining/copper_ore.png"),
        Map.entry("gold_ore_deposit", "isles:textures/custom/mining/gold_ore.png"),
        Map.entry("iron_ore_deposit", "isles:textures/custom/mining/iron_ore.png"),
        Map.entry("mithril_ore_deposit", "isles:textures/custom/mining/mithril_ore.png"),
        Map.entry("netherite_ore_deposit", "isles:textures/custom/mining/netherite_ore.png"),
        Map.entry("nickel_ore_deposit", "isles:textures/custom/mining/nickel_ore.png"),
        Map.entry("rhodonite_ore_deposit", "isles:textures/custom/mining/rhodonite_ore.png"),
        Map.entry("rune_essence_deposit", "isles:textures/custom/mining/rune_essence.png"),
        Map.entry("salt_deposit", "isles:textures/custom/mining/salt.png"),
        Map.entry("silver_ore_deposit", "isles:textures/custom/mining/silver_ore.png"),
        Map.entry("tin_ore_deposit", "isles:textures/custom/mining/tin_ore.png"),
        Map.entry("void_ore_deposit", "isles:textures/custom/mining/void_ore.png"),
        // Trees
        Map.entry("ash_tree", "isles:textures/custom/woodcutting/raw_wood_ash.png"),
        Map.entry("birch_tree", "isles:textures/custom/woodcutting/raw_wood_birch.png"),
        Map.entry("fungus_tree", "isles:textures/custom/woodcutting/fungal_stipe.png"),
        Map.entry("oak_tree", "isles:textures/custom/woodcutting/raw_wood_oak.png"),
        Map.entry("palm_tree", "isles:textures/custom/woodcutting/raw_wood_tropical.png"),
        Map.entry("whisper_tree", "isles:textures/custom/woodcutting/raw_wood_willow.png"),
        // Fields
        Map.entry("cabbage_field", "isles:textures/custom/farming/cabbage.png"),
        Map.entry("carrot_field", "minecraft:textures/block/carrots_stage3.png"),
        Map.entry("corn_field", "isles:textures/custom/farming/corn.png"),
        Map.entry("garlic_field", "isles:textures/item/gathering/garlic.png"),
        Map.entry("sugarcane_field", "minecraft:textures/block/sugar_cane.png"),
        Map.entry("tomato_field", "isles:textures/custom/farming/tomato.png"),
        Map.entry("wheat_field", "isles:textures/custom/farming/wheat.png"),
        // Fishing spots (beach and the wishing well use the Fishing icon)
        Map.entry("basic_fishing_spot", "isles:textures/custom/fishing/raw_sardine.png"),
        Map.entry("plains_fishing_spot", "isles:textures/custom/fishing/raw_shrimp.png"),
        Map.entry("pond_fishing_spot", "isles:textures/custom/fishing/raw_stone_clam.png"),
        Map.entry("river_fishing_spot", "isles:textures/custom/fishing/raw_flounder.png"),
        Map.entry("warm_beach_fishing_spot", "isles:textures/custom/fishing/raw_clownfish.png"),
        Map.entry("warm_pond_fishing_spot", "isles:textures/custom/fishing/raw_starfish.png"),
        // Groups
        Map.entry("mining", "isles:textures/custom/ui_item/ui_button_skills_mining.png"),
        Map.entry("farming", "isles:textures/custom/ui_item/ui_button_skills_farming.png"),
        Map.entry("woodcutting", "isles:textures/custom/ui_item/ui_button_skills_woodcutting.png"),
        Map.entry("fishing", "isles:textures/custom/ui_item/ui_button_skills_fishing.png"),
        Map.entry("gathering", "isles:textures/custom/ui_item/ui_button_skills_gathering.png"),
        Map.entry("waystones", "isles:textures/custom/ui_item/waystones/waystone_unlocked.png"),
        // Waystones
        Map.entry("waystone", "isles:textures/custom/ui_item/waystones/waystone_unlocked.png"),
        Map.entry("waystone_mana", "isles:textures/custom/ui_item/waystones/waystone_mana.png"),
        Map.entry("waystone_origin", "isles:textures/custom/ui_item/waystones/waystone_origin.png"),
        Map.entry("waystone_totem", "isles:textures/custom/ui_item/waystones/waystone_totem.png"),
        Map.entry("shrine", "isles:textures/custom/ui_item/waystones/shrine_unlocked.png"),
        Map.entry("shrines", "isles:textures/custom/ui_item/waystones/shrine_unlocked.png"),
        // Stations
        Map.entry("alchemy_station", "isles:textures/custom/ui_item/ui_button_skills_brewing.png"),
        Map.entry("artisan_station", "isles:textures/custom/ui_item/ui_button_skills_artisan.png"),
        Map.entry("artisan_accessories_station", "isles:textures/custom/ui_item/ui_button_skills_artisan.png"),
        Map.entry("artisan_tools_station", "isles:textures/custom/ui_item/ui_button_skills_artisan.png"),
        Map.entry("augmenting_station", "isles:textures/custom/ui_item/ui_button_skills_augmenting.png"),
        Map.entry("cooking_station", "isles:textures/custom/ui_item/ui_button_skills_cooking.png"),
        Map.entry("fletching_station", "isles:textures/custom/ui_item/ui_button_skills_fletching.png"),
        Map.entry("runecrafting_station", "isles:textures/custom/ui_item/ui_button_skills_runecrafting.png"),
        Map.entry("smithing_station", "isles:textures/custom/ui_item/ui_button_skills_smithing.png"),
        Map.entry("auction_house", "isles:textures/custom/ui_item/auction_type_equipment.png"),
        Map.entry("bank", "isles:textures/custom/ui_item/vault.png"),
        Map.entry("merchant", "isles:textures/custom/ui_item/gold_coin_icon.png"),
        Map.entry("wandering_zookeeper", "isles:textures/custom/ui_item/ui_button_skills_beastmaster.png"),
        // Miscellaneous
        Map.entry("misc", "isles:textures/custom/mob_drops/cloth/plush_cloth.png"),
        Map.entry("plushie", "isles:textures/custom/mob_drops/cloth/plush_cloth.png"),
        Map.entry("plushies", "isles:textures/custom/mob_drops/cloth/plush_cloth.png"),
        Map.entry("egg_nest", "isles:textures/custom/mob_drops/pets/egg_white.png"),
        Map.entry("egg_nests", "isles:textures/custom/mob_drops/pets/egg_white.png"),
        // Bosses
        Map.entry("boss_alpha_boar", "isles:textures/custom/ui_item/mob_icons/hostile_boar_brown.png"),
        Map.entry("boss_crimson_dragon", "isles:textures/custom/ui_item/mob_icons/boss_crimson_dragon.png"),
        Map.entry("boss_cthulhu", "isles:textures/custom/ui_item/mob_icons/boss_cthulhu.png"),
        Map.entry("boss_frog", "isles:textures/custom/ui_item/mob_icons/boss_frog.png"),
        // no Broodmother portrait in the pack :(
        Map.entry("boss_harpy_broodmother", "isles:textures/custom/ui_item/mob_icons/boss_nanook.png"),
        Map.entry("boss_nanook", "isles:textures/custom/ui_item/mob_icons/boss_nanook.png"),
        Map.entry("boss_queen_bee", "isles:textures/custom/ui_item/mob_icons/boss_queen_bee.png"),
        Map.entry("boss_reaper", "isles:textures/custom/ui_item/mob_icons/boss_reaper.png"),
        Map.entry("boss_turtle", "isles:textures/custom/ui_item/mob_icons/boss_turtle.png"));

    // (u, v, rw, rh) = box around the visible pixels so 16px and 32px icons with different margins come out the same size
    private record Icon(Identifier id, int w, int h, int u, int v, int rw, int rh) {}

    private static final Map<List<String>, Optional<Icon>> icons = new HashMap<>();

    private static Optional<Icon> icon(List<String> names) {
        return icons.computeIfAbsent(names, k -> {
            var rm = MinecraftClient.getInstance().getResourceManager();
            for (String name : names) {
                String path = PACK_ICONS.get(name);
                if (path == null) continue;
                Identifier id = Identifier.of(path);
                var res = rm.getResource(id);
                if (res.isEmpty()) continue;
                try (var in = res.get().getInputStream(); var img = NativeImage.read(in)) {
                    int w = img.getWidth(), h = img.getHeight();
                    int x0 = w, y0 = h, x1 = -1, y1 = -1;
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            if (img.getColorArgb(x, y) >>> 24 != 0) {
                                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                            }
                    if (x1 < 0) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1; }
                    return Optional.of(new Icon(id, w, h, x0, y0, x1 - x0 + 1, y1 - y0 + 1));
                } catch (IOException e) {
                    IslesLog.runtimeInfo("[Isles++] map icon " + id + " unreadable: " + e);
                }
            }
            return Optional.empty();
        });
    }

    public static void drawIcon(DrawContext ctx, Group g, String resource, int cx, int cy) {
        if (g == Group.WAYPOINTS) {
            IslesMap.diamond(ctx, cx, cy, g.colour);
            return;
        }
        List<String> names = new ArrayList<>();
        if (resource != null) names.addAll(typeIcons(resource));
        names.add(g.icon);
        drawIcon(ctx, g, names, cx, cy, ICON);
    }

    private static void drawIcon(DrawContext ctx, Poi p, int cx, int cy) {
        List<String> names = new ArrayList<>();
        if (p.icon() != null) names.add(typeIcons(p.icon()).get(0));   // its slug, cached
        names.addAll(typeIcons(p.type()));
        names.add(p.group().icon);
        drawIcon(ctx, p.group(), names, cx, cy, ICON);
    }

    // drawn as an item model, not a flat texture (plushie #6)
    static final Map<String, String> PACK_ITEMS = Map.of(
        "plushie", "isles:plushies/plushy6",
        "plushies", "isles:plushies/plushy6");
    private static final Map<String, Optional<ItemStack>> items = new HashMap<>();

    private static Optional<ItemStack> item(String name) {
        String model = PACK_ITEMS.get(name);
        if (model == null) return Optional.empty();
        return items.computeIfAbsent(name, k -> {
            Identifier id = Identifier.of(model);
            Identifier file = Identifier.of(id.getNamespace(), "items/" + id.getPath() + ".json");
            if (MinecraftClient.getInstance().getResourceManager().getResource(file).isEmpty()) return Optional.empty();
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponentTypes.ITEM_MODEL, id);
            return Optional.of(stack);
        });
    }

    private static void drawIcon(DrawContext ctx, Group g, List<String> names, int cx, int cy, int size) {
        int x = cx - size / 2, y = cy - size / 2;
        if (names.get(0).equals(PROFESSIONS)) {
            // woodcutting and mining on top, farming and fishing below, a quarter each
            int q = size / 2;
            for (int i = 0; i < 4; i++)
                drawIcon(ctx, g, List.of(PROFESSION_ICONS.get(i)), x + i % 2 * q + q / 2, y + i / 2 * q + q / 2, q);
            return;
        }
        Optional<ItemStack> item = item(names.get(0));
        if (item.isPresent()) {
            // items are 16px, scale down
            ctx.getMatrices().pushMatrix();
            try {
                ctx.getMatrices().translate(cx, cy);
                ctx.getMatrices().scale(size / 16f, size / 16f);
                ctx.drawItem(item.get(), -8, -8);
            } finally {
                ctx.getMatrices().popMatrix();
            }
            return;
        }
        Optional<Icon> icon = icon(names);
        if (icon.isPresent()) {
            Icon i = icon.get();
            // the longer side fills size and the other keeps the aspect ratio, centred
            int m = Math.max(i.rw(), i.rh());
            int dw = Math.max(1, Math.round((float) size * i.rw() / m));
            int dh = Math.max(1, Math.round((float) size * i.rh() / m));
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, i.id(), cx - dw / 2, cy - dh / 2,
                i.u(), i.v(), dw, dh, i.rw(), i.rh(), i.w(), i.h());
        } else {
            ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, Theme.HUD_SHADOW);
            ctx.fill(x + 2, y + 2, x + size - 2, y + size - 2, g.colour);
        }
    }

    // +2px each time the count doubles, ICON..16
    static int clusterSize(int n) {
        return Math.min(2 * ICON, ICON + 2 * (31 - Integer.numberOfLeadingZeros(Math.max(1, n))));
    }

    // tooltip only gets built when hovered
    public record Shown(java.util.function.Supplier<List<Text>> tooltip, int x, int y, int z, int sx, int sy) {}

    // same colours as the game's node tooltips
    private static final int COUNT = 0xA8C4B0, NAME = 0xF4EAD2, NAVIGATE = 0x55FF55;

    static List<Text> tooltip(String name) {
        return List.of(Text.literal(title(name)).withColor(NAME), Text.empty(), Text.literal("Click to navigate!").withColor(NAVIGATE));
    }

    // the waypoint goes to the entrance for these
    static List<Text> entranceTooltip(String name) {
        return List.of(Text.literal(title(name)).withColor(NAME), Text.literal("Waypoint goes to its entrance").withColor(COUNT),
            Text.empty(), Text.literal("Click to navigate!").withColor(NAVIGATE));
    }

    /**
     * A node or cluster's tooltip, laid out like the game's. A "14x Mining Node" header per profession, then
     * " 3x Iron Ore Deposit" per type (its last word in the profession's colour), then a blank line,
     * then "Click to navigate!".
     */
    static List<Text> nodeTooltip(List<Poi> nodes) {
        Map<Group, Map<String, Integer>> byGroup = new java.util.EnumMap<>(Group.class);
        for (Poi p : nodes) byGroup.computeIfAbsent(p.group(), g -> new LinkedHashMap<>()).merge(p.type(), 1, Integer::sum);
        List<Text> lines = new ArrayList<>();
        byGroup.forEach((g, types) -> {
            if (!lines.isEmpty()) lines.add(Text.empty());
            int rgb = g.colour & 0xFFFFFF;
            int total = types.values().stream().mapToInt(Integer::intValue).sum();
            lines.add(Text.literal(total + "x ").withColor(COUNT).append(Text.literal(g.node).withColor(rgb)));
            types.forEach((lowerType, n) -> {
                String type = title(lowerType);
                int split = type.lastIndexOf(' ');
                var line = Text.literal(" " + n + "x ").withColor(COUNT);
                if (split < 0) line.append(Text.literal(type).withColor(NAME));
                else line.append(Text.literal(type.substring(0, split + 1)).withColor(NAME))
                         .append(Text.literal(type.substring(split + 1)).withColor(rgb));
                lines.add(line);
            });
        });
        lines.add(Text.empty());
        lines.add(Text.literal("Click to navigate!").withColor(NAVIGATE));
        return lines;
    }

    /**
     * The icons to try for a cluster. If it's all one type, that resource's own icon
     * ("copper_ore_deposit.png"). Several types of one profession get the profession's icon
     * ("mining.png"), and mixed professions get {@link #PROFESSIONS}, the four profession icons in a grid.
     */
    static List<String> clusterIcon(List<Poi> nodes) {
        Group g = nodes.get(0).group();
        if (nodes.stream().anyMatch(p -> p.group() != g)) return List.of(PROFESSIONS);
        if (nodes.stream().map(Poi::type).distinct().count() == 1) {
            List<String> names = new ArrayList<>(typeIcons(nodes.get(0).type()));
            names.add(g.icon);
            return names;
        }
        return List.of(g.icon);
    }

    static final String PROFESSIONS = "professions";
    private static final List<String> PROFESSION_ICONS = List.of("woodcutting", "mining", "farming", "fishing");

    // on screen px, zooming in splits them up
    static final int CLUSTER_PX = 32;

    /** The grid cell a block falls in at this zoom. The grid is fixed to the world, not the
     * screen, so clusters don't change when you just pan the map. */
    static long cell(double bx, double bz, float ppb) {
        long cx = (long) Math.floor(bx * ppb / CLUSTER_PX), cz = (long) Math.floor(bz * ppb / CLUSTER_PX);
        return cx << 32 | (cz & 0xFFFFFFFFL);
    }

    // (x, y, w, h) box, centred on (cx, cz). on the minimap the centre is you
    static List<Shown> draw(DrawContext ctx, int x, int y, int w, int h, double cx, double cz, float ppb, boolean minimap) {
        List<Shown> drawn = new ArrayList<>();
        boolean zoomedIn = Math.max(w, h) / 2.0 / ppb <= STATION_RANGE;
        Map<Long, List<Poi>> nodeCells = new LinkedHashMap<>();
        Map<Poi, int[]> live = plushies(PlushieRepository.getCachedPlushies(), PlushieRepository::isOwned, PlushieFinder.hideFirstPlushie);
        List<Poi> points = new ArrayList<>(all());
        points.addAll(live.keySet());
        points.addAll(EGG_NESTS);
        for (Poi p : points) {
            if (!shown(p)) continue;
            if (p.group().profession()) {
                nodeCells.computeIfAbsent(cell(p.x() + 0.5, p.z() + 0.5, ppb), k -> new ArrayList<>()).add(p);
                continue;
            }
            if (p.group() == Group.STATIONS && (minimap
                ? sq(p.x() + 0.5 - cx) + sq(p.z() + 0.5 - cz) > (double) STATION_RANGE * STATION_RANGE
                : !zoomedIn)) continue;
            int[] s = onMap(p.x() + 0.5, p.z() + 0.5, x, y, w, h, cx, cz, ppb);
            if (s == null) continue;
            drawIcon(ctx, p, s[0], s[1]);
            int[] to = live.getOrDefault(p, new int[]{p.x(), p.y(), p.z()});
            boolean entrance = to[0] != p.x() || to[1] != p.y() || to[2] != p.z();
            drawn.add(new Shown(() -> entrance ? entranceTooltip(p.name()) : tooltip(p.name()), to[0], to[1], to[2], s[0], s[1]));
        }
        // mixed clusters go last so their bigger grid ends up on top of the ones around it
        if (shown(Group.WAYPOINTS)) {
            for (SavedWaypoints.Waypoint wp : SavedWaypoints.all()) {
                int[] s = onMap(wp.x + 0.5, wp.z + 0.5, x, y, w, h, cx, cz, ppb);
                if (s == null) continue;
                IslesMap.diamond(ctx, s[0], s[1], 0xFF000000 | wp.color);
                String name = wp.name, at = wp.x + ", " + wp.y + ", " + wp.z;
                // one shown in the world can't be navigated to: that would be a second waypoint on top of it
                boolean navigable = !wp.inWorld;
                drawn.add(new Shown(() -> navigable
                    ? List.of(Text.literal(name).withColor(NAME), Text.literal(at).withColor(COUNT), Text.empty(),
                        Text.literal("Click to navigate!").withColor(NAVIGATE))
                    : List.of(Text.literal(name).withColor(NAME), Text.literal(at).withColor(COUNT)), wp.x, wp.y, wp.z, s[0], s[1]));
            }
        }
        List<List<Poi>> clusters = new ArrayList<>(nodeCells.values());
        clusters.sort(java.util.Comparator.comparing(c -> c.stream().anyMatch(p -> p.group() != c.get(0).group())));
        for (List<Poi> cluster : clusters) drawCluster(ctx, drawn, cluster, x, y, w, h, cx, cz, ppb);
        MinecraftClient client = MinecraftClient.getInstance();
        if (shown(Group.PLAYERS) && client.world != null) {
            for (AbstractClientPlayerEntity pl : client.world.getPlayers()) {
                if (pl == client.player || !isRealPlayer(client, pl)) continue;
                int[] s = onMap(pl.getX(), pl.getZ(), x, y, w, h, cx, cz, ppb);
                if (s == null) continue;
                // party members get a coloured frame instead of the ink outline
                boolean party = Party.members.contains(pl.getName().getString().toLowerCase(Locale.ROOT));
                ctx.fill(s[0] - ICON / 2 - 1, s[1] - ICON / 2 - 1, s[0] + ICON / 2 + 1, s[1] + ICON / 2 + 1, party ? PARTY_FRAME : Theme.HUD_SHADOW);
                PlayerSkinDrawer.draw(ctx, pl.getSkin(), s[0] - ICON / 2, s[1] - ICON / 2, ICON);
                String name = pl.getName().getString();
                drawn.add(new Shown(() -> tooltip(name), pl.getBlockX(), pl.getBlockY(), pl.getBlockZ(), s[0], s[1]));
            }
        }
        return drawn;
    }

    /** True for a real player, not a server NPC made to look like one (the world's NPCs are player
     * entities). An NPC has no tab-list entry once spawned (NPC plugins drop it straight after),
     * often a version-2 id where every real account has version 4, or a name no account can have. */
    static boolean isRealPlayer(MinecraftClient client, AbstractClientPlayerEntity pl) {
        if (client.getNetworkHandler() == null || client.getNetworkHandler().getPlayerListEntry(pl.getUuid()) == null) return false;
        return isRealPlayer(pl.getUuid(), pl.getName().getString());
    }

    static boolean isRealPlayer(java.util.UUID id, String name) {
        return id.version() == 4 && Party.USERNAME.matcher(name).matches();
    }

    private static void drawCluster(DrawContext ctx, List<Shown> drawn, List<Poi> nodes,
                                    int x, int y, int w, int h, double cx, double cz, float ppb) {
        double mx = 0, mz = 0;
        for (Poi p : nodes) { mx += p.x() + 0.5; mz += p.z() + 0.5; }
        int n = nodes.size();
        mx /= n;
        mz /= n;
        int[] s = onMap(mx, mz, x, y, w, h, cx, cz, ppb);
        if (s == null) return;
        if (n == 1) {
            drawIcon(ctx, nodes.get(0), s[0], s[1]);
        } else {
            List<String> icon = clusterIcon(nodes);
            // the professions grid is twice the size so each of its four icons is as big as a normal cluster
            int size = clusterSize(n) * (icon.get(0).equals(PROFESSIONS) ? 2 : 1);
            drawIcon(ctx, nodes.get(0).group(), icon, s[0], s[1], size);
            Fonts.drawHud(ctx, String.valueOf(n), s[0] + size / 2, s[1], Theme.HUD_TEXT, Fonts.SMALL);
        }
        Poi target = nodes.get(0);
        for (Poi p : nodes) if (sq(p.x() + 0.5 - mx) + sq(p.z() + 0.5 - mz) < sq(target.x() + 0.5 - mx) + sq(target.z() + 0.5 - mz)) target = p;
        drawn.add(new Shown(() -> nodeTooltip(nodes), target.x(), target.y(), target.z(), s[0], s[1]));
    }

    private static int[] onMap(double bx, double bz, int x, int y, int w, int h, double cx, double cz, float ppb) {
        int sx = (int) Math.round(x + w / 2.0 + (bx - cx) * ppb);
        int sy = (int) Math.round(y + h / 2.0 + (bz - cz) * ppb);
        int half = ICON / 2;
        if (sx < x + half || sx > x + w - half || sy < y + half || sy > y + h - half) return null;
        return new int[]{sx, sy};
    }
}
