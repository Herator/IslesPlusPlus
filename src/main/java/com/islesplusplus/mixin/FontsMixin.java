package com.islesplusplus.mixin;

import com.islesplus.ui.Fonts;
import com.islesplus.ui.Theme;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// fade the outline with the text, otherwise fading pickup lines leave a black ghost
@Mixin(value = Fonts.class, remap = false)
public class FontsMixin {
    // every overload ("*"): only the one taking a scale draws, and a bare name picks the first
    @ModifyArg(method = "drawHud*",at = @At(value = "INVOKE", target = "Lcom/islesplus/ui/Fonts;drawRun"), index = 4)
    private static int islesplusplus$fadeOutline(int colour, @Local(argsOnly = true, ordinal = 2) int argb) {
        if (colour != Theme.HUD_SHADOW) return colour;
        int a = Math.round((Theme.HUD_SHADOW >>> 24) * (argb >>> 24) / 255f);
        return a << 24 | (Theme.HUD_SHADOW & 0xFFFFFF);
    }
}
