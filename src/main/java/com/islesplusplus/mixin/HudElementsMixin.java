package com.islesplusplus.mixin;

import com.islesplus.features.inventorynotifier.InventoryNotifier;
import com.islesplus.hud.HudElement;
import com.islesplus.hud.HudElements;
import com.islesplusplus.itempickup.ItemPickupLog;
import com.islesplusplus.map.IslesMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

// minimap + item pickups join the Isles+ HUD so the editor can move them
@Mixin(value = HudElements.class, remap = false)
public class HudElementsMixin {
    @Shadow private static List<HudElement> all;

    @Inject(method = "all", at = @At("RETURN"), cancellable = true)
    private static void islesplusplus$addElements(CallbackInfoReturnable<List<HudElement>> cir) {
        if (all.contains(IslesMap.ELEMENT)) return;
        List<HudElement> list = new ArrayList<>(all);
        list.add(0, IslesMap.ELEMENT);
        list.add(list.indexOf(InventoryNotifier.ELEMENT) + 1, ItemPickupLog.ELEMENT);   // or at the front
        all = List.copyOf(list);
        cir.setReturnValue(all);
    }
}
