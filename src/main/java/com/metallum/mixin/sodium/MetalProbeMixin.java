package com.metallum.mixin.sodium;

import com.metallum.probe.MetalProbeTriangle;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P0 probe hook: runs at the same Sodium injection point Voxy will use — the tail of the CUTOUT
 * terrain pass — and asks {@link MetalProbeTriangle} to draw into that pass. Delete alongside it.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MetalProbeMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void metallumProbe$drawTriangle(
            ChunkRenderMatrices matrices,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            FogParameters parameters,
            boolean indexedRenderingEnabled,
            GpuSampler terrainSampler,
            GpuBufferSlice uniformData,
            GpuBuffer sectionTimeInfo,
            CallbackInfo ci
    ) {
        if (!MetalProbeTriangle.ENABLED || renderPass != DefaultTerrainRenderPasses.CUTOUT) {
            return;
        }
        RenderPipeline pipeline = renderPass.getPipeline();
        DepthStencilState depthStencil = pipeline == null ? null : pipeline.getDepthStencilState();
        MetalProbeTriangle.draw("CUTOUT", depthStencil);
    }
}
