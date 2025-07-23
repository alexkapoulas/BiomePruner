package com.example.alexthundercook.biomepruner.cache;

import com.google.common.util.concurrent.Striped;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;
import com.example.alexthundercook.biomepruner.util.LocalPos;
import com.example.alexthundercook.biomepruner.util.RegionKey;
import com.example.alexthundercook.biomepruner.util.Pos2D;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

public class BiomeRegionCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");
    private static final BiomeRegionCache INSTANCE = new BiomeRegionCache();
    private static final int REGION_SIZE = 512;
    private static final int REGION_SHIFT = 9; // log2(512)

    private final ConcurrentHashMap<RegionKey, Region> activeRegions = new ConcurrentHashMap<>();
    private final Striped<Lock> regionLocks = Striped.lock(256);

    // Position-level locking for deterministic processing
    private final Striped<Lock> positionLocks = Striped.lock(4096);

    // Global flood fill cache to prevent duplicate work
    private final ConcurrentHashMap<FloodFillKey, FloodFillTask> floodFillTasks = new ConcurrentHashMap<>();

    private final AtomicLong memoryUsage = new AtomicLong(0);

    // Statistics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);

    private BiomeRegionCache() {}

    public static BiomeRegionCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * Validate that a biome holder can be safely used in Minecraft's decoration system
     * This prevents IndexOutOfBoundsException with index -1 during chunk decoration
     */
    private boolean isValidBiomeHolder(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) {
            return false;
        }
        
        // Check if biome holder is bound to registry
        if (!biomeHolder.isBound()) {
            return false;
        }
        
        // Check if biome holder has a valid registry key
        if (biomeHolder.unwrapKey().isEmpty()) {
            return false;
        }
        
        try {
            // Try to get the server and registry for validation
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
                ResourceKey<Biome> biomeKey = biomeHolder.unwrapKey().get();
                
                // Check if the biome is actually registered in the current registry
                if (!biomeRegistry.containsKey(biomeKey)) {
                    return false;
                }
                
                // Try to get the registry ID to ensure it's not -1
                int registryId = biomeRegistry.getId(biomeRegistry.get(biomeKey));
                if (registryId < 0) {
                    return false;
                }
                
                // Additional validation: ensure the biome can be retrieved by ID
                Biome retrievedBiome = biomeRegistry.byId(registryId);
                if (retrievedBiome == null) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.debug("BiomePruner: Biome holder validation error", e);
            return false;
        }
    }
    
    /**
     * Validate and potentially refresh a cached BiomeResult
     * Returns null if the cached result is invalid and should be removed
     */
    private BiomeResult validateCachedResult(BiomeResult cachedResult) {
        if (cachedResult == null) {
            return null;
        }
        
        if (!isValidBiomeHolder(cachedResult.biome())) {
            if (ConfigManager.isDebugMessagesEnabled()) {
                LOGGER.debug("BiomePruner: Removing invalid cached biome result");
            }
            return null;
        }
        
        return cachedResult;
    }

    /**
     * Region represents a 512x512 area
     */
    private static class Region {
        final RegionKey key;
        final ConcurrentHashMap<LocalPos, BiomeResult> biomeResults = new ConcurrentHashMap<>();
        final ConcurrentHashMap<SurfacePos, BiomeResult> surfaceResults = new ConcurrentHashMap<>();
        // Position-specific large biome cache - tracks 16x16 areas with center positions
        final ConcurrentHashMap<Holder<Biome>, Set<Long>> largeBiomeCenters = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, MicroBiomeColumn> microBiomeColumns = new ConcurrentHashMap<>();
        // Cache for biome-specific mismatch results (biome at position != surface biome)
        final ConcurrentHashMap<BiomePosKey, Boolean> biomeMismatchCache = new ConcurrentHashMap<>();
        // Spatial cache for flood fill results - key is region center, value includes radius
        final ConcurrentHashMap<SpatialFloodFillKey, SpatialFloodFillResult> spatialFloodFillCache = new ConcurrentHashMap<>();
        final AtomicLong lastAccessTime = new AtomicLong(System.nanoTime());

        Region(RegionKey key) {
            this.key = key;
        }

        /**
         * Get column key from x,z coordinates
         */
        private static int getColumnKey(int localX, int localZ) {
            return (localX & 511) << 9 | (localZ & 511);
        }
    }

    /**
     * Cached biome result
     */
    public record BiomeResult(Holder<Biome> biome, boolean wasMicro) {}

    /**
     * Surface biome cache key (x, z coordinates only)
     */
    private record SurfacePos(int x, int z) {}

    /**
     * Biome-position key for mismatch caching (biome + position)
     */
    private record BiomePosKey(int x, int z, Holder<Biome> biome) {}
    
    /**
     * Spatial flood fill cache key (grid-aligned position + biome)
     */
    private record SpatialFloodFillKey(int gridX, int gridZ, Holder<Biome> biome) {}
    
    /**
     * Spatial flood fill result with coverage area
     */
    public record SpatialFloodFillResult(boolean isLarge, Holder<Biome> replacement, int radius, long timestamp) {}

    /**
     * Micro biome column replacement
     */
    private record MicroBiomeColumn(Holder<Biome> originalSurfaceBiome, Holder<Biome> replacement) {}

    /**
     * Surface biome cache entry
     */
    private record SurfaceBiomeEntry(int surfaceY, Holder<Biome> surfaceBiome) {}

    /**
     * Key for flood fill deduplication
     */
    private record FloodFillKey(int x, int z, Holder<Biome> biome) {}

    /**
     * Flood fill task for collaborative processing
     */
    public static class FloodFillTask {
        private final CompletableFuture<FloodFillResult> future = new CompletableFuture<>();
        private volatile FloodFillResult result;

        public CompletableFuture<FloodFillResult> getFuture() {
            return future;
        }

        public void complete(FloodFillResult result) {
            this.result = result;
            future.complete(result);
        }

        public FloodFillResult getResult() {
            return result;
        }
    }

    /**
     * Result of flood fill
     */
    public record FloodFillResult(Set<Pos2D> positions, boolean isLarge, Holder<Biome> replacement) {}

    /**
     * Get or compute biome for position - MUST be deterministic
     */
    public BiomeResult getOrComputeBiome(int x, int y, int z,
                                         Holder<Biome> vanillaBiome,
                                         java.util.function.Function<Pos2D, BiomeResult> computer) {
        PerformanceTracker tracker = ConfigManager.isPerformanceLoggingEnabled() ?
                PerformanceTracker.getInstance() : null;
        PerformanceTracker.Timer cacheTimer = tracker != null ?
                tracker.startTimer(PerformanceTracker.Section.CACHE_CHECK) : null;

        try {
            RegionKey regionKey = getRegionKey(x, z);
            Region region = getOrCreateRegion(regionKey);

            // Check surface-based cache first (most efficient for biome analysis)
            // Note: Surface cache should only be used by the computer function that validates
            // the vanilla biome matches the surface biome - not returned directly here

            // Check legacy position-specific cache for backward compatibility
            LocalPos localPos = new LocalPos(x & 511, y, z & 511);
            BiomeResult existing = region.biomeResults.get(localPos);
            if (existing != null) {
                // Validate cached result before returning
                BiomeResult validatedResult = validateCachedResult(existing);
                if (validatedResult != null) {
                    hits.incrementAndGet();
                    if (tracker != null) tracker.recordCacheHit();
                    return validatedResult;
                } else {
                    // Remove invalid cached result
                    region.biomeResults.remove(localPos);
                }
            }

            // Check column cache
            int columnKey = Region.getColumnKey(x & 511, z & 511);
            MicroBiomeColumn column = region.microBiomeColumns.get(columnKey);
            if (column != null && column.originalSurfaceBiome.equals(vanillaBiome)) {
                // Validate the replacement biome before returning
                if (isValidBiomeHolder(column.replacement)) {
                    hits.incrementAndGet();
                    if (tracker != null) tracker.recordCacheHit();
                    return new BiomeResult(column.replacement, true);
                } else {
                    // Remove invalid column cache entry
                    region.microBiomeColumns.remove(columnKey);
                }
            }
        } finally {
            if (cacheTimer != null) cacheTimer.close();
        }

        // Need to compute - use position lock for determinism
        Lock posLock = positionLocks.get(new Pos2D(x, z));
        posLock.lock();
        try {
            // Double-check after acquiring lock
            RegionKey regionKey = getRegionKey(x, z);
            Region region = getOrCreateRegion(regionKey);
            
            // Note: Surface cache handled by computer function, not directly here

            LocalPos localPos = new LocalPos(x & 511, y, z & 511);
            BiomeResult existing = region.biomeResults.get(localPos);
            if (existing != null) {
                // Validate cached result before returning
                BiomeResult validatedResult = validateCachedResult(existing);
                if (validatedResult != null) {
                    hits.incrementAndGet();
                    if (tracker != null) tracker.recordCacheHit();
                    return validatedResult;
                } else {
                    // Remove invalid cached result
                    region.biomeResults.remove(localPos);
                }
            }

            int columnKey = Region.getColumnKey(x & 511, z & 511);
            MicroBiomeColumn column = region.microBiomeColumns.get(columnKey);
            if (column != null && column.originalSurfaceBiome.equals(vanillaBiome)) {
                // Validate the replacement biome before returning
                if (isValidBiomeHolder(column.replacement)) {
                    hits.incrementAndGet();
                    if (tracker != null) tracker.recordCacheHit();
                    return new BiomeResult(column.replacement, true);
                } else {
                    // Remove invalid column cache entry
                    region.microBiomeColumns.remove(columnKey);
                }
            }

            // Compute the result
            misses.incrementAndGet();
            if (tracker != null) tracker.recordCacheMiss();
            BiomeResult result = computer.apply(new Pos2D(x, z));

            // Cache the result in position cache
            PerformanceTracker.Timer storeTimer = tracker != null ?
                    tracker.startTimer(PerformanceTracker.Section.CACHE_STORE) : null;
            try {
                // Store in position cache for backward compatibility
                region.biomeResults.put(localPos, result);
            } finally {
                if (storeTimer != null) storeTimer.close();
            }

            return result;
        } finally {
            posLock.unlock();
        }
    }

    /**
     * Get or start flood fill task
     */
    public FloodFillTask getOrStartFloodFill(int x, int z, Holder<Biome> biome) {
        FloodFillKey key = new FloodFillKey(x, z, biome);

        // Try to get existing task
        FloodFillTask existing = floodFillTasks.get(key);
        if (existing != null) {
            return existing;
        }

        // Create new task
        FloodFillTask newTask = new FloodFillTask();
        FloodFillTask actual = floodFillTasks.putIfAbsent(key, newTask);

        return actual != null ? actual : newTask;
    }

    /**
     * Get existing flood fill task without creating a new one
     */
    public FloodFillTask getExistingFloodFill(int x, int z, Holder<Biome> biome) {
        FloodFillKey key = new FloodFillKey(x, z, biome);
        return floodFillTasks.get(key);
    }

    /**
     * Complete flood fill task and cache results
     */
    public void completeFloodFill(int x, int z, Holder<Biome> biome,
                                  Set<Pos2D> positions, boolean isLarge,
                                  Holder<Biome> replacement) {
        FloodFillKey key = new FloodFillKey(x, z, biome);
        FloodFillTask task = floodFillTasks.get(key);

        if (task != null) {
            FloodFillResult result = new FloodFillResult(positions, isLarge, replacement);

            // REMOVED: Known large biome marking was preventing micro biome detection
            // This was marking entire biome types as large across regions
            // 
            // if (isLarge) {
            //     RegionKey regionKey = getRegionKey(x, z);
            //     Region region = getOrCreateRegion(regionKey);
            //     region.knownLargeBiomes.put(biome, true);
            // }
            
            if (!isLarge) {
                // Cache column replacements for micro biome
                for (Pos2D pos : positions) {
                    cacheMicroBiomeColumn(pos.x(), pos.z(), biome, replacement);
                }
            }

            task.complete(result);
        }
    }

    /**
     * Check if we already know a biome is large near this position
     */
    public boolean isKnownLargeBiomeArea(int x, int z, Holder<Biome> biome) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = activeRegions.get(regionKey);

        if (region != null) {
            Set<Long> largeBiomeCenters = region.largeBiomeCenters.get(biome);
            if (largeBiomeCenters != null) {
                // Check if this position is within 32 blocks of any known large center
                for (Long center : largeBiomeCenters) {
                    int centerX = (int)(center >> 32);
                    int centerZ = (int)(center & 0xFFFFFFFF);
                    
                    int dx = Math.abs(x - centerX);
                    int dz = Math.abs(z - centerZ);
                    
                    // Only block if within 32 blocks of the large biome center
                    if (dx <= 32 && dz <= 32) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Mark a biome center as large (32 block radius)
     */
    public void markLargeBiomeArea(int x, int z, Holder<Biome> biome) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);
        
        long centerKey = ((long)x << 32) | (z & 0xFFFFFFFFL);
        region.largeBiomeCenters.computeIfAbsent(biome, k -> new ConcurrentSkipListSet<>()).add(centerKey);
        
        // Debug logging for problematic area
        if ((Math.abs(x - 10890) <= 100 && Math.abs(z - 17394) <= 100)) {
            // Get biome name safely
            String biomeName = "Unknown";
            try {
                if (biome.isBound() && biome.unwrapKey().isPresent()) {
                    biomeName = biome.unwrapKey().get().location().getPath().replace('_', ' ');
                }
            } catch (Exception e) {
                // Ignore errors in debug logging
            }
            LOGGER.info("BiomePruner: MARKED large biome center - {} at {},{} (32 block radius)", 
                biomeName, x, z);
        }
    }

    /**
     * Cache a biome result for a specific position
     */
    public void cacheBiome(int x, int y, int z, Holder<Biome> biome, boolean wasMicro) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);

        LocalPos localPos = new LocalPos(x & 511, y, z & 511);
        region.biomeResults.put(localPos, new BiomeResult(biome, wasMicro));
    }

    /**
     * Cache a micro biome column replacement
     */
    private void cacheMicroBiomeColumn(int x, int z, Holder<Biome> originalSurfaceBiome,
                                       Holder<Biome> replacement) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);

        int columnKey = Region.getColumnKey(x & 511, z & 511);
        region.microBiomeColumns.put(columnKey, new MicroBiomeColumn(originalSurfaceBiome, replacement));
    }

    /**
     * Get or create region with thread safety
     */
    private Region getOrCreateRegion(RegionKey key) {
        Region existing = activeRegions.get(key);
        if (existing != null) {
            existing.lastAccessTime.set(System.nanoTime());
            return existing;
        }

        Lock lock = regionLocks.get(key);
        lock.lock();
        try {
            // Double-check after acquiring lock
            existing = activeRegions.get(key);
            if (existing != null) {
                return existing;
            }

            // Check memory before creating
            ensureMemoryAvailable();

            Region newRegion = new Region(key);
            activeRegions.put(key, newRegion);
            memoryUsage.addAndGet(estimateRegionMemory(newRegion));

            return newRegion;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ensure memory is available by evicting old regions
     */
    private void ensureMemoryAvailable() {
        long maxMemory = ConfigManager.getMaxCacheMemoryMB() * 1024L * 1024L;

        while (memoryUsage.get() > maxMemory && activeRegions.size() > 1) {
            // Find and evict oldest region
            Region oldest = null;
            long oldestTime = Long.MAX_VALUE;

            for (Region region : activeRegions.values()) {
                long lastAccess = region.lastAccessTime.get();
                if (lastAccess < oldestTime) {
                    oldestTime = lastAccess;
                    oldest = region;
                }
            }

            if (oldest != null) {
                activeRegions.remove(oldest.key);
                memoryUsage.addAndGet(-estimateRegionMemory(oldest));
            } else {
                break;
            }
        }
    }

    /**
     * Convert block coordinates to region key
     */
    private static RegionKey getRegionKey(int x, int z) {
        return new RegionKey(x >> REGION_SHIFT, z >> REGION_SHIFT);
    }

    /**
     * Estimate memory usage of a region
     */
    private static long estimateRegionMemory(Region region) {
        // Rough estimate: base overhead + entries
        return 1024 + (region.biomeResults.size() * 64L) +
                (region.microBiomeColumns.size() * 32L);
    }

    /**
     * Get surface cache result if available
     */
    public BiomeResult getSurfaceCacheResult(int x, int z) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = activeRegions.get(regionKey);
        if (region != null) {
            SurfacePos surfacePos = new SurfacePos(x & 511, z & 511);
            BiomeResult cachedResult = region.surfaceResults.get(surfacePos);
            
            // Validate cached result before returning
            BiomeResult validatedResult = validateCachedResult(cachedResult);
            if (validatedResult == null && cachedResult != null) {
                // Remove invalid cached result
                region.surfaceResults.remove(surfacePos);
            }
            
            return validatedResult;
        }
        return null;
    }

    /**
     * Store surface cache result
     */
    public void storeSurfaceCacheResult(int x, int z, BiomeResult result) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);
        SurfacePos surfacePos = new SurfacePos(x & 511, z & 511);
        region.surfaceResults.put(surfacePos, result);
    }

    /**
     * Check if we know this biome at position has mismatch (vanilla biome != surface biome)
     */
    public Boolean getBiomeMismatch(int x, int z, Holder<Biome> vanillaBiome) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = activeRegions.get(regionKey);
        if (region != null) {
            BiomePosKey key = new BiomePosKey(x & 511, z & 511, vanillaBiome);
            return region.biomeMismatchCache.get(key);
        }
        return null;
    }

    /**
     * Store biome mismatch result (vanilla biome != surface biome)
     */
    public void storeBiomeMismatch(int x, int z, Holder<Biome> vanillaBiome, boolean mismatch) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);
        BiomePosKey key = new BiomePosKey(x & 511, z & 511, vanillaBiome);
        region.biomeMismatchCache.put(key, mismatch);
    }
    
    /**
     * Check if we have a nearby spatial flood fill result
     */
    public SpatialFloodFillResult getNearbyFloodFillResult(int x, int z, Holder<Biome> biome) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = activeRegions.get(regionKey);
        if (region == null) return null;
        
        // Check multiple grid sizes for spatial cache hits
        for (int gridSize : new int[]{32, 64, 128}) {
            int gridX = (x / gridSize) * gridSize;
            int gridZ = (z / gridSize) * gridSize;
            SpatialFloodFillKey key = new SpatialFloodFillKey(gridX, gridZ, biome);
            
            SpatialFloodFillResult result = region.spatialFloodFillCache.get(key);
            if (result != null) {
                // Check if the position is within the cached result's coverage
                int dx = Math.abs(x - gridX);
                int dz = Math.abs(z - gridZ);
                int distance = Math.max(dx, dz);
                
                if (distance <= result.radius()) {
                    // Check if cache entry is still fresh (within 30 seconds)
                    long age = System.currentTimeMillis() - result.timestamp();
                    if (age < 30000) {
                        // IMPORTANT: Track spatial cache hits in statistics
                        hits.incrementAndGet();
                        return result;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Store spatial flood fill result for nearby position reuse
     */
    public void storeSpatialFloodFillResult(int x, int z, Holder<Biome> biome, boolean isLarge, 
                                           Holder<Biome> replacement, int detectedRadius) {
        RegionKey regionKey = getRegionKey(x, z);
        Region region = getOrCreateRegion(regionKey);
        
        // Store in appropriate grid size based on detected radius
        int gridSize = detectedRadius < 16 ? 32 : (detectedRadius < 32 ? 64 : 128);
        int gridX = (x / gridSize) * gridSize;
        int gridZ = (z / gridSize) * gridSize;
        
        SpatialFloodFillKey key = new SpatialFloodFillKey(gridX, gridZ, biome);
        SpatialFloodFillResult result = new SpatialFloodFillResult(
            isLarge, replacement, detectedRadius, System.currentTimeMillis());
        
        region.spatialFloodFillCache.put(key, result);
    }

    /**
     * Record a cache hit (for tracking hit rates properly)
     */
    public void recordCacheHit() {
        hits.incrementAndGet();
    }

    /**
     * Get cache statistics
     */
    public String getStatistics() {
        long h = hits.get();
        long m = misses.get();
        double hitRate = (h + m == 0) ? 0.0 : (double) h / (h + m);

        return String.format("Regions: %d, Memory: %.2f MB, Hit rate: %.2f%%",
                activeRegions.size(),
                memoryUsage.get() / 1024.0 / 1024.0,
                hitRate * 100);
    }

    /**
     * Clear all cached data - CRITICAL for world unloading
     * Must be called when switching worlds to prevent cross-world contamination
     */
    public void clearAll() {
        LOGGER.info("BiomePruner: Clearing all cache data for world unload");
        
        // Cancel all running flood fill tasks before clearing
        for (FloodFillTask task : floodFillTasks.values()) {
            try {
                if (!task.getFuture().isDone()) {
                    task.getFuture().cancel(true);
                }
            } catch (Exception e) {
                // Ignore errors during cancellation
            }
        }
        
        // Clear all cached regions and their data
        activeRegions.clear();
        
        // Clear all flood fill tasks
        floodFillTasks.clear();
        
        // Reset memory usage counter
        memoryUsage.set(0);
        
        // Reset statistics
        hits.set(0);
        misses.set(0);
        
        LOGGER.info("BiomePruner: Cache cleared successfully");
    }
    
    /**
     * Get memory usage in bytes
     */
    public long getMemoryUsage() {
        return memoryUsage.get();
    }
    
    /**
     * Get number of cached regions
     */
    public int getCachedRegionCount() {
        return activeRegions.size();
    }
}