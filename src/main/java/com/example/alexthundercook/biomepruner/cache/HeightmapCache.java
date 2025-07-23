package com.example.alexthundercook.biomepruner.cache;

import com.google.common.util.concurrent.Striped;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.core.GeneratorContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.HashMap;
import java.util.Map;

public class HeightmapCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");
    private static final int CHUNK_SIZE = 16;
    
    // Thread-local cache for height calculations to reduce synchronization overhead
    private static final int BATCH_SIZE = 8;
    private final ThreadLocal<HeightBatch> threadLocalBatch = ThreadLocal.withInitial(HeightBatch::new);
    
    /**
     * Thread-local batch for height calculations
     */
    private static class HeightBatch {
        final Map<Long, Float> heightCache = new HashMap<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();
        
        void addHeight(int x, int z, float height) {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            heightCache.put(key, height);
            
            // Flush batch when full or after timeout
            if (heightCache.size() >= BATCH_SIZE || 
                System.currentTimeMillis() - lastFlushTime > 100) {
                flush();
            }
        }
        
        Float getHeight(int x, int z) {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            return heightCache.get(key);
        }
        
        void flush() {
            heightCache.clear();
            lastFlushTime = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<ChunkPos, ChunkHeightGrid> gridCache = new ConcurrentHashMap<>();
    private final Striped<ReadWriteLock> chunkLocks = Striped.readWriteLock(128);
    private final AtomicLong currentGridPoints = new AtomicLong(0);
    private final ConcurrentLinkedDeque<ChunkPos> lruQueue = new ConcurrentLinkedDeque<>();

    /**
     * Chunk-aligned height grid with lock-free atomic storage
     */
    private static class ChunkHeightGrid {
        final ChunkPos chunkPos;
        final AtomicReferenceArray<AtomicReferenceArray<Float>> heights;
        final AtomicInteger calculatedPoints;
        final AtomicReferenceArray<Boolean> calculatedMask;
        volatile long lastAccessTime;
        private final int gridCellsPerChunk;

        ChunkHeightGrid(ChunkPos pos, int gridSpacing) {
            this.chunkPos = pos;
            this.gridCellsPerChunk = CHUNK_SIZE / gridSpacing + 1;
            
            // Initialize atomic 2D array for heights
            this.heights = new AtomicReferenceArray<>(gridCellsPerChunk);
            for (int i = 0; i < gridCellsPerChunk; i++) {
                this.heights.set(i, new AtomicReferenceArray<>(gridCellsPerChunk));
            }
            
            this.calculatedPoints = new AtomicInteger(0);
            this.calculatedMask = new AtomicReferenceArray<>(gridCellsPerChunk * gridCellsPerChunk);
            
            // Initialize all mask values to false
            for (int i = 0; i < gridCellsPerChunk * gridCellsPerChunk; i++) {
                this.calculatedMask.set(i, false);
            }
            
            this.lastAccessTime = System.nanoTime();
        }
        
        /**
         * Get grid cell count for memory estimation
         */
        int getGridCellCount() {
            return gridCellsPerChunk;
        }
    }

    // Store biome source reference for height calculation
    private BiomeSource biomeSource;

    /**
     * Set the biome source for this cache instance
     */
    public void setBiomeSource(BiomeSource source) {
        this.biomeSource = source;
    }

    /**
     * Get height at any position with interpolation and thread-local batching
     */
    public int getHeight(int blockX, int blockZ) {
        // Check thread-local cache first to reduce synchronization
        HeightBatch batch = threadLocalBatch.get();
        Float cachedHeight = batch.getHeight(blockX, blockZ);
        if (cachedHeight != null) {
            return Math.round(cachedHeight);
        }
        
        int gridSpacing = ConfigManager.getGridSpacing();

        // Find grid cell containing this position
        int gridX0 = Math.floorDiv(blockX, gridSpacing);
        int gridZ0 = Math.floorDiv(blockZ, gridSpacing);
        int gridX1 = gridX0 + 1;
        int gridZ1 = gridZ0 + 1;

        // Get heights at four corners
        float h00 = getGridHeight(gridX0, gridZ0);
        float h10 = getGridHeight(gridX1, gridZ0);
        float h01 = getGridHeight(gridX0, gridZ1);
        float h11 = getGridHeight(gridX1, gridZ1);

        // Calculate interpolation factors
        float fx = (float)(blockX - gridX0 * gridSpacing) / gridSpacing;
        float fz = (float)(blockZ - gridZ0 * gridSpacing) / gridSpacing;

        // Bilinear interpolation
        float h0 = h00 * (1 - fx) + h10 * fx;
        float h1 = h01 * (1 - fx) + h11 * fx;
        float result = h0 * (1 - fz) + h1 * fz;
        
        // Cache result in thread-local batch
        batch.addHeight(blockX, blockZ, result);

        return Math.round(result);
    }

    /**
     * Get height at grid point
     */
    private float getGridHeight(int gridX, int gridZ) {
        int gridSpacing = ConfigManager.getGridSpacing();

        // Convert to chunk coordinates
        ChunkPos chunkPos = new ChunkPos(
                Math.floorDiv(gridX * gridSpacing, CHUNK_SIZE),
                Math.floorDiv(gridZ * gridSpacing, CHUNK_SIZE)
        );

        // Get or create chunk grid
        ChunkHeightGrid grid = getOrCreateGrid(chunkPos);

        // Convert to local grid coordinates
        int gridCellsPerChunk = CHUNK_SIZE / gridSpacing;
        int localGridX = Math.floorMod(gridX, gridCellsPerChunk);
        int localGridZ = Math.floorMod(gridZ, gridCellsPerChunk);

        // Check bounds
        if (localGridX < 0 || localGridZ < 0 ||
                localGridX >= grid.heights.length() ||
                localGridZ >= grid.heights.get(0).length()) {
            // Need to get from neighbor chunk
            return calculateHeightFromNoise(gridX * gridSpacing, gridZ * gridSpacing);
        }

        // Check if already calculated using atomic operations
        int index = localGridX * grid.getGridCellCount() + localGridZ;

        // Lock-free double-checked pattern with atomic operations
        Boolean isCalculated = grid.calculatedMask.get(index);
        if (isCalculated != null && isCalculated) {
            Float cachedHeight = grid.heights.get(localGridX).get(localGridZ);
            if (cachedHeight != null) {
                return cachedHeight;
            }
        }

        // Calculate height value
        float height = calculateHeightFromNoise(gridX * gridSpacing, gridZ * gridSpacing);
        
        // Attempt atomic update with compare-and-set
        if (grid.calculatedMask.compareAndSet(index, false, true) ||
            grid.calculatedMask.compareAndSet(index, null, true)) {
            // We won the race to calculate this height
            grid.heights.get(localGridX).set(localGridZ, height);
            grid.calculatedPoints.incrementAndGet();
            return height;
        } else {
            // Another thread calculated it, use their result
            Float cachedHeight = grid.heights.get(localGridX).get(localGridZ);
            if (cachedHeight != null) {
                return cachedHeight;
            }
            // Fallback to our calculated value if cache read failed
            return height;
        }
    }

    /**
     * Get or create grid with thread safety
     */
    private ChunkHeightGrid getOrCreateGrid(ChunkPos pos) {
        ChunkHeightGrid existing = gridCache.get(pos);
        if (existing != null) {
            existing.lastAccessTime = System.nanoTime();
            return existing;
        }

        ReadWriteLock lock = chunkLocks.get(pos);
        lock.readLock().lock();
        try {
            existing = gridCache.get(pos);
            if (existing != null) {
                existing.lastAccessTime = System.nanoTime();
                return existing;
            }
        } finally {
            lock.readLock().unlock();
        }

        // Need write lock to create
        lock.writeLock().lock();
        try {
            // Double-check
            existing = gridCache.get(pos);
            if (existing != null) {
                return existing;
            }

            // Create new grid
            int gridSpacing = ConfigManager.getGridSpacing();
            ChunkHeightGrid newGrid = new ChunkHeightGrid(pos, gridSpacing);
            gridCache.put(pos, newGrid);

            int gridCellsPerChunk = newGrid.getGridCellCount();
            currentGridPoints.addAndGet(gridCellsPerChunk * gridCellsPerChunk);
            lruQueue.offer(pos);

            // Check memory pressure
            ensureMemoryBounds();

            return newGrid;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Calculate height from noise using the actual world generation system
     */
    private float calculateHeightFromNoise(int x, int z) {
        // Use the proper noise-based height calculation if available
        if (biomeSource != null) {
            int height = GeneratorContext.calculateSurfaceHeight(x, z, biomeSource);
            if (height != 64) { // 64 is our fallback value
                return (float) height;
            }
        }

        // Fallback implementation using simple noise
        // This ensures the mod works even if we can't access the generator
        return 64.0f + (float)(Math.sin(x * 0.01) * 10 + Math.cos(z * 0.01) * 10);
    }

    /**
     * Ensure memory bounds are respected
     */
    private void ensureMemoryBounds() {
        long maxPoints = 100000; // Configurable limit

        while (currentGridPoints.get() > maxPoints && !lruQueue.isEmpty()) {
            ChunkPos oldest = lruQueue.poll();
            if (oldest != null) {
                ChunkHeightGrid removed = gridCache.remove(oldest);
                if (removed != null) {
                    int gridCellsPerChunk = removed.getGridCellCount();
                    currentGridPoints.addAndGet(-(gridCellsPerChunk * gridCellsPerChunk));
                }
            }
        }
    }

    /**
     * Clear all cached data - CRITICAL for world unloading
     * Must be called when switching worlds to prevent cross-world contamination
     */
    public void clearAll() {
        LOGGER.info("BiomePruner: Clearing heightmap cache data for world unload");
        
        // Clear all cached height grids
        gridCache.clear();
        
        // Clear LRU queue
        lruQueue.clear();
        
        // Reset grid points counter
        currentGridPoints.set(0);
        
        // Clear biome source reference to prevent memory leaks
        biomeSource = null;
        
        LOGGER.info("BiomePruner: Heightmap cache cleared successfully");
    }
    
    /**
     * Get cache statistics
     */
    public String getStatistics() {
        return String.format("Heightmap cache - Chunks: %d, Grid points: %d", 
            gridCache.size(), currentGridPoints.get());
    }
}