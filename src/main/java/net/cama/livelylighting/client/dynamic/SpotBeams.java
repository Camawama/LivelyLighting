package net.cama.livelylighting.client.dynamic;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.cama.livelylighting.LivelyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The faint beam of a spot light in the air ({@code client.spot_beams}): a translucent cone from the light along its axis,
 * drawn additively after the translucent blocks, brightest at the light and gone at its range, in the light's colour. The
 * cone is a touch narrower than the lit cone so the beam reads as the bright core of the light. No texture, no particles:
 * the vanilla look of a beacon beam, pointed. Only for lights within 64 blocks of the camera.
 */
final class SpotBeams {
    private static final int SEGMENTS = 14;
    private static final double MAX_DISTANCE = 64.0;

    private SpotBeams() {}

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!LivelyConfig.get().client.spot_beams || !ClientLights.drawing()) return;
        List<DynamicLight> lights = ClientLights.lights();
        if (lights.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float pt = event.getPartialTick();
        PoseStack stack = event.getPoseStack();
        Matrix4f pose = stack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        boolean begun = false;
        for (DynamicLight light : lights) {
            if (!light.isSpot() || light.level <= 0) continue;
            Vec3 p = light.mover.at(pt);
            if (p.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) continue;
            Vec3 d = light.direction.at(pt).normalize();
            if (!begun) {
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
                RenderSystem.enableDepthTest();
                buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                begun = true;
            }
            int rgb = light.rgb < 0 ? 0xFFF4DC : light.rgb;
            float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
            float strength = Math.min(1f, light.level / 15f);
            double range = Math.max(2.0, light.range);
            double halfAngle = Math.toRadians(light.outerDegrees * 0.5 * 0.8);
            double endRadius = Math.tan(halfAngle) * range;
            // an orthonormal frame round the axis
            Vec3 up = Math.abs(d.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 u = d.cross(up).normalize();
            Vec3 v = d.cross(u).normalize();
            Vec3 apex = p.subtract(cam);
            Vec3 base = apex.add(d.scale(range));
            float apexAlpha = 0.22f * strength, baseAlpha = 0f;
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
                Vec3 r0 = base.add(u.scale(Math.cos(a0) * endRadius)).add(v.scale(Math.sin(a0) * endRadius));
                Vec3 r1 = base.add(u.scale(Math.cos(a1) * endRadius)).add(v.scale(Math.sin(a1) * endRadius));
                // a thin start so the apex isn't a hard point: the beam begins 0.3 blocks out at a small radius
                Vec3 s = apex.add(d.scale(0.3));
                double startRadius = Math.tan(halfAngle) * 0.3 + 0.08;
                Vec3 s0 = s.add(u.scale(Math.cos(a0) * startRadius)).add(v.scale(Math.sin(a0) * startRadius));
                Vec3 s1 = s.add(u.scale(Math.cos(a1) * startRadius)).add(v.scale(Math.sin(a1) * startRadius));
                vertex(buf, pose, s0, r, g, b, apexAlpha);
                vertex(buf, pose, r0, r, g, b, baseAlpha);
                vertex(buf, pose, r1, r, g, b, baseAlpha);
                vertex(buf, pose, s0, r, g, b, apexAlpha);
                vertex(buf, pose, r1, r, g, b, baseAlpha);
                vertex(buf, pose, s1, r, g, b, apexAlpha);
            }
        }
        if (begun) {
            BufferUploader.drawWithShader(buf.end());
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void vertex(BufferBuilder buf, Matrix4f pose, Vec3 p, float r, float g, float b, float a) {
        buf.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).endVertex();
    }
}
