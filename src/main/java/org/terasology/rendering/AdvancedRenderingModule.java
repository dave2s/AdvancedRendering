/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering;

import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.rendering.cameras.Camera;

import org.terasology.rendering.dag.gsoc.ModuleRendering;
import org.terasology.rendering.dag.gsoc.NewNode;
import org.terasology.rendering.dag.nodes.*;
import org.terasology.rendering.opengl.FBO;
import org.terasology.rendering.opengl.FboConfig;
import org.terasology.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.rendering.opengl.fbms.ImmutableFbo;
import org.terasology.rendering.opengl.fbms.ShadowMapResolutionDependentFbo;

import static org.terasology.rendering.opengl.ScalingFactors.ONE_16TH_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.ONE_32TH_SCALE;

@RegisterSystem
public class AdvancedRenderingModule extends ModuleRendering {

    private DisplayResolutionDependentFbo displayResolutionDependentFbo;
    private ShadowMapResolutionDependentFbo shadowMapResolutionDependentFbo;
    private ImmutableFbo immutableFbo;

    private ShadowMapNode shadowMapNode;

    @Override
    public void initialise() {
        super.initialise(this.getClass());
        context.put(AdvancedRenderingModule.class,this);

        initAdvancedRendering();
    }

    private void initAdvancedRendering() {
        immutableFbo = new ImmutableFbo();
        context.put(ImmutableFbo.class, immutableFbo);

        shadowMapResolutionDependentFbo = new ShadowMapResolutionDependentFbo();
        context.put(ShadowMapResolutionDependentFbo.class, shadowMapResolutionDependentFbo);

        displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);

        addAmbientOcclusion();

        addHaze();

        // worldRenderer.requestTaskListRefresh();
    }

    private void addAmbientOcclusion() {
        NewNode opaqueObjectsNode = renderGraph.findNode("BasicRendering:opaqueObjectsNode");
        NewNode opaqueBlocksNode = renderGraph.findAka("opaqueBlocks");
        NewNode alphaRejectBlocksNode = renderGraph.findAka("alphaRejectBlocks");
        NewNode applyDeferredLightingNode = renderGraph.findAka("applyDeferredLighting");

        NewNode ambientOcclusionNode = new AmbientOcclusionNode("ambientOcclusionNode", context);
        renderGraph.connectBufferPair(applyDeferredLightingNode, 1, ambientOcclusionNode, 1);
        renderGraph.connectRunOrder(opaqueObjectsNode, 3, ambientOcclusionNode, 1);
        renderGraph.connectRunOrder(opaqueBlocksNode, 3, ambientOcclusionNode, 2);
        renderGraph.connectRunOrder(alphaRejectBlocksNode, 4, ambientOcclusionNode, 3);
        renderGraph.addNode(ambientOcclusionNode);

        NewNode blurredAmbientOcclusionNode = new BlurredAmbientOcclusionNode("blurredAmbientOcclusionNode", context);
        renderGraph.connectBufferPair(ambientOcclusionNode, 1, blurredAmbientOcclusionNode, 1);
        renderGraph.connectFbo(ambientOcclusionNode, 1, blurredAmbientOcclusionNode, 1);
        renderGraph.addNode(blurredAmbientOcclusionNode);

        NewNode prePostCompositeNode = renderGraph.findAka("prePostComposite");
        renderGraph.connectFbo(blurredAmbientOcclusionNode, 1, prePostCompositeNode, 1);
    }

    private void addHaze() {
        NewNode backdropNode = renderGraph.findAka("backdrop");
        NewNode lastUpdatedGBufferClearingNode = renderGraph.findAka("lastUpdatedGBufferClearing");

        FboConfig intermediateHazeConfig = new FboConfig(HazeNode.INTERMEDIATE_HAZE_FBO_URI, ONE_16TH_SCALE, FBO.Type.DEFAULT);
        FBO intermediateHazeFbo = displayResolutionDependentFbo.request(intermediateHazeConfig);

        HazeNode intermediateHazeNode = new HazeNode("intermediateHazeNode", context,
                intermediateHazeFbo);
        // TODO I introduce new BufferPairConnection but I have to fetch it from the old system. This must be removed when every node uses new system
        // make this implicit
        // intermediateHazeNode.addInputBufferPairConnection(1, new Pair<FBO,FBO>(displayResolutionDependentFbo.getGBufferPair().getLastUpdatedFbo(),
        //                                                                          displayResolutionDependentFbo.getGBufferPair().getStaleFbo()));
        renderGraph.connectFbo(backdropNode, 1, intermediateHazeNode, 1);
        renderGraph.addNode(intermediateHazeNode);

        FboConfig finalHazeConfig = new FboConfig(HazeNode.FINAL_HAZE_FBO_URI, ONE_32TH_SCALE, FBO.Type.DEFAULT);
        FBO finalHazeFbo = displayResolutionDependentFbo.request(finalHazeConfig);

        HazeNode finalHazeNode = new HazeNode("finalHazeNode", context, finalHazeFbo);
        renderGraph.connectBufferPair(lastUpdatedGBufferClearingNode, 1, finalHazeNode, 1);
        renderGraph.connectFbo(intermediateHazeNode, 1, finalHazeNode, 1);
        // Hack because HazeNode extends Blur which is a reusable node and we can't tailor its code to this need
        finalHazeNode.addOutputBufferPairConnection(1, lastUpdatedGBufferClearingNode.getOutputBufferPairConnection(1));
        renderGraph.addNode(finalHazeNode);

        NewNode opaqueObjectsNode = renderGraph.findAka("opaqueObjects");
        NewNode opaqueBlocksNode = renderGraph.findAka("opaqueBlocks");
        NewNode alphaRejectBlocksNode = renderGraph.findAka("alphaRejectBlocks");
        NewNode overlaysNode = renderGraph.findAka("overlays");
        renderGraph.reconnectBufferPair(finalHazeNode, 1, opaqueObjectsNode, 1);
        renderGraph.reconnectBufferPair(finalHazeNode, 1, opaqueBlocksNode, 1);
        renderGraph.reconnectBufferPair(finalHazeNode, 1, alphaRejectBlocksNode, 1);
        renderGraph.reconnectBufferPair(finalHazeNode, 1, overlaysNode, 1);

        NewNode prePostCompositeNode = renderGraph.findAka("prePostComposite");
        renderGraph.connectFbo(finalHazeNode, 1, prePostCompositeNode, 3);
    }

    public Camera getLightCamera() {
        //FIXME: remove this methodw
        return shadowMapNode.shadowMapCamera;
    }


}
