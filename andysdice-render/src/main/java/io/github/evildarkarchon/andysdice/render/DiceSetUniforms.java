package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform3f;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;

/**
 * Hands a {@link DiceSet} to the uber-shader ({@code die.frag}): its colours converted to linear RGB, and its
 * swirl's baked volume bound to a texture unit of its own. The uniform locations are looked up once.
 *
 * <p>Render thread only, with the program in use.
 */
final class DiceSetUniforms {

    private final int base;
    private final int roughness;
    private final int metallic;
    private final int swirlPeriodMm;
    private final int swirlColour;
    private final int swirlAmount;
    private final int swirlSoftness;
    private final int flakeColour;
    private final int flakeDensity;
    private final int flakeSize;
    private final int flakeMetallic;
    private final int pearl;
    private final int ink;
    private final int inkMetallic;
    private final int inkRoughness;
    private final int seed;
    private final int swirlUnit;

    /**
     * Looks up the uniforms and points the swirl sampler at its unit.
     *
     * @param program the linked uber-shader, in use
     * @param swirlUnit the texture unit for the swirl volume; not one of the atlas's
     */
    DiceSetUniforms(int program, int swirlUnit) {
        RenderThread.checkRenderThread();
        this.swirlUnit = swirlUnit;
        base = glGetUniformLocation(program, "uBase");
        roughness = glGetUniformLocation(program, "uRough");
        metallic = glGetUniformLocation(program, "uMetal");
        swirlPeriodMm = glGetUniformLocation(program, "uSwirlPeriodMm");
        swirlColour = glGetUniformLocation(program, "uSwirlColour");
        swirlAmount = glGetUniformLocation(program, "uSwirlAmount");
        swirlSoftness = glGetUniformLocation(program, "uSwirlSoft");
        flakeColour = glGetUniformLocation(program, "uFlakeColour");
        flakeDensity = glGetUniformLocation(program, "uFlakeDensity");
        flakeSize = glGetUniformLocation(program, "uFlakeSize");
        flakeMetallic = glGetUniformLocation(program, "uFlakeMetal");
        pearl = glGetUniformLocation(program, "uPearl");
        ink = glGetUniformLocation(program, "uInk");
        inkMetallic = glGetUniformLocation(program, "uInkMetal");
        inkRoughness = glGetUniformLocation(program, "uInkRough");
        seed = glGetUniformLocation(program, "uSeed");
        glUniform1i(glGetUniformLocation(program, "uSwirlTex"), swirlUnit);
    }

    /**
     * Sets every Dice Set uniform and binds its swirl volume (or none, for a Dice Set without a swirl).
     *
     * @param set the Dice Set
     * @param swirls where its swirl volume is baked
     */
    void apply(DiceSet set, SwirlVolumes swirls) {
        colour(base, set.base());
        glUniform1f(roughness, (float) set.roughness());
        glUniform1f(metallic, (float) set.metallic());
        DiceSet.Swirl swirl = set.swirl();
        glUniform1f(swirlPeriodMm, SwirlVolumes.PERIOD_MM);
        colour(swirlColour, swirl.colour());
        glUniform1f(swirlAmount, (float) swirl.amount());
        glUniform1f(swirlSoftness, (float) swirl.softness());
        glActiveTexture(GL_TEXTURE0 + swirlUnit);
        // With no swirl the shader never samples, so the unit may as well hold nothing.
        glBindTexture(GL_TEXTURE_3D, set.hasSwirl() ? swirls.volume(swirl) : 0);
        glActiveTexture(GL_TEXTURE0);
        colour(flakeColour, set.flakes().colour());
        glUniform1f(flakeDensity, (float) set.flakes().density());
        glUniform1f(flakeSize, (float) set.flakes().size());
        glUniform1f(flakeMetallic, (float) set.flakes().metallic());
        glUniform1f(pearl, (float) set.pearl());
        colour(ink, set.ink().colour());
        glUniform1f(inkMetallic, (float) set.ink().metallic());
        glUniform1f(inkRoughness, (float) set.ink().roughness());
    }

    /**
     * Sets one Die's offset into the swirl and flake fields, so that no two Dice of a Roll match (ADR-0009).
     *
     * @param slot the Die's place among the Dice drawn, from 0
     */
    void seed(int slot) {
        // The tray prototype's offsets: golden-ratio steps, a different one per axis, keep neighbours apart
        // anywhere within one period of the field.
        float[] s = new float[3];
        for (int i = 0; i < 3; i++) {
            s[i] = (float) (((slot + 1) * (0.618034 + 0.17 * i)) % 1.0) * SwirlVolumes.PERIOD_MM;
        }
        glUniform3f(seed, s[0], s[1], s[2]);
    }

    private static void colour(int location, SrgbColour c) {
        glUniform3f(location, (float) c.linearRed(), (float) c.linearGreen(), (float) c.linearBlue());
    }
}
