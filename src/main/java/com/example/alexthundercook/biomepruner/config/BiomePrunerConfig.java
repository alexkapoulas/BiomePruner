package com.example.alexthundercook.biomepruner.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;

public class BiomePrunerConfig {
    public static final ModConfigSpec SPEC;
    public static final BiomePrunerConfig INSTANCE;

    static {
        Pair<BiomePrunerConfig, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(BiomePrunerConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // Configuration values
    public final ModConfigSpec.BooleanValue enabled;
    public final ModConfigSpec.IntValue microBiomeThreshold;
    public final ModConfigSpec.BooleanValue debug;
    public final ModConfigSpec.BooleanValue debugMessages;
    public final ModConfigSpec.BooleanValue performanceLogging;

    // Biome blacklists
    public final ModConfigSpec.ConfigValue<List<? extends String>> preservedBiomes;
    public final ModConfigSpec.ConfigValue<List<? extends String>> excludedAsReplacement;
    public final ModConfigSpec.BooleanValue preserveOceanMonuments;
    public final ModConfigSpec.BooleanValue preserveVillageBiomes;
    public final ModConfigSpec.ConfigValue<List<? extends String>> caveBiomes;

    // Performance settings
    public final ModConfigSpec.IntValue maxCacheMemoryMB;
    public final ModConfigSpec.IntValue maxActiveRegions;
    public final ModConfigSpec.BooleanValue enableWorkStealing;
    public final ModConfigSpec.BooleanValue cacheInterpolatedHeights;

    // Heightmap settings
    public final ModConfigSpec.IntValue gridSpacing;
    public final ModConfigSpec.BooleanValue useBicubicInterpolation;
    public final ModConfigSpec.BooleanValue opportunisticBatchCalculation;

    // Testing settings
    public final ModConfigSpec.BooleanValue automatedTestingEnabled;
    public final ModConfigSpec.BooleanValue biomeTestsEnabled;
    public final ModConfigSpec.BooleanValue performanceTestsEnabled;
    public final ModConfigSpec.ConfigValue<List<? extends String>> testCoordinates;
    public final ModConfigSpec.IntValue performanceTestDuration;
    public final ModConfigSpec.IntValue performanceTestSpeed;
    public final ModConfigSpec.ConfigValue<String> testResultsFile;

    private BiomePrunerConfig(ModConfigSpec.Builder builder) {
        builder.push("general");

        enabled = builder
                .comment("Master toggle for biome smoothing. Set to false to disable the mod completely.")
                .define("enabled", true);

        microBiomeThreshold = builder
                .comment("Biome size threshold. Biomes smaller than this are considered 'micro' and will be replaced.")
                .defineInRange("microBiomeThreshold", 50, 10, 1000);

        debug = builder
                .comment("Debug mode - logs when fallback logic is used")
                .define("debug", false);

        debugMessages = builder
                .comment("Show debug messages in chat when biomes are replaced")
                .define("debugMessages", false);

        performanceLogging = builder
                .comment("Enable performance metric collection (slight overhead)")
                .define("performanceLogging", false);

        builder.pop();

        builder.push("biome_blacklist");

        preservedBiomes = builder
                .comment("Biomes that should never be removed (always preserved)")
                .defineList("preservedBiomes",
                        Arrays.asList(
                                "minecraft:mushroom_fields",
                                "minecraft:ice_spikes",
                                "minecraft:flower_forest",
                                "minecraft:bamboo_jungle"
                        ),
                        obj -> obj instanceof String);

        excludedAsReplacement = builder
                .comment("Biomes that should never be used as replacements")
                .defineList("excludedAsReplacement",
                        Arrays.asList(
                                "minecraft:river",
                                "minecraft:frozen_river",
                                "minecraft:warm_ocean",
                                "minecraft:cold_ocean"
                        ),
                        obj -> obj instanceof String);

        preserveOceanMonuments = builder
                .comment("Treat ocean monuments specially - preserve small ocean patches containing them")
                .define("preserveOceanMonuments", true);

        preserveVillageBiomes = builder
                .comment("Treat village biomes specially - preserve small plains/desert/savanna/taiga/snowy patches")
                .define("preserveVillageBiomes", true);

        caveBiomes = builder
                .comment("Biomes considered to be caves/underground. When detected at surface level, " +
                         "sampling will continue upward to find the true surface biome.")
                .defineList("caveBiomes",
                        Arrays.asList(
                                // Vanilla cave biomes
                                "minecraft:deep_dark",
                                "minecraft:dripstone_caves",
                                "minecraft:lush_caves",
                                // Terralith cave biomes
                                "terralith:cave/andesite_caves",
                                "terralith:cave/desert_caves",
                                "terralith:cave/diorite_caves",
                                "terralith:cave/fungal_caves",
                                "terralith:cave/granite_caves",
                                "terralith:cave/ice_caves",
                                "terralith:cave/infested_caves",
                                "terralith:cave/thermal_caves",
                                "terralith:cave/underground_jungle",
                                // Terralith deep cave biomes
                                "terralith:cave/crystal_caves",
                                "terralith:cave/deep_caves",
                                "terralith:cave/frostfire_caves",
                                "terralith:cave/mantle_caves",
                                "terralith:cave/tuff_caves"
                        ),
                        obj -> obj instanceof String);

        builder.pop();

        builder.push("performance");

        maxCacheMemoryMB = builder
                .comment("Maximum memory usage for caches in MB")
                .defineInRange("maxCacheMemoryMB", 512, 64, 4096);

        maxActiveRegions = builder
                .comment("Number of regions to keep active in memory")
                .defineInRange("maxActiveRegions", 100, 10, 1000);

        enableWorkStealing = builder
                .comment("Enable work-stealing for collaborative flood fills")
                .define("enableWorkStealing", true);

        cacheInterpolatedHeights = builder
                .comment("Cache interpolated heightmap values (trades memory for speed)")
                .define("cacheInterpolatedHeights", false);

        builder.pop();

        builder.push("heightmap");

        gridSpacing = builder
                .comment("Grid spacing for heightmap sampling (smaller = more accurate but slower)")
                .defineInRange("gridSpacing", 16, 4, 64);

        useBicubicInterpolation = builder
                .comment("Use bicubic interpolation instead of bilinear (smoother but slower)")
                .define("useBicubicInterpolation", false);

        opportunisticBatchCalculation = builder
                .comment("Batch calculate nearby points when calculating one")
                .define("opportunisticBatchCalculation", true);

        builder.pop();

        builder.push("testing");

        automatedTestingEnabled = builder
                .comment("Enable automated testing on server startup")
                .define("automatedTestingEnabled", false);

        biomeTestsEnabled = builder
                .comment("Enable biome replacement verification tests")
                .define("biomeTestsEnabled", true);

        performanceTestsEnabled = builder
                .comment("Enable performance stress tests")
                .define("performanceTestsEnabled", true);

        testCoordinates = builder
                .comment("Test coordinates in format 'x,y,z,expectedBiome' (e.g., '1000,64,2000,minecraft:plains')")
                .defineList("testCoordinates",
                        Arrays.asList(
                                "10890,64,17394,biomesoplenty:hot_shrubland",
                                "10900,64,17400,biomesoplenty:hot_shrubland",
                                "10850,64,17350,biomesoplenty:hot_shrubland"
                        ),
                        obj -> obj instanceof String && ((String)obj).split(",").length == 4);

        performanceTestDuration = builder
                .comment("Duration in seconds for performance stress test")
                .defineInRange("performanceTestDuration", 60, 10, 300);

        performanceTestSpeed = builder
                .comment("Movement speed in blocks per second for performance test")
                .defineInRange("performanceTestSpeed", 10, 1, 50);

        testResultsFile = builder
                .comment("Output file path for test results (relative to game directory)")
                .define("testResultsFile", "biomepruner_test_results.json");

        builder.pop();
    }
}