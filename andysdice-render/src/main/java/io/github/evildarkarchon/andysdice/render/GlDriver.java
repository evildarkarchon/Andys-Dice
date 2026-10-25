package io.github.evildarkarchon.andysdice.render;

import java.io.Serializable;

/**
 * What an OpenGL driver says about itself, for the log and for the fail-fast (ADR-0002).
 *
 * @param renderer {@code GL_RENDERER}, for example "NVIDIA GeForce RTX 4070/PCIe/SSE2" or "GDI Generic"
 * @param vendor {@code GL_VENDOR}
 * @param version {@code GL_VERSION}, which also carries the driver's own version
 */
public record GlDriver(String renderer, String vendor, String version) implements Serializable {

    @Override
    public String toString() {
        return "renderer: " + renderer + ", vendor: " + vendor + ", version: " + version;
    }
}
