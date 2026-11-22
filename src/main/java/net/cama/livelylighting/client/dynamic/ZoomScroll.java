package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.cama.livelylighting.network.LightSourceNet;
import net.cama.livelylighting.source.BeamZoom;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * The client's half of the beam zoom (0.11.0): while the player sneaks with a zoomable spot light in either hand (a
 * definition with a {@link BeamZoom}: the pack's flashlight), a notch of the mouse wheel is taken from the hotbar and sent
 * to the server as one step of the zoom (up: tighter, down: wider; {@code source.BeamScroll} sets the step on the stack).
 * Standing, the wheel scrolls the hotbar as always; so does sneaking with anything else in hand. No key of its own. The
 * tooltip of a zoomable item names its beam and the gesture.
 */
public final class ZoomScroll {
    private ZoomScroll() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ZoomScroll::onScroll);
        MinecraftForge.EVENT_BUS.addListener(ZoomScroll::onTooltip);
    }

    private static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || !LivelyConfig.get().beam_zoom) return;
        if (!player.isShiftKeyDown()) return;
        double delta = event.getScrollDelta();
        if (delta == 0.0 || Double.isNaN(delta)) return;
        LightData def = LightCalculator.zoomableDefinition(player.getMainHandItem());
        if (def == null) def = LightCalculator.zoomableDefinition(player.getOffhandItem());
        if (def == null) return;
        // a server without this mod has nothing to set the step: the wheel keeps the hotbar there
        net.camacraft.camafy.network.CamafyChannel channel = LightSourceNet.channel();
        if (channel == null || mc.getConnection() == null || !channel.raw().isRemotePresent(mc.getConnection().getConnection())) return;
        event.setCanceled(true);
        LightSourceNet.sendZoom(delta > 0.0 ? 1 : -1);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !LivelyConfig.get().beam_zoom) return;
        LightData def = LightCalculator.zoomableDefinition(stack);
        if (def == null || def.zoom == null) return;
        int step = def.zoom.currentStep(stack, def.coneDegrees);
        event.getToolTip().add(Component.literal("Beam: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(def.zoom.describe(step)).withStyle(ChatFormatting.WHITE)));
        event.getToolTip().add(Component.literal("Sneak and scroll to zoom the beam").withStyle(ChatFormatting.DARK_GRAY));
    }
}
