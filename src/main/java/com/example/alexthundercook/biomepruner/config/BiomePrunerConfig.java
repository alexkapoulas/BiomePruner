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

    // Performance settings
    public final ModConfigSpec.IntValue maxCacheMemoryMB;
    public final ModConfigSpec.IntValue maxActiveRegions;
    public final ModConfigSpec.BooleanValue enableWorkStealing;
    public final ModConfigSpec.BooleanValue cacheInterpolatedHeights;

    // Heightmap settings
    public final ModConfigSpec.IntValue gridSpacing;
    public final ModConfigSpec.BooleanValue useBicubicInterpolation;
    public final ModConfigSpec.BooleanValue opportunisticBatchCalculation;

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
    }
}