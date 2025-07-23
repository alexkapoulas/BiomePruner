package com.example.alexthundercook.biomepruner;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.config.BiomePrunerConfig;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.command.BiomePrunerCommands;
import com.example.alexthundercook.biomepruner.core.BiomeSmoother;
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

    /**
     * Clear caches when server is stopping to prevent cross-world contamination
     * This is CRITICAL for proper world switching behavior
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("BiomePruner: Server stopping, clearing all caches to prevent cross-world contamination");
        
        try {
            BiomeSmoother.getInstance().clearAllCaches();
            LOGGER.info("BiomePruner: Cache clearing completed successfully");
        } catch (Exception e) {
            LOGGER.error("BiomePruner: Error clearing caches during server stop", e);
        }
    }

    /**
     * Clear caches when a world is unloaded to prevent cross-world contamination
     * This handles cases where worlds are switched without full server restart
     */
    @SubscribeEvent
    public void onWorldUnload(LevelEvent.Unload event) {
        // Only clear caches for server-side world unloads to avoid clearing on client disconnect
        if (!event.getLevel().isClientSide()) {
            LOGGER.info("BiomePruner: World unloading, clearing all caches to prevent cross-world contamination");
            
            try {
                BiomeSmoother.getInstance().clearAllCaches();
                LOGGER.info("BiomePruner: Cache clearing completed successfully for world unload");
            } catch (Exception e) {
                LOGGER.error("BiomePruner: Error clearing caches during world unload", e);
            }
        }
    }
}