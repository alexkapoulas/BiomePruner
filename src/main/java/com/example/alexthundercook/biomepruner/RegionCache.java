/* RegionCache.java */
package com.example.alexthundercook.biomepruner;

import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.*;

public final class RegionCache {
    /** MICRO ⇒ replacement biome */
    public static final Long2ObjectMap<Holder<Biome>> MICRO =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    /** LARGE ⇒ sentinel set */
    public static final LongSet LARGE =
            LongSets.synchronize(new LongOpenHashSet());

    /** Chunk‑based de‑duplication of exploration tasks */
    public static final ConcurrentMap<Long, CompletableFuture<Void>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private RegionCache() {}
}
