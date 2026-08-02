package com.moguang.creategreenprint;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SpriteShifter;
import net.minecraft.resources.ResourceLocation;

/** Partial models for the Green Print base and its connected border layer. */
public final class GreenPrintPartialModels {
    public static final PartialModel BASE = model("entity/crafting_greenprint_base");
    public static final PartialModel CONNECTED = model("entity/crafting_greenprint_connected");
    public static final PartialModel SMALL = model("entity/crafting_greenprint_small");
    public static final PartialModel MEDIUM = model("entity/crafting_greenprint_medium");
    public static final PartialModel LARGE = model("entity/crafting_greenprint_large");
    public static final SpriteShiftEntry CONNECTION_SHEET = SpriteShifter.get(
            texture("entity/greenprint_small"),
            texture("entity/greenprint_small_connected"));

    private GreenPrintPartialModels() {
    }

    private static PartialModel model(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CreateGreenPrint.MODID, path));
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateGreenPrint.MODID, path);
    }
}
