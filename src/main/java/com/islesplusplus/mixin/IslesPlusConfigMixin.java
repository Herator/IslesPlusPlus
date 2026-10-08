package com.islesplusplus.mixin;

import com.islesplus.IslesPlusConfig;
import com.islesplusplus.AddonConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IslesPlusConfig.class, remap = false)
public class IslesPlusConfigMixin {
    @Inject(method = "load", at = @At("TAIL"))
    private static void islesplusplus$load(CallbackInfo ci) { AddonConfig.load(); }

    @Inject(method = "save", at = @At("TAIL"))
    private static void islesplusplus$save(CallbackInfo ci) { AddonConfig.save(); }
}
