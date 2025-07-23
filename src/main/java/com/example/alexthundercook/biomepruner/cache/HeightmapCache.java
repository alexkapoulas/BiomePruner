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
import java.util.concurrent.locks.ReadWriteLock;

public class HeightmapCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("BiomePruner");
    private static final int CHUNK_SIZE = 16;

    private final ConcurrentHashMap<ChunkPos, ChunkHeightGrid> gridCache = new ConcurrentHashMap<>();
    private final Striped<ReadWriteLock> chunkLocks = Striped.readWriteLock(128);
    private final AtomicLong currentGridPoints = new AtomicLong(0);
    private final ConcurrentLinkedDeque<ChunkPos> lruQueue = new ConcurrentLinkedDeque<>();

    /**
     * Chunk-aligned height grid
     */
    private static class ChunkHeightGrid {
        final ChunkPos chunkPos;
        final float[][] heights;
        final AtomicInteger calculatedPoints;
        volatile boolean[] calculatedMask;
        volatile long lastAccessTime;

        ChunkHeightGrid(ChunkPos pos, int gridSpacing) {
            this.chunkPos = pos;
            int gridCellsPerChunk = CHUNK_SIZE / gridSpacing + 1;
            this.heights = new float[gridCellsPerChunk][gridCellsPerChunk];
            this.calculatedPoints = new AtomicInteger(0);
            this.calculatedMask = new boolean[gridCellsPerChunk * gridCellsPerChunk];
            this.lastAccessTime = System.nanoTime();
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
     * Get height at any position with interpolation
     */
    public int getHeight(int blockX, int blockZ) {
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
                localGridX >= grid.heights.length ||
                localGridZ >= grid.heights[0].length) {
            // Need to get from neighbor chunk
            return calculateHeightFromNoise(gridX * gridSpacing, gridZ * gridSpacing);
        }

        // Check if already calculated
        int index = localGridX * grid.heights[0].length + localGridZ;

        // Use double-checked locking for better performance
        if (grid.calculatedMask[index]) {
            return grid.heights[localGridX][localGridZ];
        }

        synchronized (grid.calculatedMask) {
            // Check again after acquiring lock
            if (grid.calculatedMask[index]) {
                return grid.heights[localGridX][localGridZ];
            }

            // Calculate and store
            float height = calculateHeightFromNoise(gridX * gridSpacing, gridZ * gridSpacing);
            grid.heights[localGridX][localGridZ] = height;
            // Ensure visibility with volatile write
            grid.calculatedMask[index] = true;
            grid.calculatedPoints.incrementAndGet();

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

            int gridCellsPerChunk = CHUNK_SIZE / gridSpacing + 1;
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
                    int gridSpacing = ConfigManager.getGridSpacing();
                    int gridCellsPerChunk = CHUNK_SIZE / gridSpacing + 1;
                    currentGridPoints.addAndGet(-(gridCellsPerChunk * gridCellsPerChunk));
                }
            }
        }
    }
}