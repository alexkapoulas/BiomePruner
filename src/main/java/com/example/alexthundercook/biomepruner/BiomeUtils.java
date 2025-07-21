/* BiomeUtils.java */
package com.example.alexthundercook.biomepruner;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

/** Convenience helpers for accessing biomes at runtime. */
public final class BiomeUtils {
    private static RegistryAccess REGISTRY_ACCESS;

    private BiomeUtils() {}

    /** Called once (per world) from <code>ServerStartedEvent</code>. */
    public static void setRegistryAccess(RegistryAccess access) {
        REGISTRY_ACCESS = access;
    }

    /** Null until the BIOME registry is ready. */
    @Nullable
    public static Holder<Biome> getRedRealm() {
        if (REGISTRY_ACCESS == null) return null;
        Registry<Biome> reg = REGISTRY_ACCESS.registryOrThrow(Registries.BIOME);
        return reg.getHolder(ModBiomes.RED_REALM_KEY).orElse(null);
    }
}
