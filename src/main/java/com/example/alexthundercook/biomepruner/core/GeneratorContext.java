package com.example.alexthundercook.biomepruner.core;

import com.example.alexthundercook.biomepruner.core.MinimalHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages access to chunk generators for height calculation
 */
public class GeneratorContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");

    // Map biome sources to their chunk generators
    private static final ConcurrentHashMap<BiomeSource, GeneratorInfo> GENERATORS = new ConcurrentHashMap<>();

    /**
     * Generator information
     */
    private record GeneratorInfo(
            NoiseBasedChunkGenerator generator,
            RandomState randomState
    ) {}

    /**
     * Register a chunk generator with its random state
     */
    public static void registerChunkGenerator(BiomeSource biomeSource,
                                              NoiseBasedChunkGenerator generator,
                                              RandomState randomState) {
        if (biomeSource != null && generator != null && randomState != null) {
            GENERATORS.put(biomeSource, new GeneratorInfo(generator, randomState));
            // Note: Debug logging reduced to info level as this only happens during initialization
            LOGGER.info("Registered chunk generator for biome source");
        }
    }

    /**
     * Get the generator info for a biome source
     */
    public static GeneratorInfo getGeneratorInfo(BiomeSource biomeSource) {
        return GENERATORS.get(biomeSource);
    }

    /**
     * Calculate the actual surface height at a position
     */
    public static int calculateSurfaceHeight(int blockX, int blockZ, BiomeSource biomeSource) {
        GeneratorInfo info = getGeneratorInfo(biomeSource);

        if (info == null) {
            // Note: Debug logging removed here as this could happen frequently during world generation
            // Fallback to approximate height
            return 64;
        }

        try {
            // Create a simple height accessor
            // In the context of biome generation, we don't have chunk access yet
            // So we use a minimal height accessor
            MinimalHeightAccessor accessor = new MinimalHeightAccessor();

            // Calculate actual surface height using noise
            // Parameters: blockX, blockZ, heightmap type, height accessor, random state
            return info.generator().getBaseHeight(
                    blockX, blockZ,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    accessor,
                    info.randomState()
            );
        } catch (Exception e) {
            // Note: Only log warnings for height calculation errors to avoid spam
            LOGGER.warn("Error calculating surface height at {}, {}: {}", blockX, blockZ, e.getMessage());
            // Fallback to approximate height
            return 64;
        }
    }
}