// File: src/main/java/com/example/alexthundercook/biomepruner/DebugMessenger.java
package com.example.alexthundercook.biomepruner;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class DebugMessenger {

    static void broadcastMicroBiome(MinecraftServer srv,
                                    BlockPos center,
                                    Holder<Biome> replacement,
                                    int cellCount) {

        if (!BiomePrunerConfig.DEBUG_CHAT.get()) return;

        Component coords = Component.literal(
                        center.getX() + " " + center.getY() + " " + center.getZ())
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/tp @s " + center.getX()
                                        + " " + center.getY()
                                        + " " + center.getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport"))));
        String transKey = replacement.unwrapKey()
                .map(k -> "biome." + k.location().getNamespace() + "." + k.location().getPath())
                .orElse("biome.unknown");
        Component biomeName = Component.translatable(transKey)
                .withStyle(ChatFormatting.GREEN);

        Component msg = Component.literal("[BiomePruner] ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("Merged "))
                .append(Component.literal(cellCount + " cells ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("at "))
                .append(coords)
                .append(Component.literal(" into "))
                .append(biomeName);

        List<ServerPlayer> players = srv.getPlayerList().getPlayers();
        for (ServerPlayer p : players) p.sendSystemMessage(msg);
    }

    public static void announceMerge(RegionData data, int qx, int qz) {
        if (!BiomePrunerConfig.DEBUG_CHAT.get()) return;

        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;

        int bx = (qx << 2) + 2;
        int bz = (qz << 2) + 2;
        String tp = "/tp @s " + bx + " " +
                BiomePrunerConfig.SAMPLING_Y_LEVEL.get() + " " + bz;

        Component msg = Component.literal("[BiomePruner] Merged "
                        + data.visited.size() + " cells → ")
                .append(data.vanillaBiome.unwrapKey()
                        .map(ResourceKey::location)
                        .map(Object::toString).orElse("unknown"))
                .withStyle(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Teleport to merge centre"))));

        srv.getPlayerList().broadcastSystemMessage(msg, false);
    }

    private DebugMessenger() {}
}
