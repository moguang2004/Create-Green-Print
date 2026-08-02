package com.moguang.creategreenprint;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(CreateGreenPrint.MODID)
public class CreateGreenPrint {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "creategreenprint";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<GreenPrintItem> GREEN_PRINT = ITEMS.register("green_print",
            () -> new GreenPrintItem(new net.minecraft.world.item.Item.Properties().stacksTo(64)));
    public static final RegistryObject<EntityType<GreenPrintEntity>> GREEN_PRINT_ENTITY = ENTITY_TYPES.register("green_print",
            () -> EntityType.Builder.<GreenPrintEntity>of((type, level) -> new GreenPrintEntity(type, level), MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(MODID + ":green_print"));

    public static final RegistryObject<CreativeModeTab> GREEN_PRINT_TAB = CREATIVE_MODE_TABS.register("green_print", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.creategreenprint")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> GREEN_PRINT.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(GREEN_PRINT.get());
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    public CreateGreenPrint() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);

    }
}
