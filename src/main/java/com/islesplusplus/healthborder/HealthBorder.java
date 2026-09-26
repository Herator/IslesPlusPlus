package com.islesplusplus.healthborder;

import com.islesplus.sync.FeatureFlags;
import com.islesplus.ui.ColorMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

// coloured glow around the screen edge, grows as hp drops
public final class HealthBorder {
    public static final String KILL_KEY = "health_border";

    public static boolean enabled = true;
    public static float hue = 0f, saturation = 1f, lightness = 0.5f;
    // edge opacity at 0 hp. capped below 1 so you can still see
    public static float strength = 0.5f;
    public static final float MAX_STRENGTH = 0.85f;

    // share of the shorter screen side
    private static final float DEPTH = 0.18f;

    private HealthBorder() {}

    // 0 = full hp, 1 = dead
    static float intensity(float health, float maxHealth) {
        if (maxHealth <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, 1f - health / maxHealth));
    }

    static int ringAlpha(int i, int depth, float intensity, float strength) {
        float fade = 1f - (float) i / depth;
        float a = Math.min(strength, MAX_STRENGTH) * intensity * fade * fade;
        return Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
    }

    public static void render(DrawContext ctx, MinecraftClient client) {
        if (!enabled || FeatureFlags.isKilled(KILL_KEY)) return;
        if (client.player == null || client.options.hudHidden) return;
        float k = intensity(client.player.getHealth(), client.player.getMaxHealth());
        if (k <= 0f) return;

        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int depth = Math.max(1, Math.round(Math.min(w, h) * DEPTH));
        int rgb = ColorMath.hslToRgb(hue, saturation, lightness) & 0xFFFFFF;
        // one 1px frame per ring, they're nested so nothing gets painted twice
        for (int i = 0; i < depth && 2 * i < Math.min(w, h); i++) {
            int a = ringAlpha(i, depth, k, strength);
            if (a == 0) break;
            int c = a << 24 | rgb;
            ctx.fill(i, i, w - i, i + 1, c);
            ctx.fill(i, h - i - 1, w - i, h - i, c);
            ctx.fill(i, i + 1, i + 1, h - i - 1, c);
            ctx.fill(w - i - 1, i + 1, w - i, h - i - 1, c);
        }
    }
}
