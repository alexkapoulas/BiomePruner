/* biomepruner – AlexThundercook
 * Updated 2025‑07‑19 (b‑3 hot‑fix)
 *
 *  ✦ Stops short‑circuiting *all* CLIENT‑dist threads.
 *    We now skip only the Render thread (and its alt‑names).
 *  ✦ Everything else unchanged: one synchronous flood‑fill per chunk
 *    → micro‑biomes merge before the chunk is baked
 *    → no runaway CPU burn.
 *
 *  Minecraft 1.21.1, NeoForge 21.1.193
 */
package com.example.alexthundercook.biomepruner.mixin;

import com.example.alexthundercook.biomepruner.*;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = MultiNoiseBiomeSource.class, priority = 2000)
public abstract class MixinMultiNoiseBiomeSource {

    /* --------------------------------------------------------------- */
    /*  Utility: are we on the graphics thread?                        */
    /* --------------------------------------------------------------- */
    private static boolean skipForRenderThread() {
        String n = Thread.currentThread().getName();
        return n.equals("Render thread")      // vanilla
                || n.equals("Game Thread")        // some launchers rename it
                || n.startsWith("LWJGL")          // edge‑cases
                || n.contains("Graphics");        // modded edge‑cases
    }

    /* --------------------------------------------------------------- */
    /*  Main intercept                                                 */
    /* --------------------------------------------------------------- */
    @Inject(method = "getNoiseBiome",
            at = @At("HEAD"), cancellable = true, remap = true)
    private void biomepruner$intercept(int qx, int qy, int qz,
                                       Climate.Sampler sampler,
                                       CallbackInfoReturnable<Holder<Biome>> cir) {

        /* ---------- 0) Skip only the render thread ------------------- */
        if (skipForRenderThread()) return;

        /* ---------- 1) Re‑entry guard (prevents infinite recursion) --- */
        if (ExplorationContext.active()) return;

        /* ---------- 2) Vanilla biome under guard --------------------- */
        Holder<Biome> vanilla;
        ExplorationContext.enter();
        try {
            vanilla = ((MultiNoiseBiomeSource)(Object)this)
                    .getNoiseBiome(qx, qy, qz, sampler);
        } finally {
            ExplorationContext.exit();
        }

        /* ---------- 3) Black‑list passthrough ------------------------ */
        if (BiomePrunerConfig.isBlacklisted(vanilla)) {
            cir.setReturnValue(vanilla);
            return;
        }

        /* ---------- 4) Cached micro‑biome replacement ---------------- */
        long cellKey  = PackedPos.pack(qx,     qz);
        Holder<Biome> cached = RegionCache.MICRO.get(cellKey);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        /* ---------- 5) Known LARGE region? --------------------------- */
        if (RegionCache.LARGE.contains(cellKey)) return; // vanilla ok

        /* ---------- 6) One‑per‑chunk synchronous exploration --------- */
        long chunkKey = PackedPos.pack(qx >> 4, qz >> 4);
        CompletableFuture<Void> sentinel = new CompletableFuture<>();

        if (RegionCache.IN_FLIGHT.putIfAbsent(chunkKey, sentinel) == null) {
            try {
                RegionExplorer.exploreSync((MultiNoiseBiomeSource)(Object)this,
                        qx, qz, sampler);
            } finally {
                RegionCache.IN_FLIGHT.remove(chunkKey);
            }

            Holder<Biome> now = RegionCache.MICRO.get(cellKey);
            if (now != null) cir.setReturnValue(now);
            return;   // either we set a replacement or leave vanilla
        }

        /* ---------- 7) Another thread is scanning – fall through ----- */
        // We return vanilla for now; cache will serve future look‑ups.
    }
}
