/* RegionExplorer.java – biomepruner 1.21.1
 * Updated 2025‑07‑19 (b‑4)
 *   • Prevents “Merged 0 cells” spam by deciding `isMicro`
 *     before finalisation clears the visited set.
 *   • Includes the earlier merge‑robust flood‑fill fixes.
 */
package com.example.alexthundercook.biomepruner;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

public enum RegionExplorer {
    INSTANCE;

    /* ------------------------------------------------------------- */
    /*  Public entry points                                          */
    /* ------------------------------------------------------------- */

    public static void schedule(MultiNoiseBiomeSource src,
                                int qx, int qz,
                                Climate.Sampler sampler) {

        long chunkKey = PackedPos.pack(qx >> 4, qz >> 4);
        RegionCache.IN_FLIGHT.computeIfAbsent(chunkKey, k ->
                CompletableFuture.runAsync(
                                () -> explore(src, qx, qz, sampler),
                                ForkJoinPool.commonPool())
                        .whenComplete((v, t) -> RegionCache.IN_FLIGHT.remove(k))
        );
    }

    public static void exploreSync(MultiNoiseBiomeSource src,
                                   int qx, int qz,
                                   Climate.Sampler sampler) {
        explore(src, qx, qz, sampler);
    }

    /* ------------------------------------------------------------- */
    /*  Private implementation                                       */
    /* ------------------------------------------------------------- */

    private static void explore(MultiNoiseBiomeSource src,
                                int startQx, int startQz,
                                Climate.Sampler sampler) {

        ExplorationContext.enter();
        try {
            final RegionManager manager = RegionManager.get();
            final int maxCells          = BiomePrunerConfig.MAX_REGION_CELLS.get();
            final int samplingY         = BiomePrunerConfig.SAMPLING_Y_LEVEL.get() >> 2;
            final Holder<Biome> start   = src.getNoiseBiome(startQx, samplingY, startQz, sampler);

            RegionData region = new RegionData(start, PackedPos.pack(startQx, startQz));
            region.workers.incrementAndGet();

            /* ----- cooperative flood‑fill -------------------------------- */
            while (true) {
                region = region.findRoot();                       // hop to canonical

                if (region.visited.size() > maxCells) break;

                Long cell = region.frontier.poll();
                if (cell == null) break;

                int cx = PackedPos.unpackX(cell), cz = PackedPos.unpackZ(cell);

                for (int[] d : DIRS) {
                    int nx = cx + d[0], nz = cz + d[1];
                    long np = PackedPos.pack(nx, nz);

                    if (!region.visited.add(np)) continue;

                    if (src.getNoiseBiome(nx, samplingY, nz, sampler).equals(start)) {
                        RegionData canonical = manager.claimOrMerge(np, region);
                        canonical.frontier.add(np);
                        if (canonical != region) region = canonical;
                    }
                }
            }

            RegionData root     = region.findRoot();
            boolean    isMicro  = root.visited.size() <= maxCells;   // decide *before* clear
            int        remaining = root.workers.decrementAndGet();

            if (remaining == 0) {                                    // last worker out
                manager.finaliseRegion(root, maxCells, src, sampler);

                if (BiomePrunerConfig.DEBUG_CHAT.get() && isMicro) {
                    DebugMessenger.announceMerge(root, startQx, startQz);
                }
            }

        } finally {
            ExplorationContext.exit();
        }
    }

    private static final int[][] DIRS = { {1,0}, {-1,0}, {0,1}, {0,-1} };
}
