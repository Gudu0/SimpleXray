// This class lives in net.minecraft.client.render on purpose — RenderLayer.of() and
// MultiPhaseParameters are package-private in vanilla, so placing this class in that
// package is the standard workaround to access them without reflection or an access widener.
package net.minecraft.client.render;

import com.gudu0.simplexray.SimpleXray;
import org.slf4j.Logger;

import java.util.OptionalDouble;

public final class XrayRenderLayers {
    private XrayRenderLayers() {}

    Logger logger = SimpleXray.LOGGER;

    public static final RenderLayer OUTLINE = RenderLayer.of(
            "xray_outline",
            VertexFormats.LINES,
            VertexFormat.DrawMode.LINES,
            256,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.LINES_PROGRAM)
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(3.0)))
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)  // prevents z-fighting against nearby geometry
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .depthTest(RenderLayer.ALWAYS_DEPTH_TEST)       // always passes depth test = renders through walls
                    .cull(RenderPhase.DISABLE_CULLING)              // wireframe boxes need back-face rendering to show all edges
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .build(false)
    );
}