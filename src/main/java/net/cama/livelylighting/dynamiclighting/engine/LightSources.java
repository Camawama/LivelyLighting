package net.cama.livelylighting.dynamiclighting.engine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * What a light block of the mod stands for, so that a client with Colorful Lighting gives it its source's colour (the
 * owner, 2026-10-12: a fast trail, a light put away, two ships' merged lamps and the dim edge of a world lamp's light on
 * a ship all came out white). Colorful Lighting guessed a light block's colour from the coloured entity nearest it, or
 * from the light stored round the block's mirror across a ship's boundary; a light far behind a fast holder, one whose
 * holder no longer holds anything, one standing for several lamps or one at the dim edge of a lamp's reach had none of
 * those, and fell back to white. Now every light carries its sources ({@link LightCluster#sources()}), the server tells
 * clients ({@code LightSourceSync}) and the client gives Colorful Lighting the colour ({@code client.LightSourceColors}).
 *
 * <p>A light's sources are at most {@value #MAX} (kind, value) pairs, packed as {@code long[2n]}:
 * {@link #ENTITY}: an entity's light (its id, the slot the light comes from, a token of what is in that slot, so the
 * client knows when its own copy of the entity has caught up); {@link #WORLD_LAMP} / {@link #SHIP_LAMP}: a lamp's light
 * (its block position, in the world or in a ship's shipyard); {@link #FIRE}: a burning ship's.
 */
public final class LightSources {
    public static final int ENTITY = 1, WORLD_LAMP = 2, SHIP_LAMP = 3, FIRE = 4;
    /** Sources one light keeps at most (a merged cluster of more keeps the first ones). */
    public static final int MAX = 4;
    public static final long[] NONE = new long[0];

    /** Where an entity's light comes from (its source id in LightLogic). */
    public static final int SLOT_COMBINED = 0, SLOT_MAINHAND = 1, SLOT_OFFHAND = 2, SLOT_HEAD = 3, SLOT_CHEST = 4,
            SLOT_LEGS = 5, SLOT_FEET = 6, SLOT_CARRIED = 7, SLOT_BODY = 8, SLOT_FORCED = 9;
    /** {@link #SLOT_BODY} tokens. */
    public static final int BODY_OTHER = 0, BODY_FIRE = 1, BODY_GLOWING = 2;

    private LightSources() {}

    /** An {@link #ENTITY} value: the entity's id, the slot, and 28 bits of the token. */
    public static long entity(int entityId, int slot, int token) {
        return (entityId & 0xFFFFFFFFL) | ((long) (slot & 0xF) << 32) | ((long) (token & 0x0FFFFFFF) << 36);
    }

    public static int entityId(long value) {
        return (int) value;
    }

    public static int slot(long value) {
        return (int) (value >>> 32) & 0xF;
    }

    public static int token(long value) {
        return (int) (value >>> 36) & 0x0FFFFFFF;
    }

    /** The slot of one of LightLogic's source ids ("mainhand", "head", "body", ...). */
    public static int slotOf(String sourceId) {
        return switch (sourceId) {
            case "mainhand" -> SLOT_MAINHAND;
            case "offhand" -> SLOT_OFFHAND;
            case "head" -> SLOT_HEAD;
            case "chest" -> SLOT_CHEST;
            case "legs" -> SLOT_LEGS;
            case "feet" -> SLOT_FEET;
            case "carried_block" -> SLOT_CARRIED;
            case "body" -> SLOT_BODY;
            case "forced" -> SLOT_FORCED;
            default -> SLOT_COMBINED;
        };
    }

    /**
     * What is in the slot now, as a number both sides work out the same way (registry ids are synced): the item's id for
     * a hand or armour slot, the carried block's for an enderman, burning or glowing for the body, a mix of every slot
     * and the fire for the combined light.
     */
    public static int token(Entity entity, int slot) {
        return switch (slot) {
            case SLOT_MAINHAND -> itemToken(entity, EquipmentSlot.MAINHAND);
            case SLOT_OFFHAND -> itemToken(entity, EquipmentSlot.OFFHAND);
            case SLOT_HEAD -> itemToken(entity, EquipmentSlot.HEAD);
            case SLOT_CHEST -> itemToken(entity, EquipmentSlot.CHEST);
            case SLOT_LEGS -> itemToken(entity, EquipmentSlot.LEGS);
            case SLOT_FEET -> itemToken(entity, EquipmentSlot.FEET);
            case SLOT_CARRIED -> {
                BlockState carried = entity instanceof EnderMan enderman ? enderman.getCarriedBlock() : null;
                yield carried == null ? 0 : BuiltInRegistries.BLOCK.getId(carried.getBlock()) + 1;
            }
            case SLOT_BODY -> entity.isOnFire() ? BODY_FIRE
                    : entity instanceof LivingEntity living && living.hasEffect(MobEffects.GLOWING) ? BODY_GLOWING : BODY_OTHER;
            case SLOT_FORCED -> 0;
            default -> {
                int mix = entity.isOnFire() ? 1 : 0;
                if (entity instanceof LivingEntity living) {
                    for (EquipmentSlot s : EquipmentSlot.values()) mix = mix * 31 + rawId(living.getItemBySlot(s));
                }
                yield mix;
            }
        };
    }

    private static int itemToken(Entity entity, EquipmentSlot slot) {
        return entity instanceof LivingEntity living ? rawId(living.getItemBySlot(slot)) : 0;
    }

    private static int rawId(ItemStack stack) {
        return stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem()) + 1;
    }

    /** {@code sources} with the pair added, unless it is there already or the light has {@value #MAX}. */
    public static long[] with(long[] sources, int kind, long value) {
        for (int i = 0; i + 1 < sources.length; i += 2) {
            if (sources[i] == kind && sources[i + 1] == value) return sources;
        }
        if (sources.length >= 2 * MAX) return sources;
        long[] out = Arrays.copyOf(sources, sources.length + 2);
        out[sources.length] = kind;
        out[sources.length + 1] = value;
        return out;
    }

    /** Every pair of both (two lights of the same level meeting in one cell), at most {@value #MAX}. */
    public static long[] union(long[] a, long[] b) {
        long[] out = a;
        for (int i = 0; i + 1 < b.length; i += 2) out = with(out, (int) b[i], b[i + 1]);
        return out;
    }
}
