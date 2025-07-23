package com.example.alexthundercook.biomepruner.core;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Contains analysis data for a specific position's biome
 */
public record BiomeAnalysis(
        int x,
        int y,
        int z,
        int surfaceY,
        Holder<Biome> vanillaBiome,
        String vanillaBiomeName,
        Holder<Biome> surfaceBiome,
        String surfaceBiomeName,
        Holder<Biome> replacementBiome,
        String replacementBiomeName,
        int regionSize,
        boolean isMicroBiome,
        boolean isPreserved,
        boolean matchesSurface,
        boolean fromCache
) {
    /**
     * Create a simple analysis for preserved or non-matching biomes
     */
    public static BiomeAnalysis simple(int x, int y, int z, int surfaceY,
                                       Holder<Biome> vanillaBiome, String vanillaBiomeName,
                                       Holder<Biome> surfaceBiome, String surfaceBiomeName,
                                       boolean isPreserved, boolean matchesSurface) {
        return new BiomeAnalysis(
                x, y, z, surfaceY,
                vanillaBiome, vanillaBiomeName,
                surfaceBiome, surfaceBiomeName,
                null, null,
                -1, false,
                isPreserved, matchesSurface,
                false
        );
    }
}