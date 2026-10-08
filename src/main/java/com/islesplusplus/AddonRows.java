package com.islesplusplus;

import com.islesplus.IslesPlusConfig;
import com.islesplus.screen.islesscreen.FeatureRow;
import com.islesplus.screen.islesscreen.rows.GlowColorDrawer;
import com.islesplus.ui.Flow;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Metrics;
import com.islesplus.ui.Theme;
import com.islesplus.ui.Widget;
import com.islesplus.ui.widgets.FootnoteBox;
import com.islesplus.ui.widgets.InfoChip;
import com.islesplus.ui.widgets.Label;
import com.islesplus.ui.widgets.SmallToggle;
import com.islesplus.ui.widgets.ValueSlider;
import com.islesplusplus.healthborder.HealthBorder;
import com.islesplusplus.itempickup.ItemPickupLog;
import com.islesplusplus.map.IslesMap;

import java.util.List;

// our rows on the Isles+ QOL tab
public final class AddonRows {
    private static final int ROW_GAP = 4;

    private AddonRows() {}

    public static void addTo(List<FeatureRow> rows) {
        // after Mod-only Sounds, as when they were part of Isles+; the rest at the end
        int at = Math.min(1, rows.size());
        rows.add(at, healthBorder());
        rows.add(at + 1, map());
        rows.add(new FeatureRow("Item Pickups", "Show what you pick up, with its icon and amount.")
            .killedKey(ItemPickupLog.KILL_KEY)
            .toggle(() -> ItemPickupLog.enabled,
                v -> { ItemPickupLog.enabled = v; if (!v) ItemPickupLog.reset(); IslesPlusConfig.save(); }));
        rows.add(new FeatureRow("Glow All Ground Items", "Make every dropped item on the ground glow.")
            .toggle(() -> GroundGlow.enabled,
                v -> { GroundGlow.enabled = v; IslesPlusConfig.save(); }));
    }

    private static FeatureRow healthBorder() {
        int labelW = Fonts.labelColumn(Fonts.SMALL, "Strength");
        Widget drawer = new Flow.Column(Metrics.DRAWER_GAP)
            .add(GlowColorDrawer.of(
                () -> HealthBorder.hue, v -> HealthBorder.hue = v,
                () -> HealthBorder.saturation, v -> HealthBorder.saturation = v,
                () -> HealthBorder.lightness, v -> HealthBorder.lightness = v))
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Label("Strength", Theme.TEXT_LABEL, Fonts.SMALL).fixed(labelW))
                .add(new ValueSlider(() -> HealthBorder.strength, v -> HealthBorder.strength = v,
                    0.05f, HealthBorder.MAX_STRENGTH, 0.05f, IslesPlusConfig::save))
                .add(new InfoChip(() -> Math.round(HealthBorder.strength * 100) + "%")
                    .fixed(Fonts.width("100%", Fonts.SMALL) + 2 * Metrics.CHIP_PAD_X)));
        return new FeatureRow("Health Border", "Red glow at the screen edges as your HP drops.")
            .killedKey(HealthBorder.KILL_KEY)
            .toggle(() -> HealthBorder.enabled,
                v -> { HealthBorder.enabled = v; IslesPlusConfig.save(); })
            .drawer(drawer);
    }

    // position/size of the minimap is in EDIT HUD, not here
    private static FeatureRow map() {
        int labelW = Fonts.labelColumn(Fonts.SMALL, "Zoom");
        Widget drawer = new Flow.Column(Metrics.DRAWER_GAP)
            .add(new Flow.WrapRow(ROW_GAP, ROW_GAP)
                .add(new Label("Zoom", Theme.TEXT_LABEL, Fonts.SMALL).fixed(labelW))
                .add(new ValueSlider(() -> IslesMap.zoom, v -> IslesMap.zoom = v,
                    IslesMap.MIN_ZOOM, IslesMap.MAX_ZOOM, 0.25f, IslesPlusConfig::save))
                .add(new InfoChip(() -> IslesMap.zoom + "x")
                    .fixed(Fonts.width("0.25x", Fonts.SMALL) + 2 * Metrics.CHIP_PAD_X)))
            .add(new SmallToggle("Map as you explore", () -> IslesMap.generating,
                () -> IslesMap.setGenerating(!IslesMap.generating)))
            .add(new FootnoteBox("Fills the map in with the places you visit.",
                "Saved in config/islesplusplus/map."));
        return new FeatureRow("Map", "Minimap, and the full map on M.")
            .killedKey(IslesMap.KILL_KEY)
            .toggle(() -> IslesMap.minimapEnabled,
                v -> { IslesMap.minimapEnabled = v; IslesPlusConfig.save(); })
            .drawer(drawer);
    }
}
