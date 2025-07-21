package com.example.alexthundercook.biomepruner.datagen;

import com.example.alexthundercook.biomepruner.BiomePrunerMod;
import com.example.alexthundercook.biomepruner.ModBiomes;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.core.HolderLookup;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Generates data/biomepruner/worldgen/biome/red_realm.json at build‑time. */
@EventBusSubscriber(modid = BiomePrunerMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModBiomeProvider extends DatapackBuiltinEntriesProvider {

    private static RegistrySetBuilder buildRegistries() {
        return new RegistrySetBuilder()
                .add(Registries.BIOME, ctx ->
                        ctx.register(ModBiomes.RED_REALM_KEY, ModBiomes.makeRedRealm()));
    }

    public ModBiomeProvider(PackOutput output,
                            CompletableFuture<HolderLookup.Provider> registries) {
        // FOUR‑arg ctor → still wants HolderLookup.Provider, not PatchedRegistries
        super(output, registries, buildRegistries(), Set.of(BiomePrunerMod.MODID));
    }

    @Override public String getName() { return "BiomePruner built‑in biomes"; }

    /* Hook provider into the vanilla data‑gathering run. */
    @SubscribeEvent
    public static void gatherData(GatherDataEvent e) {
        if (!e.includeServer()) return;                          // server‑side data only
        e.getGenerator().addProvider(
                true,
                new ModBiomeProvider(
                        e.getGenerator().getPackOutput(),
                        e.getLookupProvider()));
    }
}
