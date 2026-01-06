package net.cama.livelylighting;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class LivelyLightingCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register /livelylighting
        dispatcher.register(Commands.literal("livelylighting")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("reload")
                .executes(LivelyLightingCommand::reloadConfig)));
                
        // Register /ll alias
        dispatcher.register(Commands.literal("ll")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("reload")
                .executes(LivelyLightingCommand::reloadConfig)));
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        LightManager.reloadConfig();
        context.getSource().sendSuccess(() -> Component.literal("LivelyLighting config reloaded!"), true);
        return 1;
    }
}
