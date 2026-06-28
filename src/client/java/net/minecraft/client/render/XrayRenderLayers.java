package net.minecraft.client.render;

import java.util.OptionalDouble;

public final class XrayRenderLayers {
    private XrayRenderLayers() {}

    public static final RenderLayer OUTLINE = RenderLayer.of(
            "xray_outline",
            VertexFormats.LINES,
            VertexFormat.DrawMode.LINES,
            256,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.LINES_PROGRAM)
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(3.0)))
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .depthTest(RenderLayer.ALWAYS_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .build(false)
    );
}