package com.islesplusplus.map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.islesplus.logging.IslesLog;
import com.islesplus.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// saved in waypoints.json, separate from pois.json so that one can still follow the shipped file
public final class SavedWaypoints {
    public static final int DEFAULT_COLOR = Theme.HUD_RED & 0xFFFFFF;

    public static final class Waypoint {
        public String name;
        public int x, y, z;
        // 0xRRGGBB
        public int color = DEFAULT_COLOR;
        // stays on the maps either way
        public boolean inWorld = true;
        public boolean beam = true;

        public Waypoint(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Waypoint copy() {
            Waypoint c = new Waypoint(name, x, y, z);
            c.copyFrom(this);
            return c;
        }

        public void copyFrom(Waypoint o) {
            name = o.name; x = o.x; y = o.y; z = o.z; color = o.color; inWorld = o.inWorld; beam = o.beam;
        }
    }

    private static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean loaded;

    private SavedWaypoints() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("islesplusplus").resolve("map").resolve("waypoints.json");
    }

    public static List<Waypoint> all() {
        if (!loaded) {
            loaded = true;
            Path f = file();
            if (Files.exists(f)) {
                try {
                    waypoints.addAll(fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))));
                } catch (IOException | RuntimeException e) {
                    IslesLog.runtimeInfo("[Isles++] saved waypoints unreadable: " + e);
                }
            }
        }
        return waypoints;
    }

    // so the map doesn't stack the temporary waypoint on top of it
    public static boolean inWorldAt(int x, int y, int z) {
        return all().stream().anyMatch(w -> w.inWorld && w.x == x && w.y == y && w.z == z);
    }

    public static String nextName() {
        for (int n = 1; ; n++) {
            String name = "Waypoint " + n;
            if (all().stream().noneMatch(w -> w.name.equalsIgnoreCase(name))) return name;
        }
    }

    public static Waypoint add(Waypoint w) {
        all().add(w);
        save();
        return w;
    }

    public static void remove(Waypoint w) {
        if (all().remove(w)) save();
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(toJson(waypoints)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            IslesLog.runtimeInfo("[Isles++] could not save waypoints: " + e);
        }
    }

    static JsonObject toJson(List<Waypoint> list) {
        JsonArray arr = new JsonArray();
        for (Waypoint w : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name);
            o.addProperty("x", w.x);
            o.addProperty("y", w.y);
            o.addProperty("z", w.z);
            o.addProperty("color", String.format(Locale.ROOT, "#%06X", w.color));
            o.addProperty("world", w.inWorld);
            o.addProperty("beam", w.beam);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("waypoints", arr);
        return root;
    }

    // missing coord = skip it, anything else falls back to the default
    static List<Waypoint> fromJson(JsonElement json) {
        List<Waypoint> out = new ArrayList<>();
        for (JsonElement el : json.getAsJsonObject().getAsJsonArray("waypoints")) {
            try {
                JsonObject o = el.getAsJsonObject();
                Waypoint w = new Waypoint(o.has("name") ? o.get("name").getAsString() : "Waypoint",
                    o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                if (o.has("color")) w.color = Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) & 0xFFFFFF;
                if (o.has("world")) w.inWorld = o.get("world").getAsBoolean();
                if (o.has("beam")) w.beam = o.get("beam").getAsBoolean();
                out.add(w);
            } catch (RuntimeException e) {
                IslesLog.runtimeInfo("[Isles++] saved waypoint skipped: " + el);
            }
        }
        return out;
    }
}
