package com.example.alexthundercook.biomepruner.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.alexthundercook.biomepruner.BiomePruner;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.core.BiomeSmoother;
import com.example.alexthundercook.biomepruner.core.BiomeAnalysis;
import com.example.alexthundercook.biomepruner.core.ExecutionContext;

@Mixin(MultiNoiseBiomeSource.class)
public class MultiNoiseBiomeSourceMixin {
    
    // One-time debug flags to confirm mixin is working (prevents log spam)
    private static volatile boolean hasLoggedOnce = false;
    private static volatile boolean hasLoggedReplacement = false;

    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"), cancellable = true)
    private void modifyGetNoiseBiome(int x, int y, int z, Climate.Sampler sampler,
                                     CallbackInfoReturnable<Holder<Biome>> cir) {
        // Quick check if mod is enabled
        if (!ConfigManager.isEnabled()) {
            return;
        }

        // One-time debug log to confirm mixin is working (rate limited to prevent spam)
        if (!hasLoggedOnce && ConfigManager.isPerformanceLoggingEnabled()) {
            hasLoggedOnce = true;
            BiomePruner.LOGGER.info("BiomePruner MultiNoiseBiomeSourceMixin is active and processing biomes");
        }

        // Check if we're already in our own code to prevent infinite recursion
        if (ExecutionContext.isInModCode()) {
            return;
        }

        try {
            // Mark that we're entering mod code
            ExecutionContext.enter();

            // Get the vanilla biome that was already calculated
            Holder<Biome> vanillaBiome = cir.getReturnValue();

            // Get the biome smoother instance
            BiomeSmoother smoother = BiomeSmoother.getInstance();

            // Initialize with biome source if needed
            MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource)(Object)this;
            smoother.initializeForBiomeSource(biomeSource);

            // Convert noise coordinates to block coordinates (multiply by 4)
            // getNoiseBiome receives noise coordinates, but our logic works with block coordinates
            int blockX = x << 2;
            int blockY = y << 2;
            int blockZ = z << 2;

            // Get modified biome - this ALWAYS returns a valid biome
            // Either the vanilla biome or the replacement, never null
            Holder<Biome> modifiedBiome = smoother.getModifiedBiome(blockX, blockY, blockZ,
                    vanillaBiome, (MultiNoiseBiomeSource)(Object)this, sampler);

            // Log first successful replacement (rate limited to prevent spam)
            if (!hasLoggedReplacement && !modifiedBiome.equals(vanillaBiome) && ConfigManager.isPerformanceLoggingEnabled()) {
                hasLoggedReplacement = true;
                BiomePruner.LOGGER.info("BiomePruner successfully replaced first micro biome (noise coords: {},{},{})", x, y, z);
            }

            // Always set the return value - we guarantee correct biome every time
            // No "fix it later" - we return the correct biome NOW
            cir.setReturnValue(modifiedBiome);
        } catch (Throwable t) {
            BiomePruner.LOGGER.error("Critical error in biome modification at {},{},{}", x, y, z, t);
            // On catastrophic error, we already have vanilla biome in the return value
        } finally {
            // Always exit the context
            ExecutionContext.exit();
        }
    }
}