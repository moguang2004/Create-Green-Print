package com.moguang.creategreenprint.mixin;

import com.moguang.creategreenprint.GreenPrintRequirementOverlay;
import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Create's overlay renderer intact while allowing Green Print to color missing counts. */
@Mixin(BlueprintOverlayRenderer.class)
abstract class BlueprintOverlayRendererMixin {
    @Inject(method = "renderOverlay", at = @At("HEAD"))
    private static void creategreenprint$prepareOverlay(GuiGraphics graphics, DeltaTracker deltaTracker,
                                                        CallbackInfo callback) {
        GreenPrintRequirementOverlay.prepareForCreateOverlay();
    }

    @Redirect(method = "renderOverlay", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/equipment/blueprint/BlueprintOverlayRenderer;drawItemStack("
                    + "Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/Minecraft;II"
                    + "Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;)V"))
    private static void creategreenprint$drawItemStack(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                                       ItemStack itemStack, String count) {
        BlueprintOverlayRenderer.drawItemStack(graphics, minecraft, x, y, itemStack,
                GreenPrintRequirementOverlay.countText(itemStack, count));
    }
}
