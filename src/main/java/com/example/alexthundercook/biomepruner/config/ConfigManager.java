package com.example.alexthundercook.biomepruner.config;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");

    // Processed biome sets for fast lookup
    private static final AtomicReference<Set<ResourceKey<Biome>>> preservedBiomes = new AtomicReference<>(new HashSet<>());
    private static final AtomicReference<Set<ResourceKey<Biome>>> excludedReplacements = new AtomicReference<>(new HashSet<>());
    private static final AtomicReference<Set<ResourceKey<Biome>>> caveBiomes = new AtomicReference<>(new HashSet<>());

    /**
     * Initialize configuration manager
     */
    public static void init() {
        updateBiomeSets();
        LOGGER.info("BiomePruner configuration manager initialized");
    }

    /**
     * Check if mod is enabled
     */
    public static boolean isEnabled() {
        return BiomePrunerConfig.INSTANCE.enabled.get();
    }

    /**
     * Check if debug messages are enabled
     */
    public static boolean isDebugMessagesEnabled() {
        return BiomePrunerConfig.INSTANCE.debugMessages.get();
    }

    /**
     * Check if performance logging is enabled
     */
    public static boolean isPerformanceLoggingEnabled() {
        return BiomePrunerConfig.INSTANCE.performanceLogging.get();
    }

    /**
     * Get micro biome threshold
     */
    public static int getMicroBiomeThreshold() {
        return BiomePrunerConfig.INSTANCE.microBiomeThreshold.get();
    }

    /**
     * Get max cache memory in MB
     */
    public static int getMaxCacheMemoryMB() {
        return BiomePrunerConfig.INSTANCE.maxCacheMemoryMB.get();
    }

    /**
     * Get grid spacing for heightmap
     */
    public static int getGridSpacing() {
        return BiomePrunerConfig.INSTANCE.gridSpacing.get();
    }

    /**
     * Check if a biome should be preserved
     */
    public static boolean shouldPreserveBiome(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return false;
        }

        // Check explicit preservation list
        Optional<ResourceKey<Biome>> biomeKey = biomeHolder.unwrapKey();
        if (biomeKey.isPresent() && preservedBiomes.get().contains(biomeKey.get())) {
            return true;
        }

        // Check special preservation rules
        if (BiomePrunerConfig.INSTANCE.preserveVillageBiomes.get()) {
            // Check if biome typically has villages (reuse existing biomeKey)
            if (biomeKey.isPresent()) {
                String biomeName = biomeKey.get().location().getPath();
                if (biomeName.contains("plains") || biomeName.contains("desert") ||
                        biomeName.contains("savanna") || biomeName.contains("taiga") ||
                        biomeName.contains("snowy")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if a biome can be used as replacement
     */
    public static boolean canUseAsReplacement(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return true;
        }

        Optional<ResourceKey<Biome>> biomeKey = biomeHolder.unwrapKey();
        return biomeKey.isEmpty() || !excludedReplacements.get().contains(biomeKey.get());
    }

    /**
     * Check if a biome is considered a cave biome
     */
    public static boolean isCaveBiome(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return false;
        }

        Optional<ResourceKey<Biome>> biomeKey = biomeHolder.unwrapKey();
        return biomeKey.isPresent() && caveBiomes.get().contains(biomeKey.get());
    }

    /**
     * Update processed biome sets from config
     */
    private static void updateBiomeSets() {
        // Parse preserved biomes
        Set<ResourceKey<Biome>> preserved = new HashSet<>();
        for (String biomeId : BiomePrunerConfig.INSTANCE.preservedBiomes.get()) {
            try {
                ResourceLocation id = ResourceLocation.parse(biomeId);
                ResourceKey<Biome> biomeKey = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
                preserved.add(biomeKey);
            } catch (Exception e) {
                LOGGER.error("Invalid biome identifier: {}", biomeId, e);
            }
        }
        preservedBiomes.set(preserved);

        // Parse excluded replacements
        Set<ResourceKey<Biome>> excluded = new HashSet<>();
        for (String biomeId : BiomePrunerConfig.INSTANCE.excludedAsReplacement.get()) {
            try {
                ResourceLocation id = ResourceLocation.parse(biomeId);
                ResourceKey<Biome> biomeKey = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
                excluded.add(biomeKey);
            } catch (Exception e) {
                LOGGER.error("Invalid biome identifier: {}", biomeId, e);
            }
        }
        excludedReplacements.set(excluded);

        // Parse cave biomes
        Set<ResourceKey<Biome>> caves = new HashSet<>();
        for (String biomeId : BiomePrunerConfig.INSTANCE.caveBiomes.get()) {
            try {
                ResourceLocation id = ResourceLocation.parse(biomeId);
                ResourceKey<Biome> biomeKey = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
                caves.add(biomeKey);
            } catch (Exception e) {
                LOGGER.error("Invalid cave biome identifier: {}", biomeId, e);
            }
        }
        caveBiomes.set(caves);

        LOGGER.info("Loaded {} preserved biomes, {} excluded replacements, {} cave biomes",
                preserved.size(), excluded.size(), caves.size());
    }

    // Testing configuration methods
    public static boolean isAutomatedTestingEnabled() {
        return BiomePrunerConfig.INSTANCE.automatedTestingEnabled.get();
    }

    public static boolean areBiomeTestsEnabled() {
        return BiomePrunerConfig.INSTANCE.biomeTestsEnabled.get();
    }

    public static boolean arePerformanceTestsEnabled() {
        return BiomePrunerConfig.INSTANCE.performanceTestsEnabled.get();
    }

    public static List<? extends String> getTestCoordinates() {
        return BiomePrunerConfig.INSTANCE.testCoordinates.get();
    }

    public static int getPerformanceTestDuration() {
        return BiomePrunerConfig.INSTANCE.performanceTestDuration.get();
    }

    public static int getPerformanceTestSpeed() {
        return BiomePrunerConfig.INSTANCE.performanceTestSpeed.get();
    }

    public static String getTestResultsFile() {
        return BiomePrunerConfig.INSTANCE.testResultsFile.get();
    }

}