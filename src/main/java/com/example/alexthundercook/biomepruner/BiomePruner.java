package com.example.alexthundercook.biomepruner;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.config.BiomePrunerConfig;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.command.BiomePrunerCommands;
import com.example.alexthundercook.biomepruner.test.TestEventHandler;

@Mod(BiomePruner.MOD_ID)
public class BiomePruner {
    public static final String MOD_ID = "biomepruner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public BiomePruner(IEventBus modEventBus, ModContainer modContainer) {
        // Register configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, BiomePrunerConfig.SPEC);

        // Register mod lifecycle events
        modEventBus.addListener(this::commonSetup);

        // Register to the game event bus for command registration and testing
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new TestEventHandler());

        LOGGER.info("BiomePruner initialized");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Initialize configuration manager
            ConfigManager.init();
            LOGGER.info("BiomePruner configuration loaded");
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BiomePrunerCommands.register(event.getDispatcher());
        LOGGER.info("BiomePruner commands registered");
    }
}