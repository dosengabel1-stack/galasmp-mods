package net.galasmp.videowindow;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * Huelle um eine OpenGL-Textur, die jemand anderes (WaterMedia) besitzt,
 * damit Minecraft sie in der GUI zeichnen kann. Loescht die Textur nie selbst.
 */
final class ExternalTexture extends GlTexture {

    private boolean released;

    ExternalTexture(int glId, int width, int height) {
        super(GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC, "videowindow", TextureFormat.RGBA8, width, height, 1, 1, glId);
    }

    @Override
    public void close() {
        released = true;   // gehoert WaterMedia, nicht loeschen
    }

    @Override
    public boolean isClosed() {
        return released;
    }

    @Override
    public void addViews() {
    }

    @Override
    public void removeViews() {
    }

    static final class View extends GlTextureView {
        View(ExternalTexture texture) {
            super(texture, 0, 1);
        }
    }
}
