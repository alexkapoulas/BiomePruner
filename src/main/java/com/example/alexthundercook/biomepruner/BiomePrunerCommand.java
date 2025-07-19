/* BiomePrunerCommand.java */
package com.example.alexthundercook.biomepruner;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /biomepruner analyze &lt;x&gt; &lt;z&gt;
 * Prints region size & replacement OR notes when the biome is blacklisted.
 */
@EventBusSubscriber(modid = BiomePrunerMod.MODID,
        bus = EventBusSubscriber.Bus.GAME)
public final class BiomePrunerCommand {

    private BiomePrunerCommand() {}

    /* ------------------------------------------------------------------ */

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("biomepruner")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("analyze")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(BiomePrunerCommand::runAnalyze))));
    }

    /* ------------------------------------------------------------------ */

    private static int runAnalyze(CommandContext<CommandSourceStack> ctx) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        RegionAnalyzer.RegionInfo info =
                RegionAnalyzer.analyse(ctx.getSource().getLevel(), x, z);

        ctx.getSource().sendSuccess(() -> formatMessage(x, z, info), false);
        return Command.SINGLE_SUCCESS;
    }

    private static Component formatMessage(int x, int z,
                                           RegionAnalyzer.RegionInfo info) {

        if (info.size() < 0) {   // –1 ⇒ blacklisted
            return Component.literal(
                    "BiomePruner » [" + x + ", " + z + "] → skipped (blacklisted biome)");
        }

        StringBuilder sb = new StringBuilder()
                .append("BiomePruner » [").append(x).append(", ").append(z)
                .append("] → size=").append(info.size());

        if (info.replacement() == null) {
            sb.append(" (large)");
        } else {
            String id = info.replacement()
                    .unwrapKey()
                    .map(ResourceKey::location)
                    .map(Object::toString)
                    .orElse("unknown");
            sb.append(" (micro, replacement=").append(id).append(')');
        }
        return Component.literal(sb.toString());
    }
}
