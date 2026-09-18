package com.metallum.render;

import com.metallum.mtl.MTLCommandBuffer;
import com.metallum.mtl.MTLDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Public integration surface for external Metal renderers (e.g. Voxy) that must encode their
 * own compute and render work into Metallum's frame.
 *
 * <p>Metallum owns the {@code MTLDevice}, the frame's {@code MTLCommandBuffer} and the render
 * passes Sodium and vanilla Minecraft draw into. A foreign renderer that wants to contribute
 * geometry to that same frame — sharing the colour and depth attachments, and therefore getting
 * depth coherence with the near scene for free — uses this class to:
 *
 * <ol>
 *   <li>Read {@link #currentCommandBufferHandle()} and the attachment handles.</li>
 *   <li>If it needs a compute or render encoder of its own, call {@link #endCurrentEncoder()}
 *       first: Metal forbids two open encoders on one command buffer. Metallum's current render
 *       encoder is closed, and any state it held is re-applied on its next draw.</li>
 *   <li>Encode its work directly against the returned native handles. The command buffer and
 *       attachment pointers are ordinary {@code id<MTLCommandBuffer>} / {@code id<MTLTexture>}
 *       objects, indistinguishable from ones the foreign renderer creates itself, so a renderer
 *       with its own native Metal layer needs no Metal-side glue here.</li>
 *   <li>End every encoder it opened before returning. Metallum reopens its render encoder lazily
 *       on the next draw, with load actions that preserve whatever the foreign renderer wrote.</li>
 * </ol>
 *
 * <p>All handles are raw Objective-C object pointers ({@code MemorySegment.address()}); a value of
 * {@code 0} means "not available". Handles are only valid for the duration of the current frame
 * and must not be retained across submissions.
 *
 * <p>This is intentionally a thin static façade so that a consumer can reach it reflectively and
 * avoid a hard compile-time dependency on Metallum.
 */
@Environment(EnvType.CLIENT)
public final class MetalInterop {
    private static volatile MetalDevice activeDevice;

    private MetalInterop() {
    }

    static void publishDevice(final MetalDevice device) {
        activeDevice = device;
    }

    /** True once Metallum has created its device and this surface is usable. */
    public static boolean isAvailable() {
        return activeDevice != null;
    }

    /** The process-wide {@code id<MTLDevice>}, or {@code 0} if unavailable. */
    public static long deviceHandle() {
        MetalDevice device = activeDevice;
        return device == null ? 0L : device.metalDeviceHandle().address();
    }

    /** The process-wide {@code id<MTLCommandQueue>} Metallum submits on, or {@code 0}. */
    public static long commandQueueHandle() {
        MetalDevice device = activeDevice;
        return device == null ? 0L : device.commandQueue.handle().address();
    }

    /**
     * The current frame's {@code id<MTLCommandBuffer>}, creating it on demand. Encode foreign
     * compute and render work into this buffer so it is ordered with Metallum's own passes.
     */
    public static long currentCommandBufferHandle() {
        MetalDevice device = activeDevice;
        if (device == null) {
            return 0L;
        }
        return device.createCommandEncoder().commandBuffer().handle().address();
    }

    /** The colour attachment Metallum currently has bound, or {@code 0} if none. */
    public static long currentColorAttachmentHandle() {
        MetalDevice device = activeDevice;
        return device == null ? 0L : device.createCommandEncoder().currentColorAttachment().address();
    }

    /** The depth (and stencil) attachment Metallum currently has bound, or {@code 0} if none. */
    public static long currentDepthAttachmentHandle() {
        MetalDevice device = activeDevice;
        return device == null ? 0L : device.createCommandEncoder().currentDepthAttachment().address();
    }

    /**
     * The currently open {@code MTLRenderCommandEncoder}, or {@code 0} if Metallum has none open
     * (for example before the active render pass has drawn anything). A foreign renderer should
     * {@link #endCurrentEncoder() end} this before opening its own encoder.
     */
    public static long currentRenderEncoderHandle() {
        MetalDevice device = activeDevice;
        return device == null ? 0L : device.createCommandEncoder().currentRenderEncoder().address();
    }

    /** Width of the active render pass's colour attachment, or {@code 0} if no pass is active. */
    public static int currentViewportWidth() {
        MetalDevice device = activeDevice;
        return device == null ? 0 : device.createCommandEncoder().currentWidth();
    }

    /** Height of the active render pass's colour attachment, or {@code 0} if no pass is active. */
    public static int currentViewportHeight() {
        MetalDevice device = activeDevice;
        return device == null ? 0 : device.createCommandEncoder().currentHeight();
    }

    /**
     * The {@code MTLDevice} as a typed wrapper, for consumers that prefer Metallum's {@code mtl}
     * helpers over raw handles. Returns {@code null} if unavailable.
     */
    public static @Nullable MTLDevice mtlDevice() {
        MetalDevice device = activeDevice;
        return device == null ? null : device.metalDevice();
    }

    /**
     * The current frame's {@code MTLCommandBuffer} as a typed wrapper, creating it on demand.
     * Returns {@code null} if unavailable.
     */
    public static @Nullable MTLCommandBuffer commandBuffer() {
        MetalDevice device = activeDevice;
        return device == null ? null : device.createCommandEncoder().commandBuffer();
    }

    /**
     * Ends the encoder Metallum currently has open (render or blit) so that a foreign renderer can
     * open its own. Metallum reopens its render encoder lazily on the next draw, on the same
     * attachments, with load actions that preserve what the foreign renderer wrote.
     */
    public static void endCurrentEncoder() {
        MetalDevice device = activeDevice;
        if (device != null) {
            device.createCommandEncoder().endEncoder();
        }
    }

    /**
     * Returns the frame's {@code MTLRenderCommandEncoder} for these colour/depth attachment handles,
     * <b>creating one only if Metallum does not already have a matching encoder open</b>.
     *
     * <p>This is the sharing path: a foreign renderer draws on Metallum's own encoder instead of
     * asking Metal for a second encoder on the same command buffer, which Metal refuses ("A command
     * encoder is already encoding to this command buffer"). Use
     * {@link #invalidateRenderPassState()} when done.
     *
     * <p>Returned as a raw handle; 0 if unavailable.
     */
    public static long acquireRenderEncoder(final long colorHandle, final long depthHandle,
                                            final int viewportWidth, final int viewportHeight) {
        MetalDevice device = activeDevice;
        if (device == null || colorHandle == 0L) {
            return 0L;
        }
        return device.createCommandEncoder()
                .renderCommandEncoderForHandles(
                        MemorySegment.ofAddress(colorHandle),
                        MemorySegment.ofAddress(depthHandle),
                        viewportWidth,
                        viewportHeight)
                .handle()
                .address();
    }

    /**
     * Marks Metallum's active render pass state stale after a foreign renderer drew on the shared
     * encoder, so Metallum rebinds pipeline, vertex buffers and descriptors before its next draw.
     */
    public static void invalidateRenderPassState() {
        MetalDevice device = activeDevice;
        if (device != null) {
            device.createCommandEncoder().invalidateCurrentRenderPass();
        }
    }

    /**
     * Submits and waits on the frame's current command buffer, then lets the next
     * {@link #currentCommandBufferHandle()} start a fresh one. The frame therefore spans more than
     * one GPU submission.
     *
     * <p>This exists because a foreign renderer may need GPU-written data back on the CPU partway
     * through a frame — Voxy's draw path reads its GPU-generated draw commands to push
     * {@code baseInstance}, which Metal's indirect draw path does not propagate. Without a
     * mid-frame completion point those reads cannot be satisfied, and Voxy cannot encode into this
     * frame at all. Before this, Voxy either committed its own buffer (defeating the shared frame)
     * or used an IOSurface bridge to composite afterwards.
     *
     * <p>Costs a pipeline drain at the split, which is what such a renderer pays today anyway when
     * submitting its own frames. Callers should split as rarely as possible.
     *
     * <p>Must be called on the render thread, between encoders — either call
     * {@link #endCurrentEncoder()} first, or let this close the current passes itself.
     */
    public static void flushFrame() {
        MetalDevice device = activeDevice;
        if (device != null) {
            device.createCommandEncoder().submit();
        }
    }
}
