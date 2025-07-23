package com.example.alexthundercook.biomepruner.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.alexthundercook.biomepruner.core.ChunkGeneratorAccess;
import com.example.alexthundercook.biomepruner.core.GeneratorContext;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin implements ChunkGeneratorAccess {

    @Unique
    private RandomState biomepruner$storedRandomState;

    /**
     * Capture when the generator is constructed
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onConstruct(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, CallbackInfo ci) {
        // Register this generator with its biome source for later access
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator)(Object)this;
        // RandomState will be set later via the accessor method
    }

    /**
     * Intercept getBaseHeight to capture RandomState when it's available
     */
    @Inject(method = "getBaseHeight", at = @At("HEAD"))
    private void onGetBaseHeight(int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types type, 
                                net.minecraft.world.level.LevelHeightAccessor heightAccessor, 
                                RandomState randomState, CallbackInfoReturnable<Integer> cir) {
        if (this.biomepruner$storedRandomState == null && randomState != null) {
            this.biomepruner$setRandomState(randomState);
            // Note: Debug logging removed to prevent log spam during height calculations
        }
    }

    @Override
    @Unique
    public RandomState biomepruner$getRandomState() {
        return this.biomepruner$storedRandomState;
    }

    @Override
    @Unique
    public Climate.Sampler biomepruner$getClimateSampler() {
        RandomState state = this.biomepruner$getRandomState();
        return state != null ? state.sampler() : null;
    }

    /**
     * Store the RandomState when it's provided
     */
    @Unique
    public void biomepruner$setRandomState(RandomState randomState) {
        this.biomepruner$storedRandomState = randomState;
        if (randomState != null) {
            NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator)(Object)this;
            GeneratorContext.registerChunkGenerator(self.getBiomeSource(), self, randomState);
        }
    }
}