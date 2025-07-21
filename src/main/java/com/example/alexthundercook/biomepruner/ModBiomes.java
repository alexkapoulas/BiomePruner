package com.example.alexthundercook.biomepruner;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.bus.api.IEventBus;   // still used by BiomePrunerMod

/** Static helpers – no DeferredRegister for datapack registries. */
public final class ModBiomes {
    private ModBiomes() {}

    /** biomepruner:red_realm for look‑ups, tags, /locate, etc. */
    public static final ResourceKey<Biome> RED_REALM_KEY = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(BiomePrunerMod.MODID, "red_realm"));

    /** Left so existing call‑sites compile – now a no‑op. */
    public static void bootstrap(IEventBus bus) {
        BiomePrunerMod.LOGGER.debug("ModBiomes.bootstrap(): datagen JSON handles biome registration");
    }

    /** Re‑usable builder – shared by datagen and unit tests. */
    public static Biome makeRedRealm() {
        BiomeSpecialEffects fx = new BiomeSpecialEffects.Builder()
                .waterColor(0xFF0000).waterFogColor(0x7F0000)
                .fogColor(0x4F0000).skyColor(0xFF0000)
                .build();

        return new Biome.BiomeBuilder()
                .hasPrecipitation(false)
                .temperature(1.0F).downfall(0.0F)
                .specialEffects(fx)
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build();
    }
}
