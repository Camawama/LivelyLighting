package net.cama.livelylighting.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.cama.livelylighting.data.ModTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A lantern on the Curios belt is drawn hanging at the hip (0.10.1): the lantern's own hanging model, chain up, half size, on
 * the side away from the main hand, turned and bent with the body, and as bright as its own light. Every item of
 * {@code #livelylighting:belt_lanterns} that has no Curios renderer of its own gets this one when the tags arrive; one that
 * isn't a block with a {@code hanging} state is drawn as its item. Curios by reflection (Lively Lighting compiles against
 * no Curios): its renderer interface is implemented by a proxy. Nothing without Curios; the body isn't drawn in first
 * person, so neither is the lantern there.
 */
public final class BeltLanterns {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Item> OURS = new HashSet<>();
    private static Method register;
    private static Method getRenderer;
    private static Map<Item, Object> built;
    private static Object renderer;
    private static Method slotEntity;
    private static boolean broken;
    /** For tests: how many belt lanterns this client has drawn since the start. */
    private static int drawn;

    private BeltLanterns() {}

    public static void init() {
        if (!ModList.get().isLoaded("curios")) return;
        MinecraftForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) refresh();
        });
    }

    public static int drawn() {
        return drawn;
    }

    /** The lanterns given this renderer (for tests). */
    public static Set<Item> registered() {
        return Set.copyOf(OURS);
    }

    @SuppressWarnings("unchecked")
    static void refresh() {
        if (broken) return;
        try {
            if (renderer == null) {
                Class<?> registry = Class.forName("top.theillusivec4.curios.api.client.CuriosRendererRegistry");
                Class<?> api = Class.forName("top.theillusivec4.curios.api.client.ICurioRenderer");
                register = registry.getMethod("register", Item.class, Supplier.class);
                getRenderer = registry.getMethod("getRenderer", Item.class);
                // the renderers Curios has built: its load() builds them from the suppliers once, so one registered later is put there too
                Field f = registry.getDeclaredField("RENDERERS");
                f.setAccessible(true);
                built = (Map<Item, Object>) f.get(null);
                renderer = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, BeltLanterns::invoke);
            }
            int added = 0;
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(ModTags.Items.BELT_LANTERNS)) {
                Item item = holder.value();
                if (OURS.contains(item)) continue;
                if (((Optional<?>) getRenderer.invoke(null, item)).isPresent()) continue; // its own mod draws it
                Object r = renderer;
                register.invoke(null, item, (Supplier<Object>) () -> r);
                built.put(item, r);
                OURS.add(item);
                added++;
            }
            if (added > 0) LOGGER.info("[LivelyLighting] {} lanterns hang from the belt when worn there ({} in all)", added, OURS.size());
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[LivelyLighting] Curios' renderer API isn't the one expected; belt lanterns aren't drawn: {}", t.toString());
        }
    }

    private static Object invoke(Object self, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "render" -> {
                if (args != null && args.length >= 6) render(args);
                return null;
            }
            case "hashCode" -> {
                return System.identityHashCode(self);
            }
            case "equals" -> {
                return args != null && args.length == 1 && self == args[0];
            }
            case "toString" -> {
                return "Lively Lighting belt lantern";
            }
            default -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
                return null;
            }
        }
    }

    /** {@code render(stack, slotContext, pose, parent, buffers, light, ...)}: the lantern at the hip. */
    private static void render(Object[] args) {
        try {
            ItemStack stack = (ItemStack) args[0];
            Object context = args[1];
            if (slotEntity == null) slotEntity = context.getClass().getMethod("entity");
            LivingEntity entity = (LivingEntity) slotEntity.invoke(context);
            draw(stack, entity, (PoseStack) args[2], (RenderLayerParent<?, ?>) args[3], (MultiBufferSource) args[4], (Integer) args[5]);
        } catch (Throwable t) {
            if (!broken) LOGGER.warn("[LivelyLighting] drawing a belt lantern failed: {}", t.toString());
            broken = true;
        }
    }

    static void draw(ItemStack stack, LivingEntity entity, PoseStack pose, RenderLayerParent<?, ?> parent, MultiBufferSource buffers, int light) {
        if (stack.isEmpty() || entity == null) return;
        EntityModel<?> model = parent.getModel();
        if (!(model instanceof HumanoidModel<?> humanoid)) return;
        pose.pushPose();
        // the body's place and turn (crouching included); model space: 1/16 block units already scaled, y down, +x the left side
        humanoid.body.translateAndRotate(pose);
        boolean rightHanded = entity.getMainArm() == HumanoidArm.RIGHT;
        // the hip away from the main hand, just outside the leg, at the belt (11 pixels down the body)
        pose.translate(rightHanded ? 0.36 : -0.36, 0.6875, 0.0);
        // upright in the world: model space is upside down
        pose.mulPose(Axis.ZP.rotationDegrees(180f));
        Item item = stack.getItem();
        BlockState state = item instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
        if (state != null && state.hasProperty(BlockStateProperties.HANGING)) {
            state = state.setValue(BlockStateProperties.HANGING, true);
            // as bright as its own light, whatever light the body stands in
            int packed = LightTexture.pack(Math.max(LightTexture.block(light), state.getLightEmission()), LightTexture.sky(light));
            pose.scale(0.5f, 0.5f, 0.5f);
            // the top of the chain at the belt, the lantern below it
            pose.translate(-0.5, -1.0, -0.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, packed, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.cutout());
        } else {
            int emission = state != null ? state.getLightEmission() : 0;
            int packed = LightTexture.pack(Math.max(LightTexture.block(light), emission), LightTexture.sky(light));
            pose.scale(0.4f, 0.4f, 0.4f);
            pose.translate(0.0, -0.5, 0.0);
            Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, packed, OverlayTexture.NO_OVERLAY,
                    pose, buffers, entity.level(), 0);
        }
        pose.popPose();
        drawn++;
    }
}
