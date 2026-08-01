package com.moguang.creategreenprint;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.minecraft.client.resources.model.ModelResourceLocation;

/** Client-only registration for the Create-styled Green Print editor. */
@Mod(value = CreateGreenPrint.MODID, dist = Dist.CLIENT)
public final class CreateGreenPrintClient {
    public CreateGreenPrintClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerAdditionalModels);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CreateGreenPrint.GREEN_PRINT_ENTITY.get(), GreenPrintRenderer::new);
    }

    private void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(ModelResourceLocation.standalone(GreenPrintPartialModels.BASE.modelLocation()));
        event.register(ModelResourceLocation.standalone(GreenPrintPartialModels.CONNECTED.modelLocation()));
    }
}
