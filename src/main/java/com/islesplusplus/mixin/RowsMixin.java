package com.islesplusplus.mixin;

import com.islesplus.screen.islesscreen.FeatureRow;
import com.islesplus.ui.OverlayHost;
import com.islesplusplus.AddonRows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(targets = "com.islesplus.screen.islesscreen.Rows", remap = false)
public class RowsMixin {
    @Inject(method = "qol", at = @At("RETURN"))
    private static void islesplusplus$addRows(OverlayHost host, CallbackInfoReturnable<List<FeatureRow>> cir) {
        AddonRows.addTo(cir.getReturnValue());
    }
}
