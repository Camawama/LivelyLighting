package net.cama.livelylighting.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.cama.livelylighting.dynamiclighting.engine.LightManager;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.data.LivelyLightingData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;

public class LivelyLightingCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("livelylighting");
        registerSubCommands(command);
        dispatcher.register(command);

        LiteralArgumentBuilder<CommandSourceStack> alias = Commands.literal("ll");
        registerSubCommands(alias);
        dispatcher.register(alias);
    }

    private static void registerSubCommands(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(LivelyLightingCommand::reloadConfig))
            .then(Commands.literal("purge")
                .requires(source -> source.hasPermission(2))
                .executes(LivelyLightingCommand::purgeLights))
            .then(Commands.literal("toggle")
                .executes(LivelyLightingCommand::toggleSelf)
                .then(Commands.argument("target", EntityArgument.entity())
                    .requires(source -> source.hasPermission(2))
                    .executes(LivelyLightingCommand::toggleTarget)))
            .then(Commands.literal("sound")
                .then(Commands.literal("on")
                    .executes(context -> toggleSound(context, true)))
                .then(Commands.literal("off")
                    .executes(context -> toggleSound(context, false))))
            .then(Commands.literal("add")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.entity())
                    .then(Commands.argument("level", IntegerArgumentType.integer(1, 15))
                        .executes(LivelyLightingCommand::addEntity))))
            .then(Commands.literal("remove")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.entity())
                    .executes(LivelyLightingCommand::removeEntity)))
            .then(Commands.literal("config")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("option", StringArgumentType.word())
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(LivelyLightingCommand::setConfig))));
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        LightManager.purgeAllLights(context.getSource().getServer()); // purge lights after reload
        LightManager.reloadConfig();
        context.getSource().sendSuccess(() -> Component.literal("LivelyLighting config reloaded!"), true);
        return 1;
    }

    private static int purgeLights(CommandContext<CommandSourceStack> context) {
        LightManager.purgeAllLights(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("Purged all light blocks."), true);
        return 1;
    }

    private static int toggleSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = context.getSource().getEntityOrException();
        return toggleEntity(context, entity);
    }

    private static int toggleTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "target");
        return toggleEntity(context, entity);
    }

    private static int toggleEntity(CommandContext<CommandSourceStack> context, Entity entity) {
        ServerLevel level = (ServerLevel) entity.level();
        LivelyLightingData data = LivelyLightingData.get(level);
        data.toggleEntity(entity.getUUID());
        
        boolean disabled = data.isEntityDisabled(entity.getUUID());
        String status = disabled ? "disabled" : "enabled";
        context.getSource().sendSuccess(() -> Component.literal("Dynamic lighting " + status + " for " + entity.getName().getString()), true);
        return 1;
    }
    
    private static int toggleSound(CommandContext<CommandSourceStack> context, boolean enable) throws CommandSyntaxException {
        Entity entity = context.getSource().getEntityOrException();
        if (entity instanceof Player) {
            LivelyLightingData data = LivelyLightingData.get((ServerLevel) entity.level());
            boolean currentlyDisabled = data.arePlayerSoundsDisabled(entity.getUUID());
            if (enable) {
                if (currentlyDisabled) {
                    data.togglePlayerSounds(entity.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("Dynamic lighting sounds enabled."), true);
                } else {
                    context.getSource().sendSuccess(() -> Component.literal("Dynamic lighting sounds are already enabled."), false);
                }
            } else {
                if (!currentlyDisabled) {
                    data.togglePlayerSounds(entity.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("Dynamic lighting sounds disabled."), true);
                } else {
                    context.getSource().sendSuccess(() -> Component.literal("Dynamic lighting sounds are already disabled."), false);
                }
            }
        }
        return 1;
    }

    private static int addEntity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "target");
        int level = IntegerArgumentType.getInteger(context, "level");
        
        ServerLevel world = (ServerLevel) entity.level();
        LivelyLightingData data = LivelyLightingData.get(world);
        data.addForcedEntity(entity.getUUID(), level);
        
        context.getSource().sendSuccess(() -> Component.literal("Added dynamic lighting (Level " + level + ") for " + entity.getName().getString()), true);
        return 1;
    }

    private static int removeEntity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "target");
        
        ServerLevel world = (ServerLevel) entity.level();
        LivelyLightingData data = LivelyLightingData.get(world);
        data.removeForcedEntity(entity.getUUID());
        
        context.getSource().sendSuccess(() -> Component.literal("Removed forced dynamic lighting for " + entity.getName().getString()), true);
        return 1;
    }

    private static int setConfig(CommandContext<CommandSourceStack> context) {
        String option = StringArgumentType.getString(context, "option");
        String value = StringArgumentType.getString(context, "value");
        
        LivelyConfig config = LivelyConfig.get();
        try {
            Field field = null;
            Object target = config;
            
            if (option.startsWith("experimental.")) {
                field = LivelyConfig.Experimental.class.getField(option.substring(13));
                target = config.experimental;
            } else {
                field = LivelyConfig.class.getField(option);
            }
            
            if (field.getType() == boolean.class) {
                field.setBoolean(target, Boolean.parseBoolean(value));
            } else if (field.getType() == int.class) {
                field.setInt(target, Integer.parseInt(value));
            } else if (field.getType() == double.class) {
                field.setDouble(target, Double.parseDouble(value));
            } else {
                context.getSource().sendFailure(Component.literal("Cannot modify this config option via command."));
                return 0;
            }
            
            LivelyConfig.save();
            LightManager.reloadConfig();
            
            // Purge lights if the mod was disabled
            if (option.equals("enable") && !config.enable) {
                LightManager.purgeAllLights(context.getSource().getServer());
            }

            context.getSource().sendSuccess(() -> Component.literal("Config option '" + option + "' set to " + value), true);
            return 1;
        } catch (NoSuchFieldException e) {
            context.getSource().sendFailure(Component.literal("Unknown config option: " + option));
        } catch (IllegalAccessException | NumberFormatException e) {
            context.getSource().sendFailure(Component.literal("Invalid value for option: " + option));
        }
        return 0;
    }
}
