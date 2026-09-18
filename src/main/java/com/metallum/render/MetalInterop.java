package com.metallum.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
}
