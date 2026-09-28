package com.islesplusplus.mixin;

import com.islesplusplus.GroundGlow;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityGlowMixin {
    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void islesplusplus$groundGlow(CallbackInfoReturnable<Boolean> cir) {
        if (GroundGlow.shouldGlow((Entity) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void islesplusplus$groundGlowColour(CallbackInfoReturnable<Integer> cir) {
        if (GroundGlow.shouldGlow((Entity) (Object) this)) cir.setReturnValue(GroundGlow.GLOW_RGB);
    }
}
