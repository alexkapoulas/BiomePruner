package com.example.alexthundercook.biomepruner.core;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Interface for accessing chunk generator internals via mixin
 */
public interface ChunkGeneratorAccess {
    /**
     * Get the random state from the chunk generator
     */
    RandomState biomepruner$getRandomState();

    /**
     * Get the climate sampler from the chunk generator
     */
    Climate.Sampler biomepruner$getClimateSampler();

    /**
     * Set the random state in the chunk generator
     */
    void biomepruner$setRandomState(RandomState randomState);
}