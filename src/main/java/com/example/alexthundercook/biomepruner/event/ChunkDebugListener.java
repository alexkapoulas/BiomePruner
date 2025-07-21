package com.example.alexthundercook.biomepruner.event;

import com.example.alexthundercook.biomepruner.BiomePrunerMod;
import com.example.alexthundercook.biomepruner.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@EventBusSubscriber(modid = BiomePrunerMod.MODID)
public class ChunkDebugListener {
    private static final Queue<ChunkPos> PENDING = new ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel().isClientSide()) return;
        LevelChunk c = (LevelChunk) e.getChunk();
        if (c.getFullStatus() != FullChunkStatus.FULL) return; // still baking
        //BiomePrunerMod.LOGGER.warn("Chunk loaded: {}", c.getPos());
        PENDING.add(c.getPos());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (e.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) e.getLevel();

        for (int i = 0; i < 256 && !PENDING.isEmpty(); i++) { // 4 columns / tick
            ChunkPos cp = PENDING.poll();
            LevelChunk chunk = level.getChunk(cp.x, cp.z);   // ← ints, not ChunkPos
            paint(chunk);
        }
    }

    private static void paint(LevelChunk chunk) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ServerLevel level = (ServerLevel) chunk.getLevel();
        ChunkPos cp = chunk.getPos();

        //int sampleY = level.getMaxBuildHeight();
        int sampleY = 128;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = cp.getMinBlockX() + dx;
                int wz = cp.getMinBlockZ() + dz;
                //p.set(wx, sampleY, wz);
                p.set(wx, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz), wz);
                if (level.getBiome(p).is(ModBiomes.RED_REALM_KEY)) {
                    //BiomePrunerMod.LOGGER.warn("Painting chunk at {},{} with RED_WOOL", wx, wz);
                    //p.set(wx, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wz, wz), wz);
                    //p.set(wx, 128, wz);
                    level.setBlock(p, Blocks.RED_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }
}

