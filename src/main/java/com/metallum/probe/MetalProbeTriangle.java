package com.metallum.probe;

import com.metallum.Metallum;
import com.metallum.mtl.MTLCullMode;
import com.metallum.mtl.MTLCommandBuffer;
import com.metallum.mtl.MTLCompareFunction;
import com.metallum.mtl.MTLDepthStencilDescriptor;
import com.metallum.mtl.MTLDevice;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLPrimitiveType;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.metallum.mtl.MTLRenderPipelineDescriptor;
import com.metallum.mtl.MTLTexture;
import com.metallum.objc.ObjC;
import com.metallum.render.MetalInterop;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Throwaway P0 probe: draws a single magenta triangle into whatever render pass Metallum
 * currently has open, using only the public {@link MetalInterop} surface. Its purpose is to
 * prove, before any of the Voxy port is written, that a foreign renderer can
 *
 * <ol>
 *   <li>obtain the frame's command buffer and the active pass's colour + depth attachments,</li>
 *   <li>close Metallum's encoder and open its own on those same attachments, and</li>
 *   <li>have its geometry depth-composite correctly against the near scene.</li>
 * </ol>
 *
 * <p>Enabled with {@code -Dmetallum.probe=true}. The triangle is placed at the far end of the
 * depth range in whichever convention the active pass uses, so it must appear only where no
 * nearer terrain covers it. Delete this class (and {@code MetalProbeMixin}) once P0 is closed.
 */
@Environment(EnvType.CLIENT)
public final class MetalProbeTriangle {
    /**
     * Enabled by {@code -Dmetallum.probe=true} or the {@code METALLUM_PROBE=true} environment
     * variable (the env var survives the Gradle {@code runClient} fork without extra plumbing).
     */
    public static final boolean ENABLED = Boolean.getBoolean("metallum.probe")
            || "true".equalsIgnoreCase(System.getenv("METALLUM_PROBE"));

    private static @Nullable MemorySegment pipeline;
    private static @Nullable MemorySegment depthState;
    private static int drawn;
    private static int skipped;

    private MetalProbeTriangle() {
    }

    public static void draw(final String label, @Nullable final DepthStencilState depthStencil) {
        if (!ENABLED || !MetalInterop.isAvailable()) {
            return;
        }
        // Only draw once Metallum already has an encoder open for this pass: otherwise the pass's
        // pending clear has not been materialised and would wipe what we draw.
        if (MetalInterop.currentRenderEncoderHandle() == 0L) {
            if (skipped++ % 600 == 0) {
                Metallum.LOGGER.info("[metallum-probe] no open render encoder yet; skipping");
            }
            return;
        }

        long color = MetalInterop.currentColorAttachmentHandle();
        long depth = MetalInterop.currentDepthAttachmentHandle();
        int width = MetalInterop.currentViewportWidth();
        int height = MetalInterop.currentViewportHeight();
        if (color == 0L || width <= 0 || height <= 0) {
            return;
        }

        MTLCompareFunction compare = depthCompare(depthStencil);
        if (!ensurePipeline(color, depth, compare)) {
            return;
        }

        MetalInterop.endCurrentEncoder();

        MTLCommandBuffer commandBuffer = MetalInterop.commandBuffer();
        if (commandBuffer == null) {
            return;
        }

        MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(
                MemorySegment.ofAddress(color),
                null,
                MemorySegment.ofAddress(depth),
                null,
                width,
                height
        );
        encoder.setRenderPipelineState(pipeline);
        encoder.setDepthStencilState(depthState);
        encoder.setCullMode(MTLCullMode.None);
        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
        encoder.endEncoding();

        if (drawn++ % 120 == 0) {
            Metallum.LOGGER.info("[metallum-probe] drew triangle into {}x{} pass (label={}, depthCompare={})",
                    width, height, label, compare);
        }
    }

    private static MTLCompareFunction depthCompare(@Nullable final DepthStencilState depthStencil) {
        if (depthStencil == null || depthStencil.depthTest() == null) {
            return MTLCompareFunction.LessEqual;
        }
        return MTLCompareFunction.from(depthStencil.depthTest());
    }

    private static boolean ensurePipeline(final long colorTexture, final long depthTexture, final MTLCompareFunction compare) {
        if (pipeline != null) {
            return true;
        }
        MTLDevice device = MetalInterop.mtlDevice();
        if (device == null) {
            return false;
        }

        // Reverse-Z conventions compare Greater and treat 0 as the far plane.
        boolean reverseZ = compare == MTLCompareFunction.Greater || compare == MTLCompareFunction.GreaterEqual;
        double z = reverseZ ? 0.001 : 0.999;

        MemorySegment vertexFunction = device.newFunction(msl(z), "probe_vertex");
        MemorySegment fragmentFunction = device.newFunction(msl(z), "probe_fragment");
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            Metallum.LOGGER.error("[metallum-probe] MSL compile failed");
            return false;
        }

        MTLPixelFormat colorFormat = pixelFormatOf(MTLTexture.pixelFormat(MemorySegment.ofAddress(colorTexture)));
        MTLPixelFormat depthFormat = depthTexture == 0L
                ? MTLPixelFormat.Invalid
                : pixelFormatOf(MTLTexture.pixelFormat(MemorySegment.ofAddress(depthTexture)));
        MTLPixelFormat stencilFormat = MTLPixelFormat.hasStencil(depthFormat.value)
                ? depthFormat
                : MTLPixelFormat.Invalid;

        try (MTLRenderPipelineDescriptor descriptor = new MTLRenderPipelineDescriptor()) {
            descriptor.setCompiledFunctions(vertexFunction, fragmentFunction);
            descriptor.setColorAttachmentFormat(0, colorFormat);
            descriptor.setDepthStencilFormats(depthFormat, stencilFormat);
            pipeline = device.newRenderPipelineState(descriptor);
        }
        if (ObjC.isNil(pipeline)) {
            pipeline = null;
            Metallum.LOGGER.error("[metallum-probe] pipeline state creation failed (color={}, depth={})", colorFormat, depthFormat);
            return false;
        }

        try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
            descriptor.depthCompareFunction(compare);
            descriptor.depthWriteEnabled(true);
            depthState = device.newDepthStencilState(descriptor);
        }
        if (ObjC.isNil(depthState)) {
            depthState = null;
            pipeline = null;
            Metallum.LOGGER.error("[metallum-probe] depth stencil state creation failed");
            return false;
        }

        Metallum.LOGGER.info("[metallum-probe] ready: color={} depth={} compare={} reverseZ={}",
                colorFormat, depthFormat, compare, reverseZ);
        return true;
    }

    private static @Nullable MemorySegment forcedPipeline;
    private static @Nullable MemorySegment forcedDepthState;

    /**
     * Draw a magenta triangle NOW, at whatever point in the frame the caller has reached, the way
     * the probe draws: end the open encoder, create a fresh one straight from the command buffer on
     * the given colour+depth attachments, draw, end it.
     *
     * <p>Two deliberate differences from {@link #draw}, both to isolate an encoder-lifecycle bug:
     * it ignores the "is an encoder open" guard (so it still runs when Metallum's encoder is
     * closed, which is the state Voxy's LOD pass is in after the mid-frame submit), and it never
     * touches Metallum's {@code renderCommandEncoderForHandles} bookkeeping -- the encoder is made
     * directly from the command buffer. Depth testing is disabled so that nothing about depth
     * conventions can mask the result: the only question is whether a draw at this point in the
     * frame reaches the framebuffer.
     *
     * <p>Handles are passed in rather than read from {@link MetalInterop} because a caller sitting
     * between passes has them while {@code currentColorAttachmentHandle()} may not.
     *
     * @return true if a draw was issued
     */
    public static boolean drawForced(final long colorTexture, final long depthTexture,
                                     final int width, final int height, final String label) {
        if (!MetalInterop.isAvailable() || colorTexture == 0L || width <= 0 || height <= 0) {
            return false;
        }
        if (!ensureForcedPipeline(colorTexture, depthTexture)) {
            return false;
        }
        MetalInterop.endCurrentEncoder();
        final MTLCommandBuffer commandBuffer = MetalInterop.commandBuffer();
        if (commandBuffer == null) {
            return false;
        }
        final MTLRenderCommandEncoder encoder = commandBuffer.makeRenderCommandEncoder(
                MemorySegment.ofAddress(colorTexture), null,
                MemorySegment.ofAddress(depthTexture), null,
                width, height);
        encoder.setRenderPipelineState(forcedPipeline);
        if (forcedDepthState != null) {
            encoder.setDepthStencilState(forcedDepthState);
        }
        encoder.setCullMode(MTLCullMode.None);
        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
        encoder.endEncoding();
        if (forcedDrawn++ % 120 == 0) {
            Metallum.LOGGER.info("[metallum-probe] forced triangle at {}x{} (label={})", width, height, label);
        }
        return true;
    }

    private static int forcedDrawn;

    private static boolean ensureForcedPipeline(final long colorTexture, final long depthTexture) {
        if (forcedPipeline != null) {
            return true;
        }
        final MTLDevice device = MetalInterop.mtlDevice();
        if (device == null) {
            return false;
        }
        final MTLPixelFormat colorFormat = pixelFormatOf(MTLTexture.pixelFormat(MemorySegment.ofAddress(colorTexture)));
        final MTLPixelFormat depthFormat = depthTexture == 0L
                ? MTLPixelFormat.Invalid
                : pixelFormatOf(MTLTexture.pixelFormat(MemorySegment.ofAddress(depthTexture)));
        final MTLPixelFormat stencilFormat = MTLPixelFormat.hasStencil(depthFormat.value)
                ? depthFormat
                : MTLPixelFormat.Invalid;
        final MemorySegment vertexFunction = device.newFunction(msl(0.5), "probe_vertex");
        final MemorySegment fragmentFunction = device.newFunction(msl(0.5), "probe_fragment");
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            Metallum.LOGGER.error("[metallum-probe] forced: MSL compile failed");
            return false;
        }
        try (MTLRenderPipelineDescriptor descriptor = new MTLRenderPipelineDescriptor()) {
            descriptor.setCompiledFunctions(vertexFunction, fragmentFunction);
            descriptor.setColorAttachmentFormat(0, colorFormat);
            descriptor.setDepthStencilFormats(depthFormat, stencilFormat);
            forcedPipeline = device.newRenderPipelineState(descriptor);
        }
        if (ObjC.isNil(forcedPipeline)) {
            forcedPipeline = null;
            Metallum.LOGGER.error("[metallum-probe] forced: pipeline creation failed");
            return false;
        }
        try (MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.create()) {
            descriptor.depthCompareFunction(MTLCompareFunction.Always);
            descriptor.depthWriteEnabled(false);
            forcedDepthState = device.newDepthStencilState(descriptor);
        }
        if (ObjC.isNil(forcedDepthState)) {
            // Not fatal: with no depth-stencil state bound Metal disables the depth test anyway.
            forcedDepthState = null;
        }
        Metallum.LOGGER.info("[metallum-probe] forced pipeline ready: color={} depth={} (depth test off)",
                colorFormat, depthFormat);
        return true;
    }

    private static String msl(final double z) {
        return """
                #include <metal_stdlib>
                using namespace metal;

                struct ProbeVertexOut {
                    float4 position [[position]];
                };

                vertex ProbeVertexOut probe_vertex(uint vertexId [[vertex_id]]) {
                    float2 corners[3] = { float2(-0.6, -0.45), float2(0.6, -0.45), float2(0.0, 0.7) };
                    ProbeVertexOut out;
                    out.position = float4(corners[vertexId], %s, 1.0);
                    return out;
                }

                fragment float4 probe_fragment() {
                    return float4(1.0, 0.0, 1.0, 1.0);
                }
                """.formatted(Double.toString(z));
    }

    private static MTLPixelFormat pixelFormatOf(final long value) {
        for (MTLPixelFormat format : MTLPixelFormat.values()) {
            if (format.value == value) {
                return format;
            }
        }
        return MTLPixelFormat.Invalid;
    }
}
