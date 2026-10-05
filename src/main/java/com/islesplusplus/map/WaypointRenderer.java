package com.islesplusplus.map;

import com.islesplus.render.WorldTagRenderer;
import com.islesplus.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

// beacon beam + floating label for the temp waypoint and the saved ones
public final class WaypointRenderer {
    // like a vanilla beacon: solid core + faint glow
    private static final float CORE = 0.2f, GLOW = 0.25f;
    private static final int GLOW_ALPHA = 0x20;
    static final int HIDE_WITHIN = 16;
    static final int BEAM_HIDE_WITHIN = 8;
    private static final int LABEL_ALPHA = 0xA0;

    private WaypointRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!IslesMap.available() || client.world == null) return;
        if (ctx.consumers() == null || ctx.matrices() == null) return;

        IslesMap.Waypoint wp = IslesMap.waypoint;
        if (wp != null) draw(ctx, client, wp.x(), wp.y(), wp.z(), Theme.HUD_RED & 0xFFFFFF, null, true);
        for (SavedWaypoints.Waypoint s : SavedWaypoints.all()) {
            if (s.inWorld) draw(ctx, client, s.x, s.y, s.z, s.color, s.name, s.beam);
        }
        if (ctx.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw();
    }

    private static void draw(WorldRenderContext ctx, MinecraftClient client, int x, int y, int z, int rgb, String name, boolean beam) {
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        double dx = x + 0.5 - cam.x, dz = z + 0.5 - cam.z;
        // the beam gets cut off past the render distance, so pull it in along the same line and it
        // still shows which way to go.
        double max = Math.max(32, client.options.getClampedViewDistance() * 16 - 16);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > max) { dx *= max / dist; dz *= max / dist; }

        MatrixStack m = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        if (beam && dist >= BEAM_HIDE_WITHIN) {
            int bottom = client.world.getBottomY(), height = client.world.getHeight();
            float time = (client.world.getTime() % 40) + client.getRenderTickCounter().getTickProgress(false);
            float scroll = -time * 0.02f % 1f;
            m.push();
            m.translate(dx, bottom - cam.y, dz);
            Matrix4f mat = m.peek().getPositionMatrix();
            prism(consumers.getBuffer(RenderLayers.beaconBeam(BeaconBlockEntityRenderer.BEAM_TEXTURE, false)),
                m, mat, CORE, height, 0xFF000000 | rgb, scroll);
            prism(consumers.getBuffer(RenderLayers.beaconBeam(BeaconBlockEntityRenderer.BEAM_TEXTURE, true)),
                m, mat, GLOW, height, GLOW_ALPHA << 24 | rgb, scroll);
            m.pop();
        }

        double away = Math.sqrt(sq(x + 0.5 - cam.x) + sq(y + 1.5 - cam.y) + sq(z + 0.5 - cam.z));
        if (away > HIDE_WITHIN) {
            String label = "◆ " + (name != null ? name + " " : "") + Math.round(away) + "m";
            WorldTagRenderer.drawTag(client, m, cam, camera.getYaw(), camera.getPitch(),
                x + 0.5, y + 1.5, z + 0.5, label, LABEL_ALPHA << 24 | rgb, 0, 1f, consumers);
        }
    }

    private static double sq(double v) { return v * v; }

    private static void prism(VertexConsumer vc, MatrixStack m, Matrix4f mat, float r, int height, int argb, float scroll) {
        float[][] corners = {{-r, r}, {r, r}, {r, -r}, {-r, -r}};
        float vTop = scroll + height * 0.5f;   // the texture repeats every 2 blocks, like a beacon's
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i], b = corners[(i + 1) % 4];
            vertex(vc, m, mat, a[0], 0, a[1], 0, scroll, argb);
            vertex(vc, m, mat, b[0], 0, b[1], 1, scroll, argb);
            vertex(vc, m, mat, b[0], height, b[1], 1, vTop, argb);
            vertex(vc, m, mat, a[0], height, a[1], 0, vTop, argb);
        }
    }

    private static void vertex(VertexConsumer vc, MatrixStack m, Matrix4f mat, float x, float y, float z, float u, float v, int argb) {
        vc.vertex(mat, x, y, z).color(argb).texture(u, v).overlay(OverlayTexture.DEFAULT_UV)
            .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(m.peek(), 0, 1, 0);
    }
}
