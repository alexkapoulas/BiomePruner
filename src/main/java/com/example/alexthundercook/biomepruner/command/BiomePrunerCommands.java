package com.example.alexthundercook.biomepruner.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import com.example.alexthundercook.biomepruner.BiomePruner;
import com.example.alexthundercook.biomepruner.cache.BiomeRegionCache;
import com.example.alexthundercook.biomepruner.config.ConfigManager;
import com.example.alexthundercook.biomepruner.core.BiomeSmoother;
import com.example.alexthundercook.biomepruner.core.BiomeAnalysis;
import com.example.alexthundercook.biomepruner.core.ChunkGeneratorAccess;
import com.example.alexthundercook.biomepruner.performance.PerformanceTracker;

import java.util.Map;

/**
 * Commands for BiomePruner mod
 */
public class BiomePrunerCommands {

    /**
     * Register all commands
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> biomepruner = Commands.literal("biomepruner")
                .requires(source -> source.hasPermission(2)); // Require op permission

        // Check command (debug biome info at position)
        biomepruner.then(Commands.literal("check")
                .executes(context -> executeCheck(context.getSource()))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> executeCheck(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "y"),
                                                IntegerArgumentType.getInteger(context, "z")))))));

        // Stats command
        biomepruner.then(Commands.literal("stats")
                .executes(context -> executeStats(context.getSource())));

        // Performance command
        biomepruner.then(Commands.literal("performance")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(context -> executePerformance(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "seconds"))))
                .executes(context -> executePerformance(context.getSource(), 60))); // Default 60 seconds

        // Teleport command (for debug messages)
        biomepruner.then(Commands.literal("teleport")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> executeTeleport(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "x"),
                                        IntegerArgumentType.getInteger(context, "z"))))));

        // Reset performance data
        biomepruner.then(Commands.literal("reset")
                .executes(context -> executeReset(context.getSource())));
                
        // Clear cache command for testing
        biomepruner.then(Commands.literal("clearcache")
                .requires(source -> source.hasPermission(2))
                .executes(context -> executeClearCache(context.getSource())));

        dispatcher.register(biomepruner);
    }

    /**
     * Execute check command - show biome info at position
     */
    private static int executeCheck(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            BlockPos pos = player.blockPosition();
            return executeCheck(source, pos.getX(), pos.getY(), pos.getZ());
        }

        source.sendFailure(Component.literal("This command can only be used by players without coordinates")
                .withStyle(ChatFormatting.RED));
        return 0;
    }

    /**
     * Execute check command with specific coordinates
     */
    private static int executeCheck(CommandSourceStack source, int x, int y, int z) {
        // Get the server level
        ServerLevel level = source.getLevel();

        // Get the chunk generator
        var chunkGen = level.getChunkSource().getGenerator();

        // Check if it's a noise-based generator with multi-noise biome source
        if (!(chunkGen instanceof NoiseBasedChunkGenerator noiseGen)) {
            source.sendFailure(Component.literal("This command only works with noise-based world generation")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!(noiseGen.getBiomeSource() instanceof MultiNoiseBiomeSource biomeSource)) {
            source.sendFailure(Component.literal("This command only works with multi-noise biome sources")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Get the climate sampler
        var climateSampler = ((ChunkGeneratorAccess)noiseGen).biomepruner$getClimateSampler();
        if (climateSampler == null) {
            source.sendFailure(Component.literal("Unable to access climate sampler")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Get vanilla biome at position
        Holder<Biome> vanillaBiome = biomeSource.getNoiseBiome(x >> 2, y >> 2, z >> 2, climateSampler);

        // Get the biome analysis
        BiomeSmoother smoother = BiomeSmoother.getInstance();
        BiomeAnalysis analysis = smoother.analyzeBiomeWithContext(x, y, z, vanillaBiome, biomeSource, climateSampler);

        if (analysis == null) {
            source.sendFailure(Component.literal("Failed to analyze biome at position")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Header
        source.sendSuccess(() -> Component.literal("=== Biome Analysis ===")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);

        // Position
        source.sendSuccess(() -> Component.literal("Position: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("X: %d, Y: %d, Z: %d", x, y, z))
                        .withStyle(ChatFormatting.WHITE)), false);

        // Surface height
        source.sendSuccess(() -> Component.literal("Surface Y: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(analysis.surfaceY()))
                        .withStyle(ChatFormatting.AQUA)), false);

        // Vanilla biome
        source.sendSuccess(() -> Component.literal("Vanilla Biome: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(analysis.vanillaBiomeName())
                        .withStyle(ChatFormatting.YELLOW)), false);

        // Biome at surface
        if (!analysis.surfaceBiomeName().equals(analysis.vanillaBiomeName())) {
            source.sendSuccess(() -> Component.literal("Surface Biome: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(analysis.surfaceBiomeName())
                            .withStyle(ChatFormatting.GOLD)), false);
        }

        // Region size with enhanced information
        if (analysis.regionSize() == Integer.MAX_VALUE) {
            source.sendSuccess(() -> Component.literal("Region Size: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Large (>" + ConfigManager.getMicroBiomeThreshold() + " blocks)")
                            .withStyle(ChatFormatting.GREEN)), false);
        } else if (analysis.regionSize() > 0) {
            int threshold = ConfigManager.getMicroBiomeThreshold();
            boolean isMicro = analysis.regionSize() <= threshold;
            source.sendSuccess(() -> Component.literal("Region Size: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(analysis.regionSize()))
                            .withStyle(isMicro ? ChatFormatting.RED : ChatFormatting.GREEN))
                    .append(Component.literal(" blocks")
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (threshold: " + threshold + ")")
                            .withStyle(ChatFormatting.GRAY)), false);
        } else if (analysis.regionSize() == -1) {
            source.sendSuccess(() -> Component.literal("Region Size: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Not calculated (preserved/non-surface)")
                            .withStyle(ChatFormatting.YELLOW)), false);
        } else {
            source.sendSuccess(() -> Component.literal("Region Size: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Unknown")
                            .withStyle(ChatFormatting.YELLOW)), false);
        }

        // Is micro biome?
        source.sendSuccess(() -> Component.literal("Is Micro Biome: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(analysis.isMicroBiome() ? "Yes" : "No")
                        .withStyle(analysis.isMicroBiome() ? ChatFormatting.RED : ChatFormatting.GREEN)), false);

        // Replacement biome (if applicable)
        if (analysis.isMicroBiome() && analysis.replacementBiomeName() != null) {
            source.sendSuccess(() -> Component.literal("Replacement: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(analysis.replacementBiomeName())
                            .withStyle(ChatFormatting.GREEN)), false);
        }

        // Special flags
        if (analysis.isPreserved()) {
            source.sendSuccess(() -> Component.literal("Status: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("PRESERVED (blacklisted)")
                            .withStyle(ChatFormatting.LIGHT_PURPLE)), false);
        } else if (!analysis.matchesSurface()) {
            source.sendSuccess(() -> Component.literal("Status: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Not surface biome (ignored)")
                            .withStyle(ChatFormatting.YELLOW)), false);
        } else {
            source.sendSuccess(() -> Component.literal("Status: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Processing enabled")
                            .withStyle(ChatFormatting.GREEN)), false);
        }

        // Performance and debug information
        source.sendSuccess(() -> Component.literal("Analysis Source: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(analysis.fromCache() ? "Cached result" : "Fresh calculation")
                        .withStyle(analysis.fromCache() ? ChatFormatting.DARK_GREEN : ChatFormatting.YELLOW)), false);

        // Configuration information
        source.sendSuccess(() -> Component.literal("Config - Enabled: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ConfigManager.isEnabled() ? "Yes" : "No")
                        .withStyle(ConfigManager.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(", Threshold: " + ConfigManager.getMicroBiomeThreshold())
                        .withStyle(ChatFormatting.AQUA)), false);

        // Processing details
        if (analysis.regionSize() > 0 && analysis.isMicroBiome()) {
            source.sendSuccess(() -> Component.literal("Processing: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("This biome WOULD be replaced in normal gameplay")
                            .withStyle(ChatFormatting.GOLD)), false);
        } else if (analysis.regionSize() > 0 && !analysis.isMicroBiome()) {
            source.sendSuccess(() -> Component.literal("Processing: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("This biome would NOT be replaced (too large)")
                            .withStyle(ChatFormatting.GREEN)), false);
        }

        // Add clickable teleport to surface
        if (source.getEntity() instanceof ServerPlayer) {
            MutableComponent teleportMsg = Component.literal("\n[Click to teleport to surface]")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);

            teleportMsg.withStyle(style -> style
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                            String.format("/biomepruner teleport %d %d", x, z)))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                            net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Teleport to X: " + x + ", Z: " + z))));

            source.sendSuccess(() -> teleportMsg, false);
        }

        return 1;
    }

    /**
     * Execute stats command
     */
    private static int executeStats(CommandSourceStack source) {
        // Header
        source.sendSuccess(() -> Component.literal("=== BiomePruner Status ===")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);

        // Mod status
        boolean enabled = ConfigManager.isEnabled();
        source.sendSuccess(() -> createStatusLine("Mod Enabled",
                enabled ? "Yes" : "No",
                enabled ? ChatFormatting.GREEN : ChatFormatting.RED), false);

        // Debug messages
        boolean debugMessages = ConfigManager.isDebugMessagesEnabled();
        source.sendSuccess(() -> createStatusLine("Debug Messages",
                debugMessages ? "Enabled" : "Disabled",
                debugMessages ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);

        // Performance logging
        boolean perfLogging = ConfigManager.isPerformanceLoggingEnabled();
        source.sendSuccess(() -> createStatusLine("Performance Logging",
                perfLogging ? "Enabled" : "Disabled",
                perfLogging ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);

        // Configuration values
        source.sendSuccess(() -> createStatusLine("Micro Biome Threshold",
                String.valueOf(ConfigManager.getMicroBiomeThreshold()) + " blocks",
                ChatFormatting.AQUA), false);

        source.sendSuccess(() -> createStatusLine("Grid Spacing",
                String.valueOf(ConfigManager.getGridSpacing()) + " blocks",
                ChatFormatting.AQUA), false);

        // Memory status
        long maxMemoryMB = ConfigManager.getMaxCacheMemoryMB();
        BiomeRegionCache cache = BiomeRegionCache.getInstance();
        String cacheStats = cache.getStatistics();

        source.sendSuccess(() -> createStatusLine("Max Cache Memory",
                maxMemoryMB + " MB",
                ChatFormatting.AQUA), false);

        source.sendSuccess(() -> Component.literal("Cache: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(cacheStats)
                        .withStyle(ChatFormatting.WHITE)), false);

        // Performance summary
        if (perfLogging) {
            PerformanceTracker.PerformanceStats stats = PerformanceTracker.getInstance().getStats(60);
            source.sendSuccess(() -> Component.literal("Performance (last 60s): ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format("%d executions, %.1f%% cache hit rate",
                                    stats.totalExecutions(), stats.cacheHitRate() * 100))
                            .withStyle(ChatFormatting.WHITE)), false);
        }

        return 1;
    }

    /**
     * Execute performance command
     */
    private static int executePerformance(CommandSourceStack source, int seconds) {
        if (!ConfigManager.isPerformanceLoggingEnabled()) {
            source.sendFailure(Component.literal("Performance logging is disabled. Enable it in the config.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        PerformanceTracker tracker = PerformanceTracker.getInstance();
        PerformanceTracker.PerformanceStats stats = tracker.getStats(seconds);

        // Header
        source.sendSuccess(() -> Component.literal(String.format("=== Performance Data (last %d seconds) ===", seconds))
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);

        // Overall stats
        source.sendSuccess(() -> Component.literal(String.format("Total Executions: %d", stats.totalExecutions()))
                .withStyle(ChatFormatting.WHITE), false);

        source.sendSuccess(() -> Component.literal(String.format("Cache Hit Rate: %.1f%%", stats.cacheHitRate() * 100))
                .withStyle(ChatFormatting.WHITE), false);

        source.sendSuccess(() -> Component.literal(String.format("Samples in Range: %d", stats.samplesInRange()))
                .withStyle(ChatFormatting.WHITE), false);

        // Section breakdown
        source.sendSuccess(() -> Component.literal("\nExecution Time Breakdown:")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE), false);

        for (Map.Entry<PerformanceTracker.Section, PerformanceTracker.SectionStats> entry : stats.sectionStats().entrySet()) {
            PerformanceTracker.Section section = entry.getKey();
            PerformanceTracker.SectionStats sectionStats = entry.getValue();

            // Display section header with sample count
            String headerText = String.format("\n%s (%d samples):", 
                section.getDisplayName(), sectionStats.sampleCount());
            source.sendSuccess(() -> Component.literal(headerText)
                    .withStyle(ChatFormatting.AQUA), false);

            // Show warning for insufficient samples
            if (sectionStats.sampleCount() < 3) {
                source.sendSuccess(() -> Component.literal("  [Warning: Low sample count - statistics may be unreliable]")
                        .withStyle(ChatFormatting.YELLOW), false);
            }

            source.sendSuccess(() -> createPerfLine("  Average", sectionStats.avgMicros(), "μs"), false);
            
            // Only show percentiles if we have meaningful data
            if (sectionStats.sampleCount() >= 3) {
                source.sendSuccess(() -> createPerfLine("  Median (P50)", sectionStats.p50Micros(), "μs"), false);
                source.sendSuccess(() -> createPerfLine("  P90", sectionStats.p90Micros(), "μs"), false);
                source.sendSuccess(() -> createPerfLine("  P99", sectionStats.p99Micros(), "μs"), false);
            }
            
            source.sendSuccess(() -> createPerfLine("  Min", sectionStats.minMicros(), "μs"), false);
            source.sendSuccess(() -> createPerfLine("  Max", sectionStats.maxMicros(), "μs"), false);
        }

        // Overhead calculation
        var totalStats = stats.sectionStats().get(PerformanceTracker.Section.TOTAL);
        if (totalStats != null) {
            source.sendSuccess(() -> Component.literal("\nMixin Overhead:")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE), false);

            double overheadP90 = totalStats.p90Micros();
            double overheadP99 = totalStats.p99Micros();

            source.sendSuccess(() -> Component.literal(String.format("  P90 overhead: %.2f μs per call", overheadP90))
                    .withStyle(getColorForMicros(overheadP90)), false);

            source.sendSuccess(() -> Component.literal(String.format("  P99 overhead: %.2f μs per call", overheadP99))
                    .withStyle(getColorForMicros(overheadP99)), false);
        }

        return 1;
    }

    /**
     * Execute teleport command
     */
    private static int executeTeleport(CommandSourceStack source, int x, int z) {
        if (source.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = player.serverLevel();

            // Find safe Y position
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 2;

            // Ensure it's safe
            BlockPos targetPos = new BlockPos(x, y, z);
            while (!level.getBlockState(targetPos).isAir() && y < level.getMaxBuildHeight() - 2) {
                y++;
                targetPos = targetPos.above();
            }

            // Teleport player
            player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());

            // Create final copies for lambda
            final int finalX = x;
            final int finalY = y;
            final int finalZ = z;
            
            source.sendSuccess(() -> Component.literal("Teleported to biome region at ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(String.format("X: %d, Y: %d, Z: %d", finalX, finalY, finalZ))
                            .withStyle(ChatFormatting.WHITE)), false);

            return 1;
        }

        source.sendFailure(Component.literal("This command can only be used by players")
                .withStyle(ChatFormatting.RED));
        return 0;
    }

    /**
     * Execute reset command
     */
    private static int executeReset(CommandSourceStack source) {
        PerformanceTracker.getInstance().reset();

        source.sendSuccess(() -> Component.literal("Performance data has been reset")
                .withStyle(ChatFormatting.GREEN), false);

        return 1;
    }

    /**
     * Execute clear cache command - for testing cache behavior
     */
    private static int executeClearCache(CommandSourceStack source) {
        // Clear region cache by creating a new instance
        // Note: This is a simple approach - in production we might want a proper clear method
        source.sendSuccess(() -> Component.literal("Cache clearing is not implemented yet - use server restart to clear cache")
                .withStyle(ChatFormatting.YELLOW), false);

        return 1;
    }

    /**
     * Create a status line
     */
    private static Component createStatusLine(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value)
                        .withStyle(valueColor));
    }

    /**
     * Create a performance metric line
     */
    private static Component createPerfLine(String label, double value, String unit) {
        ChatFormatting color = getColorForMicros(value);
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.2f %s", value, unit))
                        .withStyle(color));
    }

    /**
     * Get color based on microseconds
     */
    private static ChatFormatting getColorForMicros(double micros) {
        if (micros < 10) return ChatFormatting.GREEN;
        if (micros < 50) return ChatFormatting.YELLOW;
        if (micros < 100) return ChatFormatting.GOLD;
        return ChatFormatting.RED;
    }
}