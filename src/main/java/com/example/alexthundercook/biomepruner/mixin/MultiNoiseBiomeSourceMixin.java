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

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@Mixin(MultiNoiseBiomeSource.class)
public class MultiNoiseBiomeSourceMixin {
    
    // One-time debug flags to confirm mixin is working (prevents log spam)
    private static volatile boolean hasLoggedOnce = false;
    private static volatile boolean hasLoggedReplacement = false;
    
    /**
     * Lightweight validation that a biome holder is safe to use
     * Avoids expensive registry operations that caused performance regression
     */
    private boolean isValidForDecoration(Holder<Biome> biomeHolder, int x, int y, int z) {
        // Basic null check
        if (biomeHolder == null) {
            BiomePruner.LOGGER.warn("BiomePruner: Null biome holder at {},{},{}", x, y, z);
            return false;
        }
        
        // Check if biome holder is bound to registry - this is the critical check
        if (!biomeHolder.isBound()) {
            BiomePruner.LOGGER.warn("BiomePruner: Unbound biome holder at {},{},{}", x, y, z);
            return false;
        }
        
        // Check if biome holder has a valid registry key - prevents NPE
        if (biomeHolder.unwrapKey().isEmpty()) {
            BiomePruner.LOGGER.warn("BiomePruner: Biome holder missing registry key at {},{},{}", x, y, z);
            return false;
        }
        
        // If we get here, the biome holder should be safe to use
        // Note: Removed expensive registry ID validation to prevent performance regression
        return true;
    }

    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"), cancellable = true)
    private void modifyGetNoiseBiome(int x, int y, int z, Climate.Sampler sampler,
                                     CallbackInfoReturnable<Holder<Biome>> cir) {
        // Quick check if mod is enabled
        if (!ConfigManager.isEnabled()) {
            return;
        }

        // Input validation - check for extreme coordinates that could cause array index issues  
        if (x < -1000000 || x > 1000000 || y < -1000000 || y > 1000000 || z < -1000000 || z > 1000000) {
            BiomePruner.LOGGER.warn("BiomePruner: Extreme coordinates detected at {},{},{}, skipping modification to prevent index errors", x, y, z);
            return;
        }

        // Validate sampler is not null
        if (sampler == null) {
            BiomePruner.LOGGER.warn("BiomePruner: Null climate sampler at {},{},{}, skipping modification", x, y, z);
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

            // Additional safety check: Ensure we have a valid biome holder
            if (vanillaBiome == null) {
                BiomePruner.LOGGER.warn("BiomePruner: Received null biome at {},{},{}, skipping modification", x, y, z);
                return;
            }

            // Comprehensive biome holder validation for decoration safety
            if (!isValidForDecoration(vanillaBiome, x, y, z)) {
                BiomePruner.LOGGER.warn("BiomePruner: Invalid vanilla biome holder at {},{},{}, skipping modification to prevent decoration errors", x, y, z);
                return;
            }

            // Get the biome smoother instance
            BiomeSmoother smoother = BiomeSmoother.getInstance();

            // Initialize with biome source if needed
            MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource)(Object)this;
            smoother.initializeForBiomeSource(biomeSource);

            // Convert noise coordinates to block coordinates (multiply by 4)
            // getNoiseBiome receives noise coordinates, but our logic works with block coordinates
            // Add overflow protection for coordinate conversion
            int blockX, blockY, blockZ;
            try {
                // Check for potential overflow in left shift operation
                if (x > Integer.MAX_VALUE >> 2 || x < Integer.MIN_VALUE >> 2) {
                    BiomePruner.LOGGER.warn("BiomePruner: Coordinate overflow risk at noise X={}, skipping modification", x);
                    return;
                }
                if (y > Integer.MAX_VALUE >> 2 || y < Integer.MIN_VALUE >> 2) {
                    BiomePruner.LOGGER.warn("BiomePruner: Coordinate overflow risk at noise Y={}, skipping modification", y);
                    return;
                }
                if (z > Integer.MAX_VALUE >> 2 || z < Integer.MIN_VALUE >> 2) {
                    BiomePruner.LOGGER.warn("BiomePruner: Coordinate overflow risk at noise Z={}, skipping modification", z);
                    return;
                }
                
                blockX = x << 2;
                blockY = y << 2;
                blockZ = z << 2;
            } catch (Exception e) {
                BiomePruner.LOGGER.error("BiomePruner: Coordinate conversion error at {},{},{}", x, y, z, e);
                return;
            }

            // Get modified biome - this ALWAYS returns a valid biome
            // Either the vanilla biome or the replacement, never null
            Holder<Biome> modifiedBiome = smoother.getModifiedBiome(blockX, blockY, blockZ,
                    vanillaBiome, (MultiNoiseBiomeSource)(Object)this, sampler);

            // Additional safety check: Ensure replacement is valid
            if (modifiedBiome == null) {
                BiomePruner.LOGGER.warn("BiomePruner: Got null replacement biome at {},{},{}, using vanilla", x, y, z);
                return;
            }

            // Comprehensive validation of replacement biome for decoration safety
            if (!isValidForDecoration(modifiedBiome, x, y, z)) {
                BiomePruner.LOGGER.warn("BiomePruner: Invalid replacement biome holder at {},{},{}, using vanilla to prevent decoration errors", x, y, z);
                return;
            }

            // Log biome replacements with registry IDs for debugging decoration issues
            if (!modifiedBiome.equals(vanillaBiome)) {
                // Log first successful replacement (rate limited to prevent spam)
                if (!hasLoggedReplacement && ConfigManager.isPerformanceLoggingEnabled()) {
                    hasLoggedReplacement = true;
                    BiomePruner.LOGGER.info("BiomePruner successfully replaced first micro biome (noise coords: {},{},{})", x, y, z);
                }
                
                // Note: Removed expensive registry ID comparison logging to prevent performance regression
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