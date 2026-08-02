package com.moguang.creategreenprint;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only registration for the Create-styled Green Print editor. */
@Mod.EventBusSubscriber(modid = CreateGreenPrint.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CreateGreenPrintClient {
    private CreateGreenPrintClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CreateGreenPrint.GREEN_PRINT_ENTITY.get(), GreenPrintRenderer::new);
    }

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(GreenPrintPartialModels.BASE.modelLocation());
        event.register(GreenPrintPartialModels.CONNECTED.modelLocation());
    }

    @SubscribeEvent
    public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "green_print", GreenPrintRequirementOverlay.OVERLAY);
    }
}
