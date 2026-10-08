package com.islesplusplus;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.islesplus.logging.IslesLog;
import com.islesplusplus.healthborder.HealthBorder;
import com.islesplusplus.itempickup.ItemPickupLog;
import com.islesplusplus.map.IslesMap;
import com.islesplusplus.map.MapPois;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

// lives in config/islesplusplus/config.json, loads/saves along with the Isles+ config (see IslesPlusConfigMixin)
public final class AddonConfig {
    private AddonConfig() {}

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("islesplusplus").resolve("config.json"); }

    public static void load() {
        JsonObject o = new JsonObject();
        try {
            if (Files.exists(path())) o = JsonParser.parseString(Files.readString(path())).getAsJsonObject();
        } catch (Exception e) {
            IslesLog.runtimeInfo("[Isles++] config unreadable, using defaults: " + e);
        }
        HealthBorder.enabled    = bool(o, "healthBorderEnabled", true);
        HealthBorder.hue        = num(o, "healthBorderHue", 0f);
        HealthBorder.saturation = num(o, "healthBorderSaturation", 1f);
        HealthBorder.lightness  = num(o, "healthBorderLightness", 0.5f);
        HealthBorder.strength   = num(o, "healthBorderStrength", 0.5f);
        ItemPickupLog.enabled   = bool(o, "itemPickupLogEnabled", true);
        GroundGlow.enabled      = bool(o, "groundGlowEnabled", true);
        IslesMap.minimapEnabled = bool(o, "minimapEnabled", true);
        IslesMap.generating     = bool(o, "mapGenerating", true);
        IslesMap.zoom = Math.max(IslesMap.MIN_ZOOM, Math.min(IslesMap.MAX_ZOOM, num(o, "minimapZoom", 1f)));
        MapPois.split(o.has("mapHiddenPoints") ? o.get("mapHiddenPoints").getAsString() : "", MapPois.hidden);
        MapPois.split(o.has("mapShownNodes") ? o.get("mapShownNodes").getAsString() : "", MapPois.selected);
    }

    public static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("healthBorderEnabled", HealthBorder.enabled);
        o.addProperty("healthBorderHue", HealthBorder.hue);
        o.addProperty("healthBorderSaturation", HealthBorder.saturation);
        o.addProperty("healthBorderLightness", HealthBorder.lightness);
        o.addProperty("healthBorderStrength", HealthBorder.strength);
        o.addProperty("itemPickupLogEnabled", ItemPickupLog.enabled);
        o.addProperty("groundGlowEnabled", GroundGlow.enabled);
        o.addProperty("minimapEnabled", IslesMap.minimapEnabled);
        o.addProperty("mapGenerating", IslesMap.generating);
        o.addProperty("minimapZoom", IslesMap.zoom);
        o.addProperty("mapHiddenPoints", MapPois.join(MapPois.hidden));
        o.addProperty("mapShownNodes", MapPois.join(MapPois.selected));
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception e) {
            IslesLog.runtimeInfo("[Isles++] could not save config: " + e);
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try { return o.has(k) ? o.get(k).getAsBoolean() : def; } catch (Exception e) { return def; }
    }

    private static float num(JsonObject o, String k, float def) {
        try { return o.has(k) ? o.get(k).getAsFloat() : def; } catch (Exception e) { return def; }
    }
}
