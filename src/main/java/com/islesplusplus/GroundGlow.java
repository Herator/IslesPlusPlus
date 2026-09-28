package com.islesplusplus;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.MathHelper;

public final class GroundGlow {
    public static boolean enabled = true;
    public static final int GLOW_RGB = MathHelper.hsvToRgb(0.083f, 1f, 1f);

    private GroundGlow() {}

    public static boolean shouldGlow(Entity entity) {
        return enabled && entity instanceof ItemEntity ie && !ie.getStack().isEmpty();
    }
}
