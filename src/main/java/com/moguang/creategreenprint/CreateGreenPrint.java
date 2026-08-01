package com.moguang.creategreenprint;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CreateGreenPrint.MODID)
public class CreateGreenPrint {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "creategreenprint";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredItem<GreenPrintItem> GREEN_PRINT = ITEMS.register("green_print",
            () -> new GreenPrintItem(new net.minecraft.world.item.Item.Properties().stacksTo(64)));
    public static final DeferredHolder<EntityType<?>, EntityType<GreenPrintEntity>> GREEN_PRINT_ENTITY = ENTITY_TYPES.register("green_print",
            () -> EntityType.Builder.<GreenPrintEntity>of((type, level) -> new GreenPrintEntity(type, level), MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(MODID + ":green_print"));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GREEN_PRINT_TAB = CREATIVE_MODE_TABS.register("green_print", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.creategreenprint")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> GREEN_PRINT.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(GREEN_PRINT.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CreateGreenPrint(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);

    }
}
