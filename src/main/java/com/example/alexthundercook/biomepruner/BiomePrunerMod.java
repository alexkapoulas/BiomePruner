/* BiomePrunerMod.java */
package com.example.alexthundercook.biomepruner;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModLoadingContext;

@Mod(BiomePrunerMod.MODID)
public final class BiomePrunerMod {
    public static final String MODID = "biomepruner";

    public BiomePrunerMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register config early; nothing else is eagerly initialised.
        modContainer.registerConfig(ModConfig.Type.COMMON,
                BiomePrunerConfig.SPEC, MODID + "-common.toml");
    }

    // Nothing to do on common setup; everything else is lazy/on‑demand.
    public void onCommonSetup(final FMLCommonSetupEvent event) {}
}
