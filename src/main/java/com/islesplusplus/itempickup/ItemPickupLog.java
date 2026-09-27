package com.islesplusplus.itempickup;

import com.islesplus.hud.HudAnchor;
import com.islesplus.hud.HudElement;
import com.islesplus.hud.HudPlacement;
import com.islesplus.sync.FeatureFlags;
import com.islesplus.ui.Fonts;
import com.islesplus.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// "[icon] Whisperleaf x3" for each pickup. newest on top, same item stacks onto its row
// fed from ItemPickupMixin
public final class ItemPickupLog {
    public static final String KILL_KEY = "item_pickup_log";
    public static boolean enabled = true;

    static final long HOLD_MS = 2_000L, FADE_MS = 1_000L, SHOW_MS = HOLD_MS + FADE_MS;
    static final int MAX_ROWS = 6;
    private static final int ICON = 16, GAP = 4, ROW_GAP = 2;

    static final class Entry {
        final String name;
        final ItemStack icon;
        final int color;
        int amount;
        long lastMs;

        Entry(String name, ItemStack icon, int color, int amount, long lastMs) {
            this.name = name; this.icon = icon; this.color = color; this.amount = amount; this.lastMs = lastMs;
        }
    }

    // newest first
    static final List<Entry> entries = new ArrayList<>();

    private ItemPickupLog() {}

    public static void onPickup(ItemStack stack, int amount) {
        if (!enabled || FeatureFlags.isKilled(KILL_KEY) || stack.isEmpty() || amount <= 0) return;
        Text name = stack.getName();
        add(name.getString(), stack.copyWithCount(1), colorOf(name), amount, Util.getMeasuringTimeMs());
    }

    static void add(String name, ItemStack icon, int color, int amount, long now) {
        prune(now);
        Entry e = null;
        for (Entry x : entries) if (x.name.equals(name)) { e = x; break; }
        if (e != null) {
            entries.remove(e);
            e.amount += amount;
            e.lastMs = now;
        } else {
            e = new Entry(name, icon, color, amount, now);
        }
        entries.add(0, e);
        while (entries.size() > MAX_ROWS) entries.remove(entries.size() - 1);
    }

    static float alpha(Entry e, long now) {
        long age = now - e.lastMs;
        if (age <= HOLD_MS) return 1f;
        return Math.max(0f, 1f - (float) (age - HOLD_MS) / FADE_MS);
    }

    static void prune(long now) {
        entries.removeIf(e -> now - e.lastMs > SHOW_MS);
    }

    public static void reset() { entries.clear(); }

    // first colour in the name (server colours by rarity)
    private static int colorOf(Text name) {
        Optional<TextColor> c = name.visit((Style style, String s) ->
            style.getColor() != null && !s.isBlank() ? Optional.of(style.getColor()) : Optional.empty(), Style.EMPTY);
        return c.map(t -> 0xFF000000 | t.getRgb()).orElse(Theme.HUD_TEXT);
    }

    private static List<Entry> rows(boolean preview) {
        long now = Util.getMeasuringTimeMs();
        prune(now);
        if (!entries.isEmpty() || !preview) return entries;
        // the editor sample doesn't fade: stamped "now" every frame
        return List.of(
            new Entry("Whisperleaf", new ItemStack(Items.OAK_LEAVES), Theme.HUD_TEXT, 3, now),
            new Entry("Driftstone", new ItemStack(Items.COBBLESTONE), Theme.HUD_TEXT, 12, now));
    }

    private static int fade(int argb, float a) {
        return Math.round((argb >>> 24) * a) << 24 | (argb & 0xFFFFFF);
    }

    private static String amount(Entry e) { return "x" + e.amount; }

    public static final HudElement ELEMENT = new HudElement("item_pickup", "Item Pickups",
        new HudPlacement(HudAnchor.START, HudAnchor.CENTER, 4, 0)) {
        @Override public boolean enabled() { return enabled; }
        @Override public boolean active(MinecraftClient client) {
            if (!enabled || FeatureFlags.isKilled(KILL_KEY) || client.options.hudHidden) return false;
            return !rows(false).isEmpty();
        }
        @Override public Size measure(boolean preview) {
            List<Entry> rows = rows(preview);
            int w = 0;
            for (Entry e : rows) w = Math.max(w, Fonts.hudWidth(e.name) + GAP + Fonts.hudWidth(amount(e)));
            return new Size(ICON + GAP + w, rows.size() * (ICON + ROW_GAP) - ROW_GAP);
        }
        @Override public void draw(DrawContext ctx, Frame f) {
            List<Entry> rows = rows(f.preview());
            long now = Util.getMeasuringTimeMs();
            int textDy = (ICON - Fonts.height(Fonts.BODY)) / 2;
            int y = 0;
            for (Entry e : rows) {
                float a = alpha(e, now);
                // hack: nearly transparent text comes out solid for some reason, just skip it
                if (a < 0.05f) { y += ICON + ROW_GAP; continue; }
                // can't fade items, shrink them instead
                float iconScale = a;
                ctx.getMatrices().pushMatrix();
                try {
                    ctx.getMatrices().translate(ICON / 2f, y + ICON / 2f);
                    ctx.getMatrices().scale(iconScale, iconScale);
                    ctx.drawItem(e.icon, -ICON / 2, -ICON / 2);
                } finally {
                    ctx.getMatrices().popMatrix();
                }
                int x = ICON + GAP;
                Fonts.drawHud(ctx, e.name, x, y + textDy, fade(e.color, a));
                Fonts.drawHud(ctx, amount(e), x + Fonts.hudWidth(e.name) + GAP, y + textDy, fade(Theme.HUD_GOLD, a));
                y += ICON + ROW_GAP;
            }
        }
    };
}
