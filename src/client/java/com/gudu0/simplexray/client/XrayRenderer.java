package com.gudu0.simplexray.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class XrayRenderer {

    public static void register() {
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
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer buffer = consumers.getBuffer(XrayRenderLayers.OUTLINE);

        for (List<XrayBlockCache.CachedBlock> chunkMatches : XrayBlockCache.getAllMatches()) {
            for (XrayBlockCache.CachedBlock match : chunkMatches) {
                int color = XrayConfig.getColor(match.block());
                float r = ((color >> 16) & 0xFF) / 255f;
                float g = ((color >> 8) & 0xFF) / 255f;
                float b = (color & 0xFF) / 255f;

                Box box = new Box(match.pos()).expand(0.002);
                WorldRenderer.drawBox(matrices, buffer, box, r, g, b, 1f);
            }
        }

        matrices.pop();
    }
}