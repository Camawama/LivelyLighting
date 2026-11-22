package net.cama.livelylighting.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A spot light's zoom (0.11.0): a spot definition with
 * <pre>"zoom": {"nbt": "zoom", "steps": 8, "wide": [60, 30], "tight": [12, 90]}</pre>
 * lets each stack set its cone and reach by a number in its tag, from the wide end (step 0: 60 degrees, 30 blocks) to
 * the tight end (step {@code steps}: 12 degrees, 90 blocks), the cone narrowing and the beam reaching farther step by
 * step, as the head of a zoom flashlight slides. A stack without the tag keeps the definition's own {@code cone_degrees}
 * and {@code range}; its first step is taken from the step nearest that cone. The client narrows or widens a held one by
 * a notch of the mouse wheel while its holder sneaks ({@code client.dynamic.ZoomScroll}); the step is set on the server
 * ({@link BeamScroll}), so every client draws the same cone. {@code "wide"} and {@code "tight"} may also be objects
 * ({@code {"cone_degrees": 60, "range": 30}}); {@code "nbt"} defaults to {@code zoom}, {@code "steps"} to 8.
 */
public record BeamZoom(String nbt, int steps, float wideDegrees, float wideRange, float tightDegrees, float tightRange) {
    public static final String DEFAULT_NBT = "zoom";
    public static final int DEFAULT_STEPS = 8;
    public static final int MAX_STEPS = 64;

    public BeamZoom {
        nbt = nbt == null || nbt.isBlank() ? DEFAULT_NBT : nbt.trim();
        steps = Math.max(1, Math.min(MAX_STEPS, steps));
        wideDegrees = Math.max(1f, Math.min(179f, wideDegrees));
        tightDegrees = Math.max(1f, Math.min(179f, tightDegrees));
        wideRange = Math.max(1f, wideRange);
        tightRange = Math.max(1f, tightRange);
    }

    /** The {@code "zoom"} object of a definition; null when it names no ends (a zoom needs at least its wide and tight cones). */
    @Nullable
    public static BeamZoom parse(@Nullable JsonObject json, float coneDegrees, float range) {
        if (json == null) return null;
        float[] wide = end(json.get("wide"), coneDegrees, range);
        float[] tight = end(json.get("tight"), coneDegrees, range);
        if (wide == null || tight == null) return null;
        String nbt = json.has("nbt") && json.get("nbt").isJsonPrimitive() ? json.get("nbt").getAsString() : DEFAULT_NBT;
        int steps = json.has("steps") && json.get("steps").isJsonPrimitive() ? json.get("steps").getAsInt() : DEFAULT_STEPS;
        return new BeamZoom(nbt, steps, wide[0], wide[1], tight[0], tight[1]);
    }

    /** One end: {@code [degrees, range]} or {@code {"cone_degrees": d, "range": r}}; a missing range is the definition's own. */
    @Nullable
    private static float[] end(@Nullable JsonElement e, float coneDegrees, float range) {
        if (e == null) return null;
        try {
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                if (a.size() == 0) return null;
                float d = a.get(0).getAsFloat();
                float r = a.size() > 1 ? a.get(1).getAsFloat() : range;
                return new float[]{d, r > 0f ? r : LightShape.DEFAULT_SPOT_RANGE};
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                float d = o.has("cone_degrees") ? o.get("cone_degrees").getAsFloat() : coneDegrees;
                float r = o.has("range") ? o.get("range").getAsFloat() : range;
                return new float[]{d > 0f ? d : LightShape.DEFAULT_CONE_DEGREES, r > 0f ? r : LightShape.DEFAULT_SPOT_RANGE};
            }
            if (e.isJsonPrimitive()) return new float[]{e.getAsFloat(), range > 0f ? range : LightShape.DEFAULT_SPOT_RANGE};
        } catch (RuntimeException ignored) {
            // not a number: no such end
        }
        return null;
    }

    public int clamp(int step) {
        return Math.max(0, Math.min(steps, step));
    }

    /** Where a step stands between the ends, 0 at the wide end and 1 at the tight one. */
    private float share(int step) {
        return clamp(step) / (float) steps;
    }

    /** The cone at a step, degrees (the full outer angle). */
    public float coneAt(int step) {
        float t = share(step);
        return wideDegrees + (tightDegrees - wideDegrees) * t;
    }

    /** The reach at a step, blocks. */
    public float rangeAt(int step) {
        float t = share(step);
        return wideRange + (tightRange - wideRange) * t;
    }

    /** The step whose cone is nearest a cone: where a stack without the tag stands (the definition's own cone). */
    public int stepNear(float coneDegrees) {
        if (Math.abs(wideDegrees - tightDegrees) < 1.0e-3f) return 0;
        float t = (wideDegrees - coneDegrees) / (wideDegrees - tightDegrees);
        return clamp(Math.round(t * steps));
    }

    /** The step a stack's tag holds, clamped; -1 for a stack without the tag (it stands at the definition's own cone). */
    public int stepOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(nbt, Tag.TAG_ANY_NUMERIC)) return -1;
        return clamp(tag.getInt(nbt));
    }

    /** The step a stack stands at: its tag's, or the one nearest the definition's own cone. */
    public int currentStep(ItemStack stack, float definitionCone) {
        int step = stepOf(stack);
        return step >= 0 ? step : stepNear(definitionCone);
    }

    /** Sets a stack's step (the tag the definition names). */
    public void setStep(ItemStack stack, int step) {
        stack.getOrCreateTag().putInt(nbt, clamp(step));
    }

    /** A step in words for the action bar and the tooltip: {@code 36° · 59 blocks}. */
    public String describe(int step) {
        return String.format(Locale.ROOT, "%d° · %d blocks", Math.round(coneAt(step)), Math.round(rangeAt(step)));
    }

    /** Whether a step is the wide end, the tight end or between. */
    public String endOf(int step) {
        step = clamp(step);
        return step == 0 ? "widest" : step == steps ? "tightest" : "";
    }
}
