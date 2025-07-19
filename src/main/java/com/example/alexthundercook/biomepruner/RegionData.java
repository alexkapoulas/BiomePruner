/* RegionData.java */
package com.example.alexthundercook.biomepruner;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable, thread‑safe state for one contiguous region being explored.
 */
public final class RegionData {
    /** Start quart cell of this region */
    final long origin;

    /** Frontier of unexplored quart cells */
    final Queue<Long> frontier = new ConcurrentLinkedQueue<>();

    /** All visited cells in *this* RegionData */
    final Set<Long> visited = ConcurrentHashMap.newKeySet();

    /** Active worker threads exploring this region */
    final AtomicInteger workers = new AtomicInteger(0);

    /** Vanilla biome of the region (set by the first thread) */
    final Holder<Biome> vanillaBiome;

    volatile RegionData parent = this;   // points to canonical owner

    RegionData findRoot() {
        RegionData r = this;
        while (r.parent != r) r = r.parent;
        return r;                        // optional: path‑compress here
    }

    RegionData(Holder<Biome> biome, long startCell) {
        this.origin = startCell;
        this.vanillaBiome = biome;
        frontier.add(startCell);
        visited.add(startCell);
    }
}
