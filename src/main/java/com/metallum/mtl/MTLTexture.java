package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLTexture {
    private static final Msg PIXEL_FORMAT = Msg.of("pixelFormat", JAVA_LONG);
    private static final Msg WIDTH = Msg.of("width", JAVA_LONG);
    private static final Msg HEIGHT = Msg.of("height", JAVA_LONG);
    private static final Msg TEXTURE_TYPE = Msg.of("textureType", JAVA_LONG);
    private static final Msg ARRAY_LENGTH = Msg.of("arrayLength", JAVA_LONG);
    private static final Msg MIPMAP_LEVEL_COUNT = Msg.of("mipmapLevelCount", JAVA_LONG);
    private static final Msg STORAGE_MODE = Msg.of("storageMode", JAVA_LONG);
    private static final Msg DEVICE = Msg.of("device", ADDRESS);
    private static final Msg LENGTH = Msg.of("length", JAVA_LONG);
    private static final Msg NEW_TEXTURE_VIEW = Msg.of(
            "newTextureViewWithPixelFormat:textureType:levels:slices:",
            ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg NEW_TEXTURE_FROM_BUFFER = Msg.of(
            "newTextureWithDescriptor:offset:bytesPerRow:", ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final MemorySegment TEXTURE_DESCRIPTOR_CLS = ObjC.clazz("MTLTextureDescriptor");
    private static final Msg TEXTURE_BUFFER_DESCRIPTOR = Msg.of(
            "textureBufferDescriptorWithPixelFormat:width:resourceOptions:usage:",
            ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_STORAGE_MODE = Msg.ofVoid("setStorageMode:", JAVA_LONG);
    private static final Msg SET_HAZARD_TRACKING_MODE = Msg.ofVoid("setHazardTrackingMode:", JAVA_LONG);

    private MTLTexture() {
    }

    public static long pixelFormat(final MemorySegment texture) {
        return PIXEL_FORMAT.sendLong(texture);
    }

    public static long width(final MemorySegment texture) {
        return WIDTH.sendLong(texture);
    }

    public static long height(final MemorySegment texture) {
        return HEIGHT.sendLong(texture);
    }

    public static MemorySegment newTextureView(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {
        return newTextureViewWithFormat(texture, PIXEL_FORMAT.sendLong(texture), baseMipLevel, mipLevelCount);
    }

    /**
     * A view of {@code texture} with a DIFFERENT pixel format, same dimensions, mips and slices.
     *
     * <p>This exists for one caller: reading the frame's Depth32Float depth as R32Float. A depth texture
     * sampled through a {@code sampler2D} becomes MSL {@code texture2d<float>}, which Metal answers with
     * zeros -- it requires {@code depth2d}, which SPIRV-Cross only emits for a shadow sampler, i.e. for a
     * comparison read that returns a boolean rather than the depth. An R32Float view is an ordinary
     * colour texture and samples correctly.
     *
     * <p>Requires {@code MTLTextureUsagePixelFormatView} on the parent; {@link
     * com.metallum.render.MetalGpuTexture} grants it to every render attachment. Returns
     * {@link MemorySegment#NULL} on a bad mip range, and the caller must treat a NULL as "this format
     * view is not available" rather than assuming it worked -- Metal refuses the view outright for
     * format pairs it does not consider view-compatible.
     */
    public static MemorySegment newTextureViewWithFormat(final MemorySegment texture, final long pixelFormat,
                                                         final long baseMipLevel, final long mipLevelCount) {
        if (pixelFormat == MTLPixelFormat.Invalid.value) {
            return MemorySegment.NULL;
        }
        if (mipLevelCount <= 0) {
            return MemorySegment.NULL;
        }
        long totalMipLevels = MIPMAP_LEVEL_COUNT.sendLong(texture);
        if (baseMipLevel >= totalMipLevels || baseMipLevel + mipLevelCount > totalMipLevels) {
            return MemorySegment.NULL;
        }
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return NEW_TEXTURE_VIEW.sendPtr(
                    texture,
                    pixelFormat,
                    TEXTURE_TYPE.sendLong(texture),
                    baseMipLevel, mipLevelCount,
                    0L, sliceCount(texture)
            );
        }
    }

    public static MemorySegment newBufferTextureView(
            final MemorySegment buffer,
            final long pixelFormat,
            final long offset,
            final long width,
            final long bytesPerRow
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            if (pixelFormat == MTLPixelFormat.Invalid.value || width <= 0 || bytesPerRow <= 0 || offset < 0) {
                return MemorySegment.NULL;
            }
            long bufferLength = LENGTH.sendLong(buffer);
            if (offset > bufferLength || bytesPerRow > bufferLength - offset) {
                return MemorySegment.NULL;
            }

            MemorySegment device = DEVICE.sendPtr(buffer);
            long alignment = MTLDevice.minimumTextureBufferAlignment(device, pixelFormat);
            if (alignment <= 0 || offset % alignment != 0) {
                return MemorySegment.NULL;
            }
            long alignedBytesPerRow = roundUp(bytesPerRow, alignment);

            MemorySegment descriptor = TEXTURE_BUFFER_DESCRIPTOR.sendPtr(
                    TEXTURE_DESCRIPTOR_CLS, pixelFormat, width, 0L, MTLTextureUsage.ShaderRead.value);
            SET_STORAGE_MODE.send(descriptor, STORAGE_MODE.sendLong(buffer));
            SET_HAZARD_TRACKING_MODE.send(descriptor, MTLHazardTrackingMode.Untracked.value);

            return NEW_TEXTURE_FROM_BUFFER.sendPtr(buffer, descriptor, offset, alignedBytesPerRow);
        }
    }

    private static long sliceCount(final MemorySegment texture) {
        long type = TEXTURE_TYPE.sendLong(texture);
        if (type == MTLTextureType.Type2DArray.value) {
            return Math.max(ARRAY_LENGTH.sendLong(texture), 1L);
        }
        if (type == MTLTextureType.TypeCube.value) {
            return 6L;
        }
        if (type == MTLTextureType.TypeCubeArray.value) {
            return Math.max(ARRAY_LENGTH.sendLong(texture), 1L) * 6L;
        }
        return 1L;
    }

    private static long roundUp(final long value, final long alignment) {
        long remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }
}
