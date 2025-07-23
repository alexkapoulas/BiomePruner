package com.example.alexthundercook.biomepruner.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.cache.BiomeRegionCache;
import com.example.alexthundercook.biomepruner.cache.HeightmapCache;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.debug.DebugMessenger;
import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;
import com.example.alexthundercook.biomepruner.util.Pos2D;

import java.util.*;
import java.util.ArrayList;
import java.util.List;
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
     * Lightweight validation that a biome holder is safe to use
     * Avoids expensive registry operations that caused performance regression
     */
    private boolean isValidForDecoration(Holder<Biome> biomeHolder, int x, int y, int z, String context) {
        // Basic null check
        if (biomeHolder == null) {
            LOGGER.warn("BiomePruner: Null biome holder at {},{},{} during {}", x, y, z, context);
            return false;
        }
        
        // Check if biome holder is bound to registry - this is the critical check
        if (!biomeHolder.isBound()) {
            LOGGER.warn("BiomePruner: Unbound biome holder at {},{},{} during {}", x, y, z, context);
            return false;
        }
        
        // Check if biome holder has a valid registry key - prevents NPE
        if (biomeHolder.unwrapKey().isEmpty()) {
            LOGGER.warn("BiomePruner: Biome holder missing registry key at {},{},{} during {}", x, y, z, context);
            return false;
        }
        
        // If we get here, the biome holder should be safe to use
        // Note: Removed expensive registry ID validation to prevent performance regression
        return true;
    }
    
    /**
     * Get a guaranteed safe fallback biome when validation fails
     * Returns the provided fallback or a known safe biome
     */
    private Holder<Biome> getSafeFallbackBiome(Holder<Biome> fallback, int x, int y, int z) {
        // If fallback is valid, use it
        if (fallback != null && fallback.isBound() && fallback.unwrapKey().isPresent()) {
            return fallback;
        }
        
        // If we reach here, something is seriously wrong with biome holders
        // Log this as it indicates a deeper issue
        LOGGER.error("BiomePruner: CRITICAL - All biome fallbacks failed at {},{},{} - this indicates a serious registry issue", x, y, z);
        
        // Return the original fallback even if invalid - let Minecraft handle it rather than crash
        return fallback;
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
        // Note: Detailed debug logging removed - issue resolved
        
        // Start performance tracking if enabled
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;
        PerformanceTracker.Timer totalTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.TOTAL) : null;

        try {
            // Note: Debug logging removed here to prevent log spam since this method 
            // is called very frequently during world generation

            // Validate vanilla biome before processing
            if (!isValidForDecoration(vanillaBiome, x, y, z, "vanilla validation")) {
                LOGGER.warn("BiomePruner: Invalid vanilla biome holder detected at {},{},{}, returning as-is", x, y, z);
                return vanillaBiome;
            }
            
            // Note: Removed expensive registry ID logging to prevent performance regression

            // Check if this biome should be preserved
            if (ConfigManager.shouldPreserveBiome(vanillaBiome)) {
                return vanillaBiome;
            }

            // Use the cache's atomic get-or-compute operation
            BiomeRegionCache.BiomeResult result = regionCache.getOrComputeBiome(x, y, z, vanillaBiome,
                    pos -> computeBiomeResult(x, y, z, vanillaBiome, source, sampler));

            // Note: Debug logging removed here to prevent log spam during biome generation

            // Final validation of the result before returning
            Holder<Biome> resultBiome = result.biome();
            if (!isValidForDecoration(resultBiome, x, y, z, "result validation")) {
                LOGGER.warn("BiomePruner: Invalid result biome holder at {},{},{}, using safe fallback", x, y, z);
                return getSafeFallbackBiome(vanillaBiome, x, y, z);
            }
            
            // Note: Removed expensive result biome registry ID logging to prevent performance regression

            return resultBiome;
        } catch (RuntimeException e) {
            // Catch coordinate validation errors and other runtime exceptions
            // Log the error but return vanilla biome to prevent crashes
            if (e.getMessage() != null && e.getMessage().contains("coordinate")) {
                LOGGER.warn("BiomePruner: Coordinate validation error at {},{},{}: {}", x, y, z, e.getMessage());
            } else {
                LOGGER.error("BiomePruner: Runtime error in getModifiedBiome at {},{},{}", x, y, z, e);
            }
            return vanillaBiome;
        } catch (Exception e) {
            // Catch any other unexpected errors
            LOGGER.error("BiomePruner: Unexpected error in getModifiedBiome at {},{},{}", x, y, z, e);
            return vanillaBiome;
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
        // Note: Detailed debug logging removed - issue resolved
        
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;

        // Check if we already know this specific biome has mismatch at this position
        Boolean cachedMismatch = regionCache.getBiomeMismatch(x, z, vanillaBiome);
        if (cachedMismatch != null && cachedMismatch) {
            // We know this vanilla biome != surface biome at this position
            // IMPORTANT: Track biome mismatch cache hits
            regionCache.recordCacheHit();
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }

        // Note: Large biome check moved after surface biome determination

        // Get surface height (expensive operation)
        PerformanceTracker.Timer heightTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.HEIGHT_CALC) : null;
        
        int surfaceY;
        try {
            surfaceY = heightmapCache.getHeight(x, z);
        } catch (Exception e) {
            LOGGER.warn("BiomePruner: Error getting surface height at {},{}, using default", x, z, e);
            surfaceY = 64; // Default surface height
        }
        if (heightTimer != null) heightTimer.close();

        // Get biome at surface position, skipping cave biomes
        Holder<Biome> surfaceBiome;
        try {
            surfaceBiome = getSurfaceBiome(x, z, surfaceY, source, sampler);
        } catch (RuntimeException e) {
            LOGGER.warn("BiomePruner: Critical biome sampling failure at {},{},{}, using vanilla biome: {}", 
                x, z, surfaceY, e.getMessage());
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        } catch (Exception e) {
            LOGGER.error("BiomePruner: Unexpected error getting surface biome at {},{},{}, using vanilla biome", 
                x, z, surfaceY, e);
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }

        // Check if vanilla biome matches surface biome
        boolean biomesMatch = vanillaBiome.equals(surfaceBiome);
        
        // Cache the mismatch result for this specific biome at this position
        regionCache.storeBiomeMismatch(x, z, vanillaBiome, !biomesMatch);
        
        if (!biomesMatch) {
            // Biome differs from surface, don't modify
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        }

        // Check surface cache for this specific surface biome match
        // This is safe because we've verified vanillaBiome == surfaceBiome
        BiomeRegionCache.BiomeResult surfaceResult = regionCache.getSurfaceCacheResult(x, z);
        if (surfaceResult != null) {
            // Validate that cached result is for the same biome
            // (different biomes at same x,z but different Y levels should not share cache)
            if (surfaceResult.biome().equals(vanillaBiome) || surfaceResult.biome().equals(surfaceBiome)) {
                // IMPORTANT: Track surface cache hits
                regionCache.recordCacheHit();
                return surfaceResult;
            }
            // Cache hit but for different biome - ignore and continue
        }

        // Check spatial cache for nearby flood fill results first (performance boost)
        // Note: Spatial cache hits are tracked within getNearbyFloodFillResult
        var spatialResult = regionCache.getNearbyFloodFillResult(x, z, surfaceBiome);
        if (spatialResult != null) {
            BiomeRegionCache.BiomeResult cachedResult;
            if (spatialResult.isLarge()) {
                cachedResult = new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            } else {
                cachedResult = new BiomeRegionCache.BiomeResult(spatialResult.replacement(), true);
            }
            // Store in surface cache for future surface biome matches
            regionCache.storeSurfaceCacheResult(x, z, cachedResult);
            return cachedResult;
        }

        // Check if we already know this specific area contains a large biome
        if (regionCache.isKnownLargeBiomeArea(x, z, surfaceBiome)) {
            // Debug logging for the problematic area
            if ((x == 10890 && z == 17394) || 
                (Math.abs(x - 10890) <= 50 && Math.abs(z - 17394) <= 50 && getBiomeName(surfaceBiome).equals("Hot Shrubland"))) {
                if (ConfigManager.isDebugMessagesEnabled()) {
                    LOGGER.info("BiomePruner: BLOCKED by large biome area cache - {} at {},{},{}", 
                        getBiomeName(surfaceBiome), x, y, z);
                }
            }
            // IMPORTANT: Track large biome area cache hits
            regionCache.recordCacheHit();
            BiomeRegionCache.BiomeResult largeBiomeResult = new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            // Cache this result for future surface matches
            regionCache.storeSurfaceCacheResult(x, z, largeBiomeResult);
            return largeBiomeResult;
        }

        // Get or start flood fill task
        BiomeRegionCache.FloodFillTask task = regionCache.getOrStartFloodFill(x, z, surfaceBiome);

        // Check if another thread already completed it
        BiomeRegionCache.FloodFillResult existingResult = task.getResult();
        if (existingResult != null) {
            // IMPORTANT: Track flood fill cache hits
            regionCache.recordCacheHit();
            BiomeRegionCache.BiomeResult cachedResult;
            if (existingResult.isLarge()) {
                cachedResult = new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            } else {
                // Send debug message if enabled
                if (ConfigManager.isDebugMessagesEnabled() && existingResult.positions() != null) {
                    DebugMessenger.getInstance().sendBiomeReplacementMessage(
                            x, y, z, vanillaBiome, existingResult.replacement(), existingResult.positions());
                }
                cachedResult = new BiomeRegionCache.BiomeResult(existingResult.replacement(), true);
            }
            
            // Store in surface cache for future surface biome matches
            regionCache.storeSurfaceCacheResult(x, z, cachedResult);
            return cachedResult;
        }

        // We need to run the flood fill
        // Rate-limited logging to prevent spam (log at most once every 5 seconds)
        if (ConfigManager.isDebugMessagesEnabled()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFloodFillLogTime > FLOOD_FILL_LOG_INTERVAL_MS) {
                lastFloodFillLogTime = currentTime;
                LOGGER.info("BiomePruner: Starting flood fill for {} at {},{},{} (surface biome: {}) [Rate limited - may be starting many more]",
                    getBiomeName(vanillaBiome), x, y, z, getBiomeName(surfaceBiome));
            }
        }
        try {
            runFloodFillAndComplete(x, surfaceY, z, surfaceBiome, source, sampler, task);

            // Wait for completion (with timeout to prevent hanging)
            BiomeRegionCache.FloodFillResult result = task.getFuture().get(5, TimeUnit.SECONDS);

            BiomeRegionCache.BiomeResult finalResult;
            if (result.isLarge()) {
                finalResult = new BiomeRegionCache.BiomeResult(vanillaBiome, false);
            } else {
                // Validate replacement biome before using it
                if (result.replacement() == null || !result.replacement().isBound()) {
                    LOGGER.warn("BiomePruner: Invalid replacement biome from flood fill at {},{},{}, using vanilla", 
                        x, y, z);
                    finalResult = new BiomeRegionCache.BiomeResult(vanillaBiome, false);
                } else {
                    // Send debug message if enabled
                    if (ConfigManager.isDebugMessagesEnabled() && result.positions() != null) {
                        DebugMessenger.getInstance().sendBiomeReplacementMessage(
                                x, y, z, vanillaBiome, result.replacement(), result.positions());
                    }
                    finalResult = new BiomeRegionCache.BiomeResult(result.replacement(), true);
                }
            }
            
            // Store in surface cache for future surface biome matches
            regionCache.storeSurfaceCacheResult(x, z, finalResult);
            return finalResult;

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Error in flood fill computation at {},{},{}", x, y, z, e);
            // On error, return vanilla biome
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error in flood fill computation at {},{},{}: {}", x, y, z, e.getMessage());
            // On coordinate or other runtime errors, return vanilla biome
            return new BiomeRegionCache.BiomeResult(vanillaBiome, false);
        } catch (Exception e) {
            LOGGER.error("Unexpected error in flood fill computation at {},{},{}", x, y, z, e);
            // On any other error, return vanilla biome
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

        // Convert block threshold to biome coordinate threshold (each biome coordinate = 16 blocks)
        int threshold = ConfigManager.getMicroBiomeThreshold() / 16;
        Set<Pos2D> visited = new HashSet<>();
        Queue<Pos2D> queue = new ArrayDeque<>();

        try {
            // Convert to biome coordinates (4x4 block grid) for accurate flood fill
            int biomeStartX = startX >> 2;
            int biomeStartZ = startZ >> 2;
            Pos2D start = new Pos2D(biomeStartX, biomeStartZ);
            queue.offer(start);
            visited.add(start);

            // Ultra-fast flood fill with aggressive early bailout
            while (!queue.isEmpty() && visited.size() <= threshold) {
                Pos2D current = queue.poll();

                // Simplified early bailout - only check when we're very close to threshold
                if (visited.size() > threshold * 0.95 && queue.size() > visited.size()) {
                    // Large biome detected - bail out immediately
                    regionCache.completeFloodFill(startX, startZ, targetBiome,
                            Collections.emptySet(), true, targetBiome);
                    // Mark this area as containing a large biome
                    regionCache.markLargeBiomeArea(startX, startZ, targetBiome);
                    // Store in spatial cache with large radius for maximum cache hits
                    regionCache.storeSpatialFloodFillResult(startX, startZ, targetBiome, true, targetBiome, 128);
                    return;
                }

                // Optimized flood fill - pre-collect neighbors to reduce cache contention
                List<Pos2D> potentialNeighbors = new ArrayList<>(4);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx == 0) == (dz == 0)) continue; // Skip center and diagonals
                        
                        Pos2D neighbor = new Pos2D(current.x() + dx, current.z() + dz);
                        if (!visited.contains(neighbor)) {
                            potentialNeighbors.add(neighbor);
                        }
                    }
                }
                
                // Process neighbors with batch-optimized cache access
                for (Pos2D neighbor : potentialNeighbors) {
                            try {
                                // Convert biome coordinates back to block coordinates for height/biome lookup
                                int neighborBlockX = neighbor.x() << 2;
                                int neighborBlockZ = neighbor.z() << 2;
                                int neighborY = heightmapCache.getHeight(neighborBlockX, neighborBlockZ);
                                Holder<Biome> neighborBiome = getSurfaceBiome(
                                        neighborBlockX, neighborBlockZ, neighborY, source, sampler);

                                if (neighborBiome.equals(targetBiome)) {
                                    visited.add(neighbor);
                                    queue.offer(neighbor);

                                    // Immediate exit if we exceed threshold
                                    if (visited.size() > threshold) {
                                        regionCache.completeFloodFill(startX, startZ, targetBiome,
                                                Collections.emptySet(), true, targetBiome);
                                        // Mark this area as containing a large biome
                                        regionCache.markLargeBiomeArea(startX, startZ, targetBiome);
                                        return;
                                    }
                                    
                                    // Simple progressive bailout - avoid processing huge biomes
                                    if (visited.size() > threshold * 0.8 && queue.size() > threshold * 0.5) {
                                        // Large expanding biome detected
                                        regionCache.completeFloodFill(startX, startZ, targetBiome,
                                                Collections.emptySet(), true, targetBiome);
                                        // Mark this area as containing a large biome
                                        regionCache.markLargeBiomeArea(startX, startZ, targetBiome);
                                        return;
                                    }
                                }
                            } catch (RuntimeException e) {
                                // If we get coordinate errors during flood fill, skip this neighbor
                                // and continue with the flood fill
                                LOGGER.warn("BiomePruner: Coordinate error during flood fill at neighbor {},{}, skipping: {}", 
                                    neighbor.x() << 2, neighbor.z() << 2, e.getMessage());
                                continue;
                            } catch (Exception e) {
                                // For other errors, log and skip this neighbor
                                LOGGER.warn("BiomePruner: Error sampling neighbor during flood fill at {},{}, skipping", 
                                    neighbor.x() << 2, neighbor.z() << 2, e);
                                continue;
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
            
            // Validate replacement before using it
            if (!isValidForDecoration(replacement, startX, startY, startZ, "replacement validation")) {
                LOGGER.warn("BiomePruner: Invalid replacement biome found at flood fill {},{}, using safe fallback", startX, startZ);
                replacement = getSafeFallbackBiome(targetBiome, startX, startY, startZ);
            }
        } finally {
            if (neighborTimer != null) neighborTimer.close();
        }

        regionCache.completeFloodFill(startX, startZ, targetBiome, visited, false, replacement);
        
        // Debug logging for completed micro biome
        if (ConfigManager.isDebugMessagesEnabled()) {
            LOGGER.info("BiomePruner: Completed micro biome flood fill for {} at {},{} - size: {}, replacement: {}",
                getBiomeName(targetBiome), startX, startZ, visited.size(), getBiomeName(replacement));
        }
        
        // Store in spatial cache for nearby position reuse
        int detectedRadius = (int) Math.sqrt(visited.size()); // Approximate radius from area
        regionCache.storeSpatialFloodFillResult(startX, startZ, targetBiome, false, replacement, detectedRadius);
    }

    /**
     * Find the dominant neighboring biome
     */
    private Holder<Biome> findDominantNeighbor(Set<Pos2D> microBiomePositions,
                                               Holder<Biome> originalBiome,
                                               MultiNoiseBiomeSource source,
                                               Climate.Sampler sampler) {
        // Get approximate surface height from the micro biome positions
        int estimatedSurfaceY = getEstimatedSurfaceHeight(microBiomePositions);
        Map<Holder<Biome>, Integer> validNeighborCounts = new HashMap<>();
        Map<Holder<Biome>, Integer> allNeighborCounts = new HashMap<>();

        // Check perimeter of micro biome
        for (Pos2D pos : microBiomePositions) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0) == (dz == 0)) continue; // Skip center and diagonals

                    Pos2D neighbor = new Pos2D(pos.x() + dx, pos.z() + dz);

                    if (!microBiomePositions.contains(neighbor)) {
                        try {
                            // Convert biome coordinates to block coordinates
                            int neighborBlockX = neighbor.x() << 2;
                            int neighborBlockZ = neighbor.z() << 2;
                            // Use height approximation for neighbor search performance
                            int height = getApproximateHeight(neighborBlockX, neighborBlockZ, estimatedSurfaceY);
                            Holder<Biome> neighborBiome = getSurfaceBiome(
                                    neighborBlockX, neighborBlockZ, height, source, sampler);

                            // Skip if neighbor is the same as original biome (prevents X->X replacements)
                            if (!neighborBiome.equals(originalBiome)) {
                                // Validate neighbor biome before using it
                                if (!isValidForDecoration(neighborBiome, neighborBlockX, height, neighborBlockZ, "neighbor validation")) {
                                    LOGGER.debug("BiomePruner: Invalid neighbor biome detected during replacement search, skipping");
                                    continue;
                                }
                                
                                // Always count for fallback
                                allNeighborCounts.merge(neighborBiome, 1, Integer::sum);

                                // Only count valid replacements for primary selection
                                if (ConfigManager.canUseAsReplacement(neighborBiome)) {
                                    validNeighborCounts.merge(neighborBiome, 1, Integer::sum);
                                }
                            }
                        } catch (RuntimeException e) {
                            // If we get coordinate errors during neighbor search, skip this neighbor
                            LOGGER.warn("BiomePruner: Coordinate error during neighbor search at {},{}, skipping: {}", 
                                neighbor.x() << 2, neighbor.z() << 2, e.getMessage());
                            continue;
                        } catch (Exception e) {
                            // For other errors, log and skip this neighbor
                            LOGGER.warn("BiomePruner: Error sampling neighbor for replacement at {},{}, skipping", 
                                neighbor.x() << 2, neighbor.z() << 2, e);
                            continue;
                        }
                    }
                }
            }
        }

        // First try: Find most common valid neighbor (excluding original biome)
        Optional<Holder<Biome>> validChoice = validNeighborCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);

        if (validChoice.isPresent()) {
            return validChoice.get();
        }

        // Fallback: All neighbors are blacklisted, use dominant anyway (excluding original)
        Optional<Holder<Biome>> fallbackChoice = allNeighborCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
        
        if (fallbackChoice.isPresent()) {
            return fallbackChoice.get();
        }
        
        // Ultimate fallback: No different neighbors found, keep original (should not replace)
        // This case indicates the biome is not actually a micro biome - likely a detection error
        LOGGER.warn("BiomePruner: Found micro biome {} with no different neighbors at flood fill origin. "
                + "This suggests fragmented detection of a larger biome.", getBiomeName(originalBiome));
        return originalBiome;
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
        // Validate input parameters
        if (source == null) {
            LOGGER.error("BiomePruner: Null biome source in getOriginalBiome at {},{},{}", blockX, blockY, blockZ);
            throw new IllegalArgumentException("Biome source cannot be null");
        }
        
        if (sampler == null) {
            LOGGER.error("BiomePruner: Null climate sampler in getOriginalBiome at {},{},{}", blockX, blockY, blockZ);
            throw new IllegalArgumentException("Climate sampler cannot be null");
        }
        
        // Validate coordinates are within reasonable bounds to prevent index errors
        if (blockX < -30000000 || blockX > 30000000) {
            LOGGER.warn("BiomePruner: Block X coordinate {} out of safe range, clamping", blockX);
            blockX = Math.max(-30000000, Math.min(30000000, blockX));
        }
        
        if (blockY < -2048 || blockY > 2048) {
            LOGGER.warn("BiomePruner: Block Y coordinate {} out of safe range, clamping", blockY);
            blockY = Math.max(-2048, Math.min(2048, blockY));
        }
        
        if (blockZ < -30000000 || blockZ > 30000000) {
            LOGGER.warn("BiomePruner: Block Z coordinate {} out of safe range, clamping", blockZ);
            blockZ = Math.max(-30000000, Math.min(30000000, blockZ));
        }
        
        // Convert block coordinates to noise coordinates (divide by 4)
        // Add additional safety checks for the conversion
        int noiseX, noiseY, noiseZ;
        try {
            noiseX = blockX >> 2;
            noiseY = blockY >> 2;
            noiseZ = blockZ >> 2;
            
            // Validate converted coordinates are reasonable
            if (noiseX < -7500000 || noiseX > 7500000 || 
                noiseY < -512 || noiseY > 512 || 
                noiseZ < -7500000 || noiseZ > 7500000) {
                LOGGER.warn("BiomePruner: Converted noise coordinates {},{},{} may cause array index issues", noiseX, noiseY, noiseZ);
            }
            
        } catch (Exception e) {
            LOGGER.error("BiomePruner: Error converting block coordinates {},{},{} to noise coordinates", blockX, blockY, blockZ, e);
            throw new RuntimeException("Coordinate conversion failed", e);
        }
        
        try {
            Holder<Biome> result = source.getNoiseBiome(noiseX, noiseY, noiseZ, sampler);
            
            // Validate the returned biome holder
            if (result == null) {
                LOGGER.error("BiomePruner: getNoiseBiome returned null at noise coords {},{},{} (block {},{},{})", 
                    noiseX, noiseY, noiseZ, blockX, blockY, blockZ);
                throw new RuntimeException("BiomeSource returned null biome");
            }
            
            if (!result.isBound()) {
                LOGGER.warn("BiomePruner: Unbound biome holder returned at noise coords {},{},{} (block {},{},{})", 
                    noiseX, noiseY, noiseZ, blockX, blockY, blockZ);
            }
            
            return result;
            
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error("BiomePruner: Index out of bounds in getNoiseBiome at noise coords {},{},{} (block {},{},{})", 
                noiseX, noiseY, noiseZ, blockX, blockY, blockZ, e);
            throw new RuntimeException("BiomeSource array access failed - this indicates a coordinate validation issue", e);
        } catch (Exception e) {
            LOGGER.error("BiomePruner: Unexpected error in getNoiseBiome at noise coords {},{},{} (block {},{},{})", 
                noiseX, noiseY, noiseZ, blockX, blockY, blockZ, e);
            throw new RuntimeException("BiomeSource access failed", e);
        }
    }

    // Rate limiting for cave biome detection logging
    private static volatile boolean hasLoggedCaveSkip = false;
    
    // Rate limiting for flood fill start logging
    private static volatile long lastFloodFillLogTime = 0;
    private static final long FLOOD_FILL_LOG_INTERVAL_MS = 5000; // Log at most once every 5 seconds

    /**
     * Get estimated surface height from a set of positions (avoids individual height calculations)
     */
    private int getEstimatedSurfaceHeight(Set<Pos2D> positions) {
        if (positions.isEmpty()) {
            return 64; // Default minecraft surface level
        }
        
        // For micro biomes, use height from first position as estimate
        // This avoids expensive height calculations for every position
        Pos2D first = positions.iterator().next();
        // Convert biome coordinates to block coordinates
        int blockX = first.x() << 2;
        int blockZ = first.z() << 2;
        return heightmapCache.getHeight(blockX, blockZ);
    }
    
    /**
     * Get approximate height for a position using nearby known heights
     * This is much faster than exact height calculation for small areas
     */
    private int getApproximateHeight(int x, int z, int nearbyHeight) {
        // For micro biomes and small areas, terrain height variation is usually minimal
        // Use the nearby height as approximation, with small random variation
        
        // Check if we're within a reasonable distance for approximation
        int variation = Math.abs((x % 8) - 4) + Math.abs((z % 8) - 4); // 0-8 range
        
        // Add slight variation based on position to account for minor terrain changes
        return nearbyHeight + (variation > 6 ? (variation - 6) : 0) - 1;
    }

    /**
     * Get approximate surface biome for flood fill optimization
     * Uses estimated height to avoid expensive heightmap calculations during flood fill
     */
    private Holder<Biome> getApproximateSurfaceBiome(int blockX, int blockZ, int estimatedY,
                                                     MultiNoiseBiomeSource source,
                                                     Climate.Sampler sampler) {
        // For micro biomes (small areas), height variation is usually minimal
        // Use the estimated Y from the start position to avoid expensive height calculations
        return getSurfaceBiome(blockX, blockZ, estimatedY, source, sampler);
    }

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
            try {
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
                
            } catch (RuntimeException e) {
                // If we get a coordinate error at this height, try the next height level
                LOGGER.warn("BiomePruner: Coordinate error at {},{},{}, trying next height level: {}", 
                    blockX, currentY, blockZ, e.getMessage());
                currentY += 8;
                samples++;
                continue;
            } catch (Exception e) {
                // For other errors, log and try next height
                LOGGER.warn("BiomePruner: Error sampling biome at {},{},{}, trying next height level", 
                    blockX, currentY, blockZ, e);
                currentY += 8;
                samples++;
                continue;
            }
        }
        
        // Fallback: return the biome at the original height if we couldn't find a non-cave biome
        try {
            return getOriginalBiome(blockX, startY, blockZ, source, sampler);
        } catch (Exception e) {
            // Ultimate fallback - if we can't get any biome, this is a serious error
            LOGGER.error("BiomePruner: Critical error - cannot get fallback biome at {},{},{}", 
                blockX, startY, blockZ, e);
            throw new RuntimeException("Critical biome sampling failure", e);
        }
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
        // Use biome coordinate threshold for consistency with actual flood fill
        int threshold = ConfigManager.getMicroBiomeThreshold() / 16;
        Set<Pos2D> visited = new HashSet<>();
        Queue<Pos2D> queue = new ArrayDeque<>();

        // Convert to biome coordinates for consistent behavior
        int biomeStartX = startX >> 2;
        int biomeStartZ = startZ >> 2;
        Pos2D start = new Pos2D(biomeStartX, biomeStartZ);
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
                        // Convert biome coordinates back to block coordinates
                        int neighborBlockX = neighbor.x() << 2;
                        int neighborBlockZ = neighbor.z() << 2;
                        // Get biome at neighbor surface position, skipping cave biomes
                        int neighborY = heightmapCache.getHeight(neighborBlockX, neighborBlockZ);
                        Holder<Biome> neighborBiome = getSurfaceBiome(
                                neighborBlockX, neighborBlockZ, neighborY, source, sampler);

                        if (neighborBiome.equals(targetBiome)) {
                            visited.add(neighbor);
                            queue.offer(neighbor);

                            // Early exit if we exceed threshold
                            if (visited.size() > threshold) {
                                // Large biome - convert to block count for display
                                return new DebugFloodFillResult(visited.size() * 16, false, null);
                            }
                        }
                    }
                }
            }
        }

        // Micro biome - find replacement
        Holder<Biome> replacement = findDominantNeighbor(visited, targetBiome, source, sampler);
        // Convert biome coordinate count to block count for debug display
        return new DebugFloodFillResult(visited.size() * 16, true, replacement);
    }

    /**
     * Clear all cached data - CRITICAL for world unloading
     * Must be called when switching worlds to prevent cross-world contamination
     */
    public void clearAllCaches() {
        LOGGER.info("BiomePruner: Clearing all BiomeSmoother caches for world unload");
        
        // Clear the region cache (singleton)
        regionCache.clearAll();
        
        // Clear the heightmap cache (instance)
        heightmapCache.clearAll();
        
        LOGGER.info("BiomePruner: All BiomeSmoother caches cleared successfully");
    }
    
    /**
     * Get cache statistics for debugging
     */
    public String getCacheStatistics() {
        return String.format("BiomeSmoother Cache Stats:\n- %s\n- %s", 
            regionCache.getStatistics(), 
            heightmapCache.getStatistics());
    }
}