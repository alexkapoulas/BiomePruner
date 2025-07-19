/*
 * RegionManager.java – sharded owner map + alias‑based merges
 * Updated 2025‑07‑19
 *   • Added Thread.yield() back‑off inside claimOrMerge’s spin loop.
 *   • Clears heavyweight structures for LARGE regions immediately after finalisation.
 */
package com.example.alexthundercook.biomepruner;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.util.HashMap;
import java.util.Map;

public final class RegionManager {

    private static final RegionManager INSTANCE = new RegionManager();

    /* ------------------------------------------------------------------ */
    /* 1)  Sharded owner table                                            */
    /* ------------------------------------------------------------------ */

    private static final int SHARDS = 256;

    @SuppressWarnings("unchecked")
    private final Long2ObjectOpenHashMap<RegionData>[] owners = new Long2ObjectOpenHashMap[SHARDS];
    private final Object[] locks = new Object[SHARDS];

    @SuppressWarnings("unchecked")
    private RegionManager() {
        for (int i = 0; i < SHARDS; i++) {
            owners[i] = new Long2ObjectOpenHashMap<>(1024);
            locks[i] = new Object();
        }
    }

    public static RegionManager get() { return INSTANCE; }

    private static int shard(long cell) {
        return (int) (cell ^ (cell >>> 32)) & (SHARDS - 1);
    }

    private RegionData putIfAbsent(long cell, RegionData data) {
        int s = shard(cell);
        synchronized (locks[s]) {
            return owners[s].putIfAbsent(cell, data);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2)  Public API – claimOrMerge                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Claim {@code cell} for {@code current}.  If another region already owns the cell
     * we alias‑merge via union‑find.  Callers that need the canonical root must call
     * {@link RegionData#findRoot()} afterwards.
     */
    public RegionData claimOrMerge(long cell, RegionData current) {
        for (;;) {
            RegionData curRoot = current.findRoot();
            RegionData existing = putIfAbsent(cell, curRoot);
            if (existing == null) return curRoot;              // fast‑path

            RegionData exRoot = existing.findRoot();
            if (exRoot == curRoot) return curRoot;             // already merged

            /* deterministic lock order to avoid AB‑BA */
            RegionData first  = firstByOrigin(exRoot, curRoot);
            RegionData second = (first == exRoot ? curRoot : exRoot);

            synchronized (first) {
                synchronized (second) {
                    first.frontier.addAll(second.frontier);
                    first.visited.addAll(second.visited);
                    first.workers.addAndGet(second.workers.get());

                    second.parent = first;
                    second.visited.clear();
                    second.frontier.clear();
                }
            }
            /* back‑off so a different thread can make observable progress */
            Thread.yield();   // NEW
        }
    }

    private static RegionData firstByOrigin(RegionData a, RegionData b) {
        return Long.compareUnsigned(a.origin, b.origin) <= 0 ? a : b;
    }

    /* ------------------------------------------------------------------ */
    /* 3)  Region finalisation                                            */
    /* ------------------------------------------------------------------ */

    public void finaliseRegion(RegionData data,
                               int maxCells,
                               MultiNoiseBiomeSource src,
                               Climate.Sampler sampler) {

        boolean micro = data.visited.size() <= maxCells;
        if (micro) {
            Holder<Biome> replacement = chooseReplacement(data, src, sampler);
            for (long cell : data.visited) RegionCache.MICRO.put(cell, replacement);
        } else {
            for (long cell : data.visited) RegionCache.LARGE.add(cell);
            /* heavy data structures no longer needed once tagged LARGE */
            data.visited.clear();            // NEW
            data.frontier.clear();           // NEW
        }
    }

    /* ------------------------------------------------------------------ */
    /* 4)  Replacement selection (unchanged)                              */
    /* ------------------------------------------------------------------ */

    private static Holder<Biome> chooseReplacement(RegionData data,
                                                   MultiNoiseBiomeSource src,
                                                   Climate.Sampler sampler) {

        final int samplingY = BiomePrunerConfig.SAMPLING_Y_LEVEL.get() >> 2;
        final int[][] DIRS  = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        Map<Holder<Biome>, Integer> counts = new HashMap<>();

        for (long cell : data.visited) {
            int cx = PackedPos.unpackX(cell);
            int cz = PackedPos.unpackZ(cell);

            for (int[] d : DIRS) {
                int nx = cx + d[0], nz = cz + d[1];
                long neighbour = PackedPos.pack(nx, nz);
                if (data.visited.contains(neighbour)) continue;

                Holder<Biome> biome = src.getNoiseBiome(nx, samplingY, nz, sampler);
                if (biome.equals(data.vanillaBiome))          continue;
                if (BiomePrunerConfig.isBlacklisted(biome))  continue;

                counts.merge(biome, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(data.vanillaBiome);
    }

    /* ------------------------------------------------------------------ */
    /* 5)  Debug passthrough                                              */
    /* ------------------------------------------------------------------ */

    public static Holder<Biome> chooseReplacementForDebug(MultiNoiseBiomeSource src,
                                                          Climate.Sampler sampler,
                                                          java.util.Set<Long> visited,
                                                          Holder<Biome> vanilla) {

        RegionData stub = new RegionData(vanilla, 0L);
        stub.visited.addAll(visited);
        return chooseReplacement(stub, src, sampler);
    }
}
