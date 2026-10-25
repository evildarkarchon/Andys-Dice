package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The dev-only gallery (ADR-0005): renders a still of every Die Kind in each of a few fixed poses into a
 * directory of PNGs, so the look can be judged by eye. Nothing asserts on its pixels; later tickets add their
 * axes (Numerals #41, Dice Sets #42, Trays #44) to the same stills.
 *
 * <p>It is behind a flag, never part of a normal run: {@value #FLAG} on the app's command line (once the app
 * routes it here), or in dev {@code mvn -pl andysdice-render -am process-classes -Pgallery}, which writes to
 * {@code andysdice-render/target/gallery}.
 *
 * <p>It starts the JVM's one render thread (ADR-0002) with a capture transport and closes it when done, so it
 * runs in a JVM of its own, never beside the app's window.
 */
public final class Gallery {

    /** The command-line flag that asks for the gallery instead of the app. */
    public static final String FLAG = "--gallery";

    /** Each still is a square of this many pixels. */
    public static final int SIZE_PX = 720;

    /** Where the dev run writes, relative to the working directory, when given no directory. */
    private static final Path DEFAULT_DIRECTORY = Path.of("target", "gallery");

    // The prototype tray's 32 degree lens, looking down at 35 degrees.
    private static final double FOV_Y_DEGREES = 32;
    private static final double PITCH_DEGREES = 35;
    // Yaw on the resting pose, so that no face is seen square on.
    private static final double REST_YAW_DEGREES = 25;
    // Room round the Die's bounding sphere.
    private static final double MARGIN = 1.1;
    private static final long STILL_TIMEOUT_SECONDS = 30;

    /** The fixed poses each Die Kind is shown in. */
    public enum Pose {
        /** At rest on the table, its last face-table face up (the d4: that face down, its corner up). */
        FACE_UP,
        /** An edge turned toward the camera: the fillet between face 0 and its first neighbour. */
        EDGE_ON,
        /** A corner turned toward the camera: one of the spherical caps. */
        CORNER_ON
    }

    private Gallery() {}

    /**
     * Runs the gallery from the command line.
     *
     * @param args optionally, the directory to write to (default {@code target/gallery})
     * @throws IOException if a still can't be written
     */
    public static void main(String[] args) throws IOException {
        Path directory = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : DEFAULT_DIRECTORY;
        try {
            List<Path> written = write(directory);
            System.out.println("gallery: " + written.size() + " stills in " + directory.toAbsolutePath());
        } catch (GlUnavailableException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Renders every still into {@code directory}, named {@code <die kind>-<pose>.png} (for example
     * {@code d20-edge-on.png}), replacing any of the same name. Starts and closes the JVM's one render thread.
     *
     * @param directory where to write; created if missing
     * @return the files written, Die Kind by Die Kind in enum order, poses in enum order within each
     * @throws IOException if the directory or a still can't be written
     * @throws GlUnavailableException if there is no OpenGL 3.3 core context
     * @throws IllegalStateException if a render thread was already started in this JVM, or a still timed out
     */
    public static List<Path> write(Path directory) throws IOException {
        Files.createDirectories(directory);
        CaptureTransport capture = new CaptureTransport();
        DiceScene scene = new DiceScene();
        List<Path> written = new ArrayList<>();
        try (RenderThread renderer = RenderThread.start(capture, scene)) {
            renderer.resize(SIZE_PX, SIZE_PX);
            for (DieKind kind : DieKind.values()) {
                for (Pose pose : Pose.values()) {
                    Still still = await(capture.take(renderer, () -> {
                        scene.setCamera(camera(kind));
                        scene.setDice(List.of(new DiceScene.Die(kind, new Vector3d(), orientation(kind, pose))));
                    }));
                    Path file = directory.resolve(kind.name().toLowerCase(Locale.ROOT) + "-"
                            + pose.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png");
                    if (!ImageIO.write(still.toImage(), "png", file.toFile())) {
                        throw new IOException("no PNG writer for " + file);
                    }
                    written.add(file);
                }
            }
        }
        return List.copyOf(written);
    }

    /** Waits for a still, unwrapping what the render thread failed with. */
    private static Still await(CompletableFuture<Still> still) {
        try {
            return still.get(STILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while taking a still", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("a still took longer than " + STILL_TIMEOUT_SECONDS + " s", e);
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
    }

    /** The unit direction from the target toward the camera. */
    private static Vector3dc towardCamera() {
        double pitch = Math.toRadians(PITCH_DEGREES);
        return new Vector3d(0, Math.sin(pitch), Math.cos(pitch));
    }

    /** A camera on {@link #towardCamera()} that fits the Die's bounding sphere in the frame. */
    private static DiceScene.Camera camera(DieKind kind) {
        double radius = 0;
        for (Vector3dc v : kind.solid().vertices()) {
            radius = Math.max(radius, v.length());
        }
        double distance = MARGIN * radius / Math.sin(Math.toRadians(FOV_Y_DEGREES / 2));
        return new DiceScene.Camera(new Vector3d(towardCamera()).mul(distance), new Vector3d(), FOV_Y_DEGREES);
    }

    /** The model-to-world rotation of a pose: which way the Die's z-up model frame turns. */
    static Quaterniondc orientation(DieKind kind, Pose pose) {
        Solid solid = kind.solid();
        return switch (pose) {
            case FACE_UP -> {
                Vector3dc last = solid.faces().getLast().normal();
                // The d4 is read from the top: the face whose corner points up lies on the table.
                Vector3dc toward = kind == DieKind.D4 ? new Vector3d(0, -1, 0) : new Vector3d(0, 1, 0);
                yield new Quaterniond().rotationY(Math.toRadians(REST_YAW_DEGREES))
                        .mul(new Quaterniond().rotationTo(last, toward));
            }
            case EDGE_ON -> {
                Vector3d edge = new Vector3d(solid.faces().getFirst().normal())
                        .add(solid.faces().get(solid.neighbours(0).getFirst()).normal()).normalize();
                yield new Quaterniond().rotationTo(edge, towardCamera());
            }
            case CORNER_ON -> {
                Vector3d corner = new Vector3d(solid.vertices().getFirst()).normalize();
                yield new Quaterniond().rotationTo(corner, towardCamera());
            }
        };
    }
}
