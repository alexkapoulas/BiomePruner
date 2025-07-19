/* RegionAnalyzer.java */
package com.example.alexthundercook.biomepruner;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.jetbrains.annotations.Nullable;

/**
 * Blocking, stand‑alone flood‑fill for tests and diagnostics.
 * **Respects the biome blacklist** – skips analysis completely in that case.
 */
public final class RegionAnalyzer {

    /**
     * Immutable result snapshot.
     *
     * @param size        number of quart cells in the contiguous region,
     *                    or {@code -1} when analysis was skipped due to blacklist
     * @param replacement non‑null IFF <em>micro‑biome</em> AND not blacklisted
     */
    public record RegionInfo(int size, @Nullable Holder<Biome> replacement) {}

    private RegionAnalyzer() {}

    /* ------------------------------------------------------------------ */

    public static RegionInfo analyse(ServerLevel level, int blockX, int blockZ) {
        int qx = blockX >> 2;
        int qz = blockZ >> 2;

        MultiNoiseBiomeSource src = (MultiNoiseBiomeSource)
                level.getChunkSource().getGenerator().getBiomeSource();

        Climate.Sampler sampler = level.getChunkSource()
                .randomState()
                .sampler();

        return analyse(src, qx, qz, sampler);
    }

    public static RegionInfo analyse(MultiNoiseBiomeSource src,
                                     int quartX, int quartZ,
                                     Climate.Sampler sampler) {

        ExplorationContext.enter();                 // prevent recursive mixin trips
        try {
            final int samplingY = BiomePrunerConfig.SAMPLING_Y_LEVEL.get() >> 2;
            final Holder<Biome> target =
                    src.getNoiseBiome(quartX, samplingY, quartZ, sampler);

            /* ---------- blacklist: analysis aborted ------------------- */
            if (BiomePrunerConfig.isBlacklisted(target)) {
                return new RegionInfo(-1, null);
            }

            /* ---------- flood‑fill ----------------------------------- */
            final int maxCells = BiomePrunerConfig.MAX_REGION_CELLS.get();
            LongSet visited = new LongOpenHashSet();
            LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();

            long start = PackedPos.pack(quartX, quartZ);
            visited.add(start);
            frontier.enqueue(start);

            while (!frontier.isEmpty()) {
                long cell = frontier.dequeueLong();
                int cx = PackedPos.unpackX(cell);
                int cz = PackedPos.unpackZ(cell);

                for (int[] d : DIRS) {
                    int nx = cx + d[0], nz = cz + d[1];
                    long np = PackedPos.pack(nx, nz);

                    if (visited.add(np)) {
                        Holder<Biome> b =
                                src.getNoiseBiome(nx, samplingY, nz, sampler);
                        if (b.equals(target)) frontier.enqueue(np);
                    }
                }
            }

            int size = visited.size();
            Holder<Biome> repl = null;
            if (size <= maxCells) {
                repl = RegionManager.chooseReplacementForDebug(src, sampler, visited, target);
            }
            return new RegionInfo(size, repl);

        } finally {
            ExplorationContext.exit();
        }
    }

    private static final int[][] DIRS = { {1,0}, {-1,0}, {0,1}, {0,-1} };
}
