/* BiomePrunerMod.java */
package com.example.alexthundercook.biomepruner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


@Mod(BiomePrunerMod.MODID)
public final class BiomePrunerMod {
    public static Logger LOGGER = LogManager.getLogger(BiomePrunerMod.MODID);
    public static final String MODID = "biomepruner";

    public BiomePrunerMod(IEventBus modEventBus, ModContainer modContainer) {
        ModBiomes.bootstrap(modEventBus);          // ❶ use new bootstrap
        NeoForge.EVENT_BUS.register(this);         // event handlers
        modContainer.registerConfig(ModConfig.Type.COMMON,
                BiomePrunerConfig.SPEC, MODID + "-common.toml");
    }

    /* ---------------- RegistryAccess capture ------------------ */
    @SubscribeEvent
    public void cacheRegistry(ServerStartingEvent e) {
        BiomeUtils.setRegistryAccess(e.getServer().registryAccess());
    }

}
