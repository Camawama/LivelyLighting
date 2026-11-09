package net.cama.livelylighting.client;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.cama.livelylighting.LivelyConfig;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * A "Lively Lighting" page in Embeddium's video settings (0.6.0) with the client module's keys, so a player changes the
 * renderer, the falloff, light through blocks, the spot beams and the budgets without opening {@code livelylighting.json}.
 * Every change is written to that file and takes effect on the next tick. Only when Embeddium is present; the page is added
 * through Embeddium's options API when the screen is built.
 */
public final class EmbeddiumOptions {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EmbeddiumOptions() {}

    enum Renderer {
        AUTO, SHADER, LIGHTMAP, OFF;

        static Renderer of(String s) {
            try {
                return valueOf(s == null ? "AUTO" : s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    enum Shape {
        DIAMOND, ROUND;

        static Shape of(String s) {
            return net.cama.livelylighting.source.Falloff.parse(s, net.cama.livelylighting.source.Falloff.DIAMOND) == net.cama.livelylighting.source.Falloff.ROUND ? ROUND : DIAMOND;
        }
    }

    /** The client keys as Embeddium's storage: the live config object, saved to its file on apply. */
    private static final class Storage implements OptionStorage<LivelyConfig.Client> {
        @Override
        public LivelyConfig.Client getData() {
            return LivelyConfig.get().client;
        }

        @Override
        public void save() {
            LivelyConfig.save();
        }
    }

    public static void register() {
        try {
            OptionGUIConstructionEvent.BUS.addListener(event -> event.addPage(page()));
            LOGGER.info("[LivelyLighting] a Lively Lighting page in Embeddium's video settings");
        } catch (Throwable t) {
            LOGGER.info("[LivelyLighting] Embeddium's options API isn't as expected; no settings page ({})", t.toString());
        }
    }

    private static OptionPage page() {
        Storage storage = new Storage();
        OptionGroup look = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Renderer.class, storage)
                        .setName(Component.literal("Renderer"))
                        .setTooltip(Component.literal("How this client draws dynamic light. Auto: Colorful Lighting's shaders where they run (Embeddium, no shader pack), which rebuild no chunk when a light moves; else the lightmap, which bakes the light into chunks. Off draws none."))
                        .setControl(opt -> new CyclingControl<>(opt, Renderer.class, new Component[]{Component.literal("Auto"), Component.literal("Shaders"), Component.literal("Lightmap"), Component.literal("Off")}))
                        .setBinding((c, v) -> c.renderer = v.name().toLowerCase(Locale.ROOT), c -> Renderer.of(c.renderer))
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(Shape.class, storage)
                        .setName(Component.literal("Falloff"))
                        .setTooltip(Component.literal("Diamond: a level lost per block of Manhattan distance, the look of Minecraft's own light. Round: per block of straight distance."))
                        .setControl(opt -> new CyclingControl<>(opt, Shape.class, new Component[]{Component.literal("Diamond"), Component.literal("Round")}))
                        .setBinding((c, v) -> c.falloff = v.name().toLowerCase(Locale.ROOT), c -> Shape.of(c.falloff))
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Light Through Blocks"))
                        .setTooltip(Component.literal("On: the light spreads through the blocks round you as a placed lamp's does: walls stop it, stained glass tints it, it goes round corners. Off: it shines through everything, as other dynamic lighting mods do."))
                        .setControl(TickBoxControl::new)
                        .setBinding((c, v) -> c.transmission = v ? "voxel" : "off", c -> !"off".equalsIgnoreCase(c.transmission))
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Propagated Lightmap Lights"))
                        .setTooltip(Component.literal("Lightmap renderer with Colorful Lighting: a point light is a lamp its engine propagates (walls, glass, shader packs read it as block light). Off: a straight line through walls, cheaper when many lights move."))
                        .setControl(TickBoxControl::new)
                        .setBinding((c, v) -> c.lightmap_propagate = v, c -> c.lightmap_propagate)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build();
        OptionGroup beams = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Spot Beams"))
                        .setTooltip(Component.literal("A faint cone in the air along every spot light (headlights, flashlights)."))
                        .setControl(TickBoxControl::new)
                        .setBinding((c, v) -> c.spot_beams = v, c -> c.spot_beams)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Spot Beam Opacity"))
                        .setTooltip(Component.literal("How visible the beam is at the light."))
                        .setControl(opt -> new SliderControl(opt, 1, 40, 1, ControlValueFormatter.percentage()))
                        .setBinding((c, v) -> c.spot_beam_opacity = v / 100.0, c -> (int) Math.round(Math.max(0.01, Math.min(0.4, c.spot_beam_opacity)) * 100))
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build();
        OptionGroup budgets = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Shader Lights"))
                        .setTooltip(Component.literal("Shader renderer: lights sent to the shaders per frame, nearest first. Every terrain vertex evaluates each of them outside the propagated field."))
                        .setControl(opt -> new SliderControl(opt, 4, 64, 4, ControlValueFormatter.number()))
                        .setBinding((c, v) -> c.shader_max_lights = v, c -> Math.max(4, Math.min(64, c.shader_max_lights)))
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Shader Light Distance"))
                        .setTooltip(Component.literal("Shader renderer: lights farther than this from the camera are left out."))
                        .setControl(opt -> new SliderControl(opt, 16, 128, 8, ControlValueFormatter.number()))
                        .setBinding((c, v) -> c.shader_max_distance = v, c -> Math.max(16, Math.min(128, c.shader_max_distance)))
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Lightmap Rebuilds Per Tick"))
                        .setTooltip(Component.literal("Lightmap renderer: chunk sections rebuilt for moved lights per tick, nearest first; the rest wait."))
                        .setControl(opt -> new SliderControl(opt, 2, 64, 2, ControlValueFormatter.number()))
                        .setBinding((c, v) -> c.lightmap_rebuilds_per_tick = v, c -> Math.max(2, Math.min(64, c.lightmap_rebuilds_per_tick)))
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Draw Without Server"))
                        .setTooltip(Component.literal("On a server without Lively Lighting (a vanilla server, another pack), this client draws the light it can see anyway. A server with an older Lively Lighting places light blocks and is never drawn over."))
                        .setControl(TickBoxControl::new)
                        .setBinding((c, v) -> c.draw_without_server = v, c -> c.draw_without_server)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build();
        return new OptionPage(Component.literal("Lively Lighting"), ImmutableList.of(look, beams, budgets));
    }
}
