package net.cama.livelylighting.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Curios, by reflection (Lively Lighting compiles against no Curios): the items an entity wears in its curio slots, in
 * a fixed order (Curios' slot map order, then slot index) that server and client walk the same way. A torch or
 * lantern on a belt lights the way like one in the off hand: the server takes the brightest curio into the entity's
 * light ({@code LightCalculator}, the cluster path's {@code curios} source), and clients colour that light from the
 * first curio Colorful Lighting knows a colour for ({@code LightSourceColors}). Nothing without Curios.
 */
public final class CuriosLights {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final boolean LOADED = ModList.get().isLoaded("curios");
    private static Method getInventory;
    private static Method resolve;
    private static Method getCurios;
    private static Method getStacks;
    private static Method getSlots;
    private static Method getStackInSlot;
    private static boolean broken;

    private CuriosLights() {
    }

    public static boolean loaded() {
        return LOADED && !broken;
    }

    /** Every non-empty stack in the entity's curio slots, in Curios' own order; empty without Curios. */
    public static List<ItemStack> worn(LivingEntity entity) {
        if (!LOADED || broken) return Collections.emptyList();
        try {
            if (getInventory == null) {
                Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                getInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
                resolve = getInventory.getReturnType().getMethod("resolve");
                Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                getCurios = handler.getMethod("getCurios");
                Class<?> stacksHandler = Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler");
                getStacks = stacksHandler.getMethod("getStacks");
                Class<?> dynamic = Class.forName("top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler");
                getSlots = dynamic.getMethod("getSlots");
                getStackInSlot = dynamic.getMethod("getStackInSlot", int.class);
            }
            Object lazy = getInventory.invoke(null, entity);
            Optional<?> handler = (Optional<?>) resolve.invoke(lazy);
            if (handler.isEmpty()) return Collections.emptyList();
            Map<?, ?> curios = (Map<?, ?>) getCurios.invoke(handler.get());
            List<ItemStack> out = new ArrayList<>();
            for (Object stacksHandler : curios.values()) {
                Object stacks = getStacks.invoke(stacksHandler);
                int slots = (Integer) getSlots.invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = (ItemStack) getStackInSlot.invoke(stacks, i);
                    if (!stack.isEmpty()) out.add(stack);
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOGGER.warn("[LivelyLighting] Curios' API isn't the one expected; curio slots give no light: {}", e.toString());
            return Collections.emptyList();
        }
    }

    /**
     * What the entity wears as a number both sides work out the same way (registry ids are synced): the worn items'
     * ids folded in Curios' order. 0 for no curios.
     */
    public static int token(LivingEntity entity) {
        int mix = 0;
        for (ItemStack stack : worn(entity)) mix = mix * 31 + BuiltInRegistries.ITEM.getId(stack.getItem()) + 1;
        return mix;
    }
}
