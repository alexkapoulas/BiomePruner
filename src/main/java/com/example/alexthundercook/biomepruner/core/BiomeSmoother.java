package com.example.alexthundercook.biomepruner.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.cache.BiomeRegionCache;
import com.example.alexthundercook.biomepruner.cache.HeightmapCache;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.debug.DebugMessenger;
import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;
import com.example.alexthundercook.biomepruner.util.Pos2D;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BiomeSmoother {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");
    private static final BiomeSmoother INSTANCE = new BiomeSmoother();

    private final BiomeRegionCache regionCache;
    private final HeightmapCache heightmapCache;

    private BiomeSmoother() {
        this.regionCache = BiomeRegionCache.getInstance();
        this.heightmapCache = new HeightmapCache();
    }

    public static BiomeSmoother getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize the heightmap cache with a biome source
     */
    public void initializeForBiomeSource(BiomeSource source) {
        heightmapCache.setBiomeSource(source);
    }

    /**
     * Get the modified biome for a position - MUST be deterministic
     *
     * This method ALWAYS returns a valid biome:
     * - If the position should not be modified, returns vanillaBiome
     * - If the position is in a micro biome, returns the replacement biome
     * - Never returns null, never defers processing
     *
     * @param vanillaBiome The biome that vanilla would have returned
     * @return The correct biome for this position (never null)
     */
    public Holder<Biome> getModifiedBiome(int x, int y, int z,
                                          Holder<Biome> vanillaBiome,
                                          MultiNoiseBiomeSource source,
                                          Climate.Sampler sampler) {
        // Start performance tracking if enabled
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;
        PerformanceTracker.Timer totalTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.TOTAL) : null;

        try {
            // Note: Debug logging removed here to prevent log spam since this method 
            // is called very frequently during world generation

            // Check if this biome should be preserved
            if (ConfigManager.shouldPreserveBiome(vanillaBiome)) {
                return vanillaBiome;
            }

            // Use the cache's atomic get-or-compute operation
            BiomeRegionCache.BiomeResult result = regionCache.getOrComputeBiome(x, y, z, vanillaBiome,
                    pos -> computeBiomeResult(x, y, z, vanillaBiome, source, sampler));

            // Note: Debug logging removed here to prevent log spam during biome generation

            return result.biome();
        } finally {
            if (totalTimer != null) {
                totalTimer.close();
            }
        }
    }

    /**
     * Compute the biome result for a position
     * This method is called within a lock, so it must be deterministic
     */
    private BiomeRegionCache.BiomeResult computeBiomeResult(int x, int y, int z,
                                                            Holder<Biome> vanillaBiome,
                                                            MultiNoiseBiomeSource source,
                                                            Climate.Sampler sampler) {
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;

        // Get surface height
        PerformanceTracker.Timer heightTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.HEIGHT_CALC) : null;
        int surfaceY = heightmapCache.getHeight(x, z);
        if (heightTimer != null) heightTimer.close();

        // Get biome at surface position, skipping cave biomes
        Holder<Biome> surfaceBiome = getSurfaceBiome(x, z, surfaceY, source, sampler);

        // Only process if vanilla biome matches surface biome
        if (!vanillaBiome.equals(surfaceBiome)) {
            // Biome differs from surface, don't modify
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }

        // Check if we already know this is a large biome
        if (regionCache.isKnownLargeBiome(x, z, surfaceBiome)) {
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }

        // Get or start flood fill task
        BiomeRegionCache.FloodFillTask task = regionCache.getOrStartFloodFill(x, z, surfaceBiome);

        // Check if another thread already completed it
        BiomeRegionCache.FloodFillResult existingResult = task.getResult();
        if (existingResult != null) {
            if (existingResult.isLarge()) {
                return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            } else {
                // Send debug message if enabled
                if (ConfigManager.isDebugMessagesEnabled() && existingResult.positions() != null) {
                    DebugMessenger.getInstance().sendBiomeReplacementMessage(
                            x, y, z, vanillaBiome, existingResult.replacement(), existingResult.positions());
                }
                return new BiomeRegionCache.BiomeResult(existingResult.replacement(), true);
            }
        }

        // We need to run the flood fill
        try {
            runFloodFillAndComplete(x, surfaceY, z, surfaceBiome, source, sampler, task);

            // Wait for completion (with timeout to prevent hanging)
            BiomeRegionCache.FloodFillResult result = task.getFuture().get(5, TimeUnit.SECONDS);

            if (result.isLarge()) {
                return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            } else {
                // Send debug message if enabled
                if (ConfigManager.isDebugMessagesEnabled() && result.positions() != null) {
                    DebugMessenger.getInstance().sendBiomeReplacementMessage(
                            x, y, z, vanillaBiome, result.replacement(), result.positions());
                }
                return new BiomeRegionCache.BiomeResult(result.replacement(), true);
            }

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Error in flood fill computation at {},{},{}", x, y, z, e);
            // On error, return vanilla biome
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }
    }

    /**
     * Run flood fill and complete the task
     */
    private void runFloodFillAndComplete(int startX, int startY, int startZ,
                                         Holder<Biome> targetBiome,
                                         MultiNoiseBiomeSource source,
                                         Climate.Sampler sampler,
                                         BiomeRegionCache.FloodFillTask task) {
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;
        PerformanceTracker.Timer floodFillTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.FLOOD_FILL) : null;

        int threshold = ConfigManager.getMicroBiomeThreshold();
        Set<Pos2D> visited = new HashSet<>();
        Queue<Pos2D> queue = new ArrayDeque<>();

        try {
            Pos2D start = new Pos2D(startX, startZ);
            queue.offer(start);
            visited.add(start);

            // Flood fill on 2D surface
            while (!queue.isEmpty() && visited.size() <= threshold) {
                Pos2D current = queue.poll();

                // Check 4 cardinal neighbors
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx == 0) == (dz == 0)) continue; // Skip center and diagonals

                        Pos2D neighbor = new Pos2D(current.x() + dx, current.z() + dz);

                        if (!visited.contains(neighbor)) {
                            // Get biome at neighbor surface position, skipping cave biomes
                            int neighborY = heightmapCache.getHeight(neighbor.x(), neighbor.z());
                            Holder<Biome> neighborBiome = getSurfaceBiome(
                                    neighbor.x(), neighbor.z(), neighborY, source, sampler);

                            if (neighborBiome.equals(targetBiome)) {
                                visited.add(neighbor);
                                queue.offer(neighbor);

                                // Early exit if we exceed threshold
                                if (visited.size() > threshold) {
                                    // Large biome
                                    regionCache.completeFloodFill(startX, startZ, targetBiome,
                                            Collections.emptySet(), true, targetBiome);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (floodFillTimer != null) floodFillTimer.close();
        }

        // Micro biome - find replacement
        PerformanceTracker.Timer neighborTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.NEIGHBOR_SEARCH) : null;
        Holder<Biome> replacement;
        try {
            replacement = findDominantNeighbor(visited, targetBiome, source, sampler);
        } finally {
            if (neighborTimer != null) neighborTimer.close();
        }

        regionCache.completeFloodFill(startX, startZ, targetBiome, visited, false, replacement);
    }

    /**
     * Find the dominant neighboring biome
     */
    private Holder<Biome> findDominantNeighbor(Set<Pos2D> microBiomePositions,
                                               Holder<Biome> originalBiome,
                                               MultiNoiseBiomeSource source,
                                               Climate.Sampler sampler) {
        Map<Holder<Biome>, Integer> validNeighborCounts = new HashMap<>();
        Map<Holder<Biome>, Integer> allNeighborCounts = new HashMap<>();

        // Check perimeter of micro biome
        for (Pos2D pos : microBiomePositions) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0) == (dz == 0)) continue; // Skip center and diagonals

                    Pos2D neighbor = new Pos2D(pos.x() + dx, pos.z() + dz);

                    if (!microBiomePositions.contains(neighbor)) {
                        int height = heightmapCache.getHeight(neighbor.x(), neighbor.z());
                        Holder<Biome> neighborBiome = getSurfaceBiome(
                                neighbor.x(), neighbor.z(), height, source, sampler);

                        // Always count for fallback
                        allNeighborCounts.merge(neighborBiome, 1, Integer::sum);

                        // Only count valid replacements for primary selection
                        if (ConfigManager.canUseAsReplacement(neighborBiome)) {
                            validNeighborCounts.merge(neighborBiome, 1, Integer::sum);
                        }
                    }
                }
            }
        }

        // First try: Find most common valid neighbor
        Optional<Holder<Biome>> validChoice = validNeighborCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);

        if (validChoice.isPresent()) {
            return validChoice.get();
        }

        // Fallback: All neighbors are blacklisted, use dominant anyway
        return allNeighborCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(originalBiome);
    }

    /**
     * Analyze biome with context for debugging commands
     * Returns detailed analysis with actual region size calculation
     */
    public BiomeAnalysis analyzeBiomeWithContext(int x, int y, int z, 
                                                Holder<Biome> vanillaBiome,
                                                MultiNoiseBiomeSource source, 
                                                Climate.Sampler sampler) {
        // Get biome names
        String vanillaBiomeName = getBiomeName(vanillaBiome);
        
        // Get surface height
        int surfaceY = heightmapCache.getHeight(x, z);
        
        // Get surface biome, skipping cave biomes
        Holder<Biome> surfaceBiome = getSurfaceBiome(x, z, surfaceY, source, sampler);
        String surfaceBiomeName = getBiomeName(surfaceBiome);
        
        boolean matchesSurface = vanillaBiome.equals(surfaceBiome);
        boolean isPreserved = ConfigManager.shouldPreserveBiome(vanillaBiome);
        
        if (isPreserved || !matchesSurface) {
            return BiomeAnalysis.simple(x, y, z, surfaceY, vanillaBiome, vanillaBiomeName,
                    surfaceBiome, surfaceBiomeName, isPreserved, matchesSurface);
        }
        
        // Check cache first for existing results
        BiomeRegionCache.FloodFillTask existingTask = regionCache.getExistingFloodFill(x, z, surfaceBiome);
        if (existingTask != null) {
            BiomeRegionCache.FloodFillResult existingResult = existingTask.getResult();
            if (existingResult != null) {
                // We have cached results
                String replacementName = existingResult.replacement() != null ? 
                    getBiomeName(existingResult.replacement()) : null;
                int regionSize = existingResult.positions() != null ? 
                    existingResult.positions().size() : (existingResult.isLarge() ? Integer.MAX_VALUE : -1);
                
                return new BiomeAnalysis(
                    x, y, z, surfaceY,
                    vanillaBiome, vanillaBiomeName,
                    surfaceBiome, surfaceBiomeName,
                    existingResult.replacement(), replacementName,
                    regionSize, !existingResult.isLarge(),
                    false, true, true
                );
            }
        }
        
        // Perform fresh analysis for debug command
        DebugFloodFillResult debugResult = performDebugFloodFill(x, surfaceY, z, surfaceBiome, source, sampler);
        
        String replacementName = debugResult.replacement() != null ? 
            getBiomeName(debugResult.replacement()) : null;
        
        return new BiomeAnalysis(
            x, y, z, surfaceY,
            vanillaBiome, vanillaBiomeName,
            surfaceBiome, surfaceBiomeName,
            debugResult.replacement(), replacementName,
            debugResult.regionSize(), debugResult.isMicroBiome(),
            false, true, false
        );
    }

    /**
     * Get display name for a biome holder
     */
    private String getBiomeName(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return "Unknown";
        }
        
        Optional<ResourceKey<Biome>> biomeKey = biomeHolder.unwrapKey();
        if (biomeKey.isPresent()) {
            String name = biomeKey.get().location().getPath();
            return capitalizeWords(name.replace('_', ' '));
        }
        
        return "Unknown";
    }
    
    /**
     * Capitalize words in a string
     */
    private String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char ch : input.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                result.append(ch);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(ch));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(ch));
            }
        }
        
        return result.toString();
    }

    /**
     * Get original biome at block coordinates
     * This method is safe to call from within our mixin context
     */
    private Holder<Biome> getOriginalBiome(int blockX, int blockY, int blockZ,
                                           MultiNoiseBiomeSource source,
                                           Climate.Sampler sampler) {
        // Convert block coordinates to noise coordinates (divide by 4)
        return source.getNoiseBiome(blockX >> 2, blockY >> 2, blockZ >> 2, sampler);
    }

    // Rate limiting for cave biome detection logging
    private static volatile boolean hasLoggedCaveSkip = false;

    /**
     * Get surface biome at block coordinates, skipping cave biomes
     * If a cave biome is found, keep sampling upward until a non-cave biome is found
     */
    private Holder<Biome> getSurfaceBiome(int blockX, int blockZ, int startY,
                                          MultiNoiseBiomeSource source,
                                          Climate.Sampler sampler) {
        int currentY = startY;
        int maxY = 320; // World height limit
        int maxSamples = 20; // Prevent infinite loops
        int samples = 0;
        boolean foundCave = false;

        while (currentY <= maxY && samples < maxSamples) {
            Holder<Biome> biome = getOriginalBiome(blockX, currentY, blockZ, source, sampler);
            
            // If it's not a cave biome, we found our surface biome
            if (!ConfigManager.isCaveBiome(biome)) {
                // Log first successful cave skip (rate limited)
                if (foundCave && !hasLoggedCaveSkip && ConfigManager.isPerformanceLoggingEnabled()) {
                    hasLoggedCaveSkip = true;
                    LOGGER.info("BiomePruner: Skipped cave biome at surface, found {} at Y={} (started at Y={})", 
                        getBiomeName(biome), currentY, startY);
                }
                return biome;
            }
            
            foundCave = true;
            // Move up and try again
            currentY += 8; // Sample every 8 blocks upward
            samples++;
        }
        
        // Fallback: return the biome at the original height if we couldn't find a non-cave biome
        return getOriginalBiome(blockX, startY, blockZ, source, sampler);
    }

    /**
     * Result of debug flood fill analysis
     */
    private record DebugFloodFillResult(
        int regionSize,
        boolean isMicroBiome,
        Holder<Biome> replacement
    ) {}

    /**
     * Perform flood fill analysis for debug purposes
     * This is more expensive but provides exact region size information
     */
    private DebugFloodFillResult performDebugFloodFill(int startX, int startY, int startZ,
                                                       Holder<Biome> targetBiome,
                                                       MultiNoiseBiomeSource source,
                                                       Climate.Sampler sampler) {
        int threshold = ConfigManager.getMicroBiomeThreshold();
        Set<Pos2D> visited = new HashSet<>();
        Queue<Pos2D> queue = new ArrayDeque<>();

        Pos2D start = new Pos2D(startX, startZ);
        queue.offer(start);
        visited.add(start);

        // Flood fill on 2D surface
        while (!queue.isEmpty() && visited.size() <= threshold) {
            Pos2D current = queue.poll();

            // Check 4 cardinal neighbors
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0) == (dz == 0)) continue; // Skip center and diagonals

                    Pos2D neighbor = new Pos2D(current.x() + dx, current.z() + dz);

                    if (!visited.contains(neighbor)) {
                        // Get biome at neighbor surface position, skipping cave biomes
                        int neighborY = heightmapCache.getHeight(neighbor.x(), neighbor.z());
                        Holder<Biome> neighborBiome = getSurfaceBiome(
                                neighbor.x(), neighbor.z(), neighborY, source, sampler);

                        if (neighborBiome.equals(targetBiome)) {
                            visited.add(neighbor);
                            queue.offer(neighbor);

                            // Early exit if we exceed threshold
                            if (visited.size() > threshold) {
                                // Large biome
                                return new DebugFloodFillResult(visited.size(), false, null);
                            }
                        }
                    }
                }
            }
        }

        // Micro biome - find replacement
        Holder<Biome> replacement = findDominantNeighbor(visited, targetBiome, source, sampler);
        return new DebugFloodFillResult(visited.size(), true, replacement);
    }
}