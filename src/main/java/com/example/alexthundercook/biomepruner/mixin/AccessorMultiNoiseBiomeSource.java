// File: src/main/java/com/example/alexthundercook/biomepruner/mixin/AccessorMultiNoiseBiomeSource.java
package com.example.alexthundercook.biomepruner.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiNoiseBiomeSource.class)
public interface AccessorMultiNoiseBiomeSource {
    @Invoker("getNoiseBiome")
    Holder<Biome> biomepruner$callRaw(int x, int y, int z, Climate.Sampler sampler);
}
