package com.gudu0.simplexray.client;

import com.gudu0.simplexray.SimpleXray;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

import java.util.List;

public class XrayRenderer {
    Logger logger = SimpleXray.LOGGER;

    public static void register() {
        // AFTER_ENTITIES fires after entity rendering but while the world render's vertex
        // consumers are still open — the latest point we can still write into them this frame.
        WorldRenderEvents.AFTER_ENTITIES.register(XrayRenderer::onRender);
    }

    private static void onRender(WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        ClientWorld world = context.world();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (world == null || player == null) return;

        Vec3d camPos = context.camera().getPos();
        matrices.push();
        // The matrix stack is in world-space, but vertex positions are in world-space too.
        // Translating by -camPos converts world-space coords to camera-relative coords so
        // the GPU sees the correct positions relative to the view origin.
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer buffer = consumers.getBuffer(XrayRenderLayers.OUTLINE);

        for (List<XrayBlockCache.CachedBlock> chunkMatches : XrayBlockCache.getAllMatches()) {
            for (XrayBlockCache.CachedBlock match : chunkMatches) {
                int color = XrayConfig.getColor(match.block());
                float r = ((color >> 16) & 0xFF) / 255f;
                float g = ((color >> 8) & 0xFF) / 255f;
                float b = (color & 0xFF) / 255f;

                // expand(0.002) pushes the wireframe just outside the block face to prevent
                // z-fighting when the camera is looking at the block from outside.
                Box box = new Box(match.pos()).expand(0.002);
                WorldRenderer.drawBox(matrices, buffer, box, r, g, b, 1f);
            }
        }

        matrices.pop();
    }
}