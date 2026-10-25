package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;

import org.joml.Vector3dc;

/**
 * One program's uniforms for the chosen Light Rig: its lights ({@code light.glsl}), its reflection room
 * ({@code env.glsl}) and the key light's shadow ({@code shadow.glsl}). The Dice and the Tray each hold one, so both
 * are lit, reflect and are shadowed alike.
 *
 * <p>Render thread only, with the program in use.
 */
final class LightUniforms {

    // shadow.glsl's bias, in mm: a spot's perspective map spends its depth precision near the light, so needs more.
    private static final float ORTHOGRAPHIC_BIAS_MM = 0.05f;
    private static final float PERSPECTIVE_BIAS_MM = 0.25f;

    private final int keyPosition;
    private final int keyColour;
    private final int spotDirection;
    private final int spotCos;
    private final int keyReference;
    private final int fillDirection;
    private final int fillColour;
    private final int environment;
    private final int lightViewProjection;
    private final int shadowBias;
    private final int lightPerspective;
    private final int lightNearFar;
    private final int lightSize;
    private final int uvPerMm;
    private final int shadowTaps;

    /**
     * Looks up a program's light uniforms and points its two shadow samplers at their units.
     *
     * @param program the linked program, which must be in use
     * @param shadowUnit the texture unit the comparing shadow map is bound to
     * @param rawShadowUnit the unit the raw shadow map is bound to (with {@link ShadowMap}'s sampler object)
     */
    LightUniforms(int program, int shadowUnit, int rawShadowUnit) {
        keyPosition = glGetUniformLocation(program, "uKeyPosition");
        keyColour = glGetUniformLocation(program, "uKeyColour");
        spotDirection = glGetUniformLocation(program, "uSpotDirection");
        spotCos = glGetUniformLocation(program, "uSpotCos");
        keyReference = glGetUniformLocation(program, "uKeyReference");
        fillDirection = glGetUniformLocation(program, "uFillDirection");
        fillColour = glGetUniformLocation(program, "uFillColour");
        environment = glGetUniformLocation(program, "uEnvironment");
        lightViewProjection = glGetUniformLocation(program, "uLightViewProjection");
        shadowBias = glGetUniformLocation(program, "uShadowBiasMm");
        lightPerspective = glGetUniformLocation(program, "uLightPerspective");
        lightNearFar = glGetUniformLocation(program, "uLightNearFar");
        lightSize = glGetUniformLocation(program, "uLightSize");
        uvPerMm = glGetUniformLocation(program, "uUvPerMm");
        shadowTaps = glGetUniformLocation(program, "uShadowTaps");
        glUniform1i(glGetUniformLocation(program, "uShadow"), shadowUnit);
        glUniform1i(glGetUniformLocation(program, "uShadowRaw"), rawShadowUnit);
    }

    /**
     * Sets a rig's lights and room, and the shadow map's view, for the next draws.
     *
     * @param rig the Light Rig
     * @param view the key light's view the shadow map was drawn from this frame
     * @param shadowTaps the PCSS taps per loop, the Quality Preset's (1 to 16)
     */
    void apply(LightRig rig, LightView view, int shadowTaps) {
        glUniform1i(this.shadowTaps, shadowTaps);
        Vector3dc key = rig.keyPosition();
        glUniform4f(keyPosition, (float) key.x(), (float) key.y(), (float) key.z(), rig.spot() ? 1 : 0);
        vector(keyColour, rig.keyColour());
        vector(spotDirection, rig.spotAim());
        glUniform2f(spotCos, (float) Math.cos(Math.toRadians(rig.spotOuterDegrees())),
                (float) Math.cos(Math.toRadians(rig.spotInnerDegrees())));
        // A spot is as bright as its colour says at the world's origin, the Tray's middle.
        glUniform1f(keyReference, (float) key.length());
        vector(fillDirection, rig.fillDirection());
        vector(fillColour, rig.fillColour());
        glUniform1i(environment, rig.room());
        glUniformMatrix4fv(lightViewProjection, false, DiceScene.columns(view.viewProjection()));
        glUniform1f(shadowBias, view.perspective() ? PERSPECTIVE_BIAS_MM : ORTHOGRAPHIC_BIAS_MM);
        glUniform1i(lightPerspective, view.perspective() ? 1 : 0);
        glUniform2f(lightNearFar, (float) view.near(), (float) view.far());
        glUniform1f(lightSize, (float) rig.lightSize());
        glUniform1f(uvPerMm, (float) view.uvPerMm());
    }

    /** Sets a {@code vec3} uniform from a vector. */
    private static void vector(int location, Vector3dc v) {
        glUniform3f(location, (float) v.x(), (float) v.y(), (float) v.z());
    }
}
