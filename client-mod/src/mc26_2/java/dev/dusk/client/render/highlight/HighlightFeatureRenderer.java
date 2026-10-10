package dev.dusk.client.render.highlight;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

import java.util.List;

/** Draws Block Highlight among the frame's submits, so it shares the level's buffers and ordering. */
public final class HighlightFeatureRenderer extends RenderTypeFeatureRenderer<HighlightFeatureRenderer.Node> {
    public static final FeatureRendererType<Node> TYPE = FeatureRendererType.create("Dusk block highlight");
    public static final Node NODE = new Node();

    @Override
    protected void buildGroup(FeatureFrameContext context, List<Node> submits) {
        HighlightGpu.drawInto(this::getVertexBuilder);
    }

    /** The one submit per frame; everything it draws comes from HighlightEngine. */
    public static final class Node implements SubmitNode {
        private Node() {}

        @Override
        public FeatureRendererType<Node> featureType() {
            return TYPE;
        }
    }
}
