package com.example.alexthundercook.biomepruner.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.util.Pos2D;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Handles debug messages for biome replacements
 */
public class DebugMessenger {
    private static final DebugMessenger INSTANCE = new DebugMessenger();

    // Recent messages queue (prevent spam)
    private final ConcurrentLinkedDeque<RecentMessage> recentMessages = new ConcurrentLinkedDeque<>();
    private static final long MESSAGE_COOLDOWN_MS = 1000; // 1 second between similar messages
    private static final int MAX_RECENT_MESSAGES = 100;

    private DebugMessenger() {}

    public static DebugMessenger getInstance() {
        return INSTANCE;
    }

    /**
     * Send a debug message about a biome replacement
     */
    public void sendBiomeReplacementMessage(int x, int y, int z,
                                            Holder<Biome> originalBiome,
                                            Holder<Biome> replacementBiome,
                                            Set<Pos2D> microBiomeRegion) {
        if (!ConfigManager.isDebugMessagesEnabled()) {
            return;
        }

        // Check if we've sent a similar message recently
        if (isRecentlySent(x, z, originalBiome, replacementBiome)) {
            return;
        }

        // Get biome names
        String originalName = getBiomeName(originalBiome);
        String replacementName = getBiomeName(replacementBiome);
        // Convert biome coordinate count to actual block count (each biome coordinate = 4x4 blocks)
        int regionSize = microBiomeRegion.size() * 16;

        // Calculate center of region for teleport (convert biome coordinates to block coordinates)
        int biomeRegionSize = microBiomeRegion.size();
        int centerBiomeX = microBiomeRegion.stream().mapToInt(Pos2D::x).sum() / biomeRegionSize;
        int centerBiomeZ = microBiomeRegion.stream().mapToInt(Pos2D::z).sum() / biomeRegionSize;
        int centerX = centerBiomeX << 2; // Convert to block coordinates
        int centerZ = centerBiomeZ << 2; // Convert to block coordinates

        // Create the chat message
        Component message = createDebugMessage(
                originalName, replacementName, regionSize,
                x, y, z, centerX, centerZ
        );

        // Send to all players
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(message);
            }
        }

        // Record as recent
        recordRecentMessage(x, z, originalBiome, replacementBiome);
    }

    /**
     * Create the formatted debug message with interactive components
     */
    private Component createDebugMessage(String originalBiome, String replacementBiome,
                                         int regionSize, int x, int y, int z,
                                         int centerX, int centerZ) {
        // Base message
        MutableComponent message = Component.literal("[BiomePruner] ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Replaced ")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(originalBiome)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" with ")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(replacementBiome)
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(regionSize))
                        .withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" blocks)")
                        .withStyle(ChatFormatting.WHITE));

        // Create hover text
        Component hoverText = Component.literal("Position: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("X: %d, Y: %d, Z: %d", x, y, z))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\nRegion Center: ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("X: %d, Z: %d", centerX, centerZ))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\n\nClick to teleport to region")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));

        // Add hover event
        HoverEvent hoverEvent = new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                hoverText
        );

        // Add click event (teleport command)
        String teleportCommand = String.format("/biomepruner teleport %d %d", centerX, centerZ);
        ClickEvent clickEvent = new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                teleportCommand
        );

        // Apply events to the message
        message.withStyle(style -> style
                .withHoverEvent(hoverEvent)
                .withClickEvent(clickEvent));

        return message;
    }

    /**
     * Get the display name for a biome
     */
    private String getBiomeName(Holder<Biome> biomeHolder) {
        if (!biomeHolder.isBound()) {
            return "Unknown";
        }

        Optional<ResourceKey<Biome>> biomeKey = biomeHolder.unwrapKey();
        
        if (biomeKey.isPresent()) {
            // Convert minecraft:forest to Forest
            String name = biomeKey.get().location().getPath();
            return capitalizeWords(name.replace('_', ' '));
        }

        return "Unknown";
    }

    /**
     * Capitalize words in a string
     */
    private String capitalizeWords(String str) {
        String[] words = str.split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                if (result.length() > 0) {
                    result.append(" ");
                }
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.toString();
    }

    /**
     * Check if a similar message was sent recently
     */
    private boolean isRecentlySent(int x, int z, Holder<Biome> original, Holder<Biome> replacement) {
        long now = System.currentTimeMillis();

        // Clean old messages
        recentMessages.removeIf(msg -> now - msg.timestamp > MESSAGE_COOLDOWN_MS * 10);

        // Check for similar recent message
        for (RecentMessage recent : recentMessages) {
            if (recent.isNear(x, z, 32) &&
                    recent.original.equals(original) &&
                    recent.replacement.equals(replacement) &&
                    now - recent.timestamp < MESSAGE_COOLDOWN_MS) {
                return true;
            }
        }

        return false;
    }

    /**
     * Record a recent message
     */
    private void recordRecentMessage(int x, int z, Holder<Biome> original, Holder<Biome> replacement) {
        recentMessages.offer(new RecentMessage(x, z, original, replacement, System.currentTimeMillis()));

        // Limit queue size
        while (recentMessages.size() > MAX_RECENT_MESSAGES) {
            recentMessages.poll();
        }
    }

    /**
     * Recent message record
     */
    private record RecentMessage(
            int x, int z,
            Holder<Biome> original,
            Holder<Biome> replacement,
            long timestamp
    ) {
        boolean isNear(int otherX, int otherZ, int distance) {
            int dx = Math.abs(x - otherX);
            int dz = Math.abs(z - otherZ);
            return dx <= distance && dz <= distance;
        }
    }
}