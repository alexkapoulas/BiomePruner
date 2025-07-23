package com.example.alexthundercook.biomepruner.test;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event handler for automated testing
 */
public class TestEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner-Test");
    private boolean testingStarted = false;
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("Server started, checking if automated testing should begin");
        
        // Give the server a moment to fully initialize
        // We'll start testing on the first tick
        testingStarted = false;
    }
    
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        // Start testing on first tick after server start
        if (!testingStarted) {
            testingStarted = true;
            AutomatedTestManager.getInstance().startTesting();
        }
    }
    
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Server stopping, shutting down test manager");
        AutomatedTestManager.getInstance().shutdown();
    }
}