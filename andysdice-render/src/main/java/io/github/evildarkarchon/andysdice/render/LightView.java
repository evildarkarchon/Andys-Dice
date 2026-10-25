package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.tray.Tray;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The key light's view of the scene, which its shadow map is drawn from (ADR-0010), with what {@code shadow.glsl}
 * needs to turn map depths back into millimetres and PCSS penumbrae into map texels. The tray prototype's
 * {@code GlRenderer.lightView}.
 *
 * <p>A directional key gets an orthographic box fitted to what the camera is looking at, so that a close-up gets fine
 * shadow texels, but never wider than the Tray plus its walls' shadows. A spot gets a perspective frustum a little
 * wider than its cone. Pure maths, no GL.
 *
 * @param viewProjection world (mm) to the light's clip space
 * @param perspective whether it is a spot's perspective frustum rather than an orthographic box
 * @param near the near plane's distance from the light, in mm
 * @param far the far plane's distance, in mm
 * @param uvPerMm for the box, shadow-map uv per mm; for a frustum, uv per mm at 1 mm from the light
 */
record LightView(Matrix4dc viewProjection, boolean perspective, double near, double far, double uvPerMm) {

    /** Where the box's eye sits along the key direction, and its depth range, in mm (the prototype's). */
    private static final double BOX_EYE_MM = 600;
    private static final double BOX_NEAR_MM = 100;
    private static final double BOX_FAR_MM = 1100;

    /** The box's half-width: half the camera's distance, from 50 mm up to the Tray plus its walls and their shadows. */
    private static final double MIN_HALF_EXTENT_MM = 50;
    private static final double MAX_HALF_EXTENT_MM = Math.hypot(Tray.FOOTPRINT_WIDTH_MM / 2.0,
            Tray.FOOTPRINT_DEPTH_MM / 2.0) + Tray.WALL_THICKNESS_MM + 60;

    /** A spot's frustum: this much wider than its cone, and its depth range in mm. */
    private static final double SPOT_MARGIN_DEGREES = 6;
    private static final double SPOT_NEAR_MM = 150;
    private static final double SPOT_FAR_MM = 900;

    /**
     * The key light's view while the scene is seen from a camera.
     *
     * @param rig the Light Rig
     * @param camera the camera the frame is drawn from
     * @return the view its shadow map is drawn from
     */
    static LightView of(LightRig rig, DiceScene.Camera camera) {
        if (rig.spot()) {
            double fov = Math.toRadians(rig.spotOuterDegrees() * 2 + SPOT_MARGIN_DEGREES);
            Vector3dc position = rig.keyPosition();
            // Up is +z: the spot looks nearly straight down, so +y would be almost along its axis.
            Matrix4d viewProjection = new Matrix4d().perspective(fov, 1, SPOT_NEAR_MM, SPOT_FAR_MM)
                    .lookAt(position, new Vector3d(position).add(rig.spotAim()), new Vector3d(0, 0, 1));
            return new LightView(viewProjection, true, SPOT_NEAR_MM, SPOT_FAR_MM, 1 / (2 * Math.tan(fov / 2)));
        }
        double distance = camera.eye().distance(camera.target());
        double extent = Math.clamp(distance * 0.5, MIN_HALF_EXTENT_MM, MAX_HALF_EXTENT_MM);
        // Centred on the floor below what the camera looks at.
        Vector3d target = new Vector3d(camera.target().x(), 0, camera.target().z());
        Vector3d eye = new Vector3d(rig.keyPosition()).mul(BOX_EYE_MM).add(target);
        Matrix4d viewProjection = new Matrix4d().ortho(-extent, extent, -extent, extent, BOX_NEAR_MM, BOX_FAR_MM)
                .lookAt(eye, target, new Vector3d(0, 1, 0));
        return new LightView(viewProjection, false, BOX_NEAR_MM, BOX_FAR_MM, 1 / (2 * extent));
    }
}
