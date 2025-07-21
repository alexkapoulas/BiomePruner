/* BiomePrunerConfig.java */
package com.example.alexthundercook.biomepruner;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * All configurable values for the Biome Pruner mod.
 */
public final class BiomePrunerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue      MAX_REGION_CELLS;
    public static final ModConfigSpec.IntValue      SAMPLING_Y_LEVEL;
    public static final ModConfigSpec.BooleanValue  DEBUG_CHAT;
    public static final ModConfigSpec.BooleanValue  USE_DEBUG_BIOME;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_BLACKLIST;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        MAX_REGION_CELLS = b.comment(
                        "Maximum quart‑cells before a region stops being a micro‑biome")
                .defineInRange("maxRegionCells", 512, 1, Integer.MAX_VALUE);

        SAMPLING_Y_LEVEL = b.comment(
                        "Block‑Y used for 2‑D biome sampling (converted to quart coords internally)")
                .defineInRange("samplingYLevel", 64, 0, 320);

        DEBUG_CHAT = b.comment(
                        "Emit clickable chat when micro‑biomes are merged")
                .define("debugChat", true);

        USE_DEBUG_BIOME = b.comment(
                            "Forces the use of the Red Realm debug biome for all pruned regions.")
                .define("useDebugBiome", false);

        BIOME_BLACKLIST = b.comment(
                        "List of biome resource locations that SHALL NOT be analysed or replaced\n" +
                                "Example:  [ \"minecraft:mushroom_fields\", \"minecraft:deep_dark\" ]")
                .defineList("blacklist",
                        List.of("minecraft:mushroom_fields"),
                        o -> o instanceof String);

        SPEC = b.build();
    }

    private BiomePrunerConfig() {}

    /* ------------------------------------------------------------------ */

    /**
     * @return {@code true} when {@code biome} appears in {@code blacklist}.
     */
    public static boolean isBlacklisted(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(ResourceKey::location)
                .map(ResourceLocation::toString)
                .map(id -> BIOME_BLACKLIST.get().contains(id))
                .orElse(false);
    }
}
