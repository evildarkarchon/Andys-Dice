package io.github.evildarkarchon.andysdice.render;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.numerals.NumeralAtlas;
import io.github.evildarkarchon.andysdice.render.numerals.NumeralLayout;
import java.awt.image.RenderedImage;
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
 * axes (Numerals #41, Dice Sets #42, Trays #44) to the same stills. The stills show the engraved Numerals (#41),
 * and a sheet per Die Kind shows every one of its Numerals in its face's frame. Every still is taken in every
 * built-in Dice Set (#42), a directory per Dice Set. Every built-in Tray is also shown under every Light Rig (#45,
 * ADR-0010), with the 7-die set resting in it.
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

    /** Each Die Kind's sheet of Numerals is a square of at most this many pixels (its atlas, scaled down). */
    public static final int SHEET_MAX_PX = 1280;

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
    // The Tray-and-Light-Rig stills: their directory, and the gap between the resting Dice's centres.
    private static final String LIGHT_RIG_DIRECTORY = "light-rigs";
    private static final double REST_SPACING_MM = 34;

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
            System.out.println("gallery: " + written.size() + " images in " + directory.toAbsolutePath());
        } catch (GlUnavailableException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Renders every still into {@code directory}: one sub-directory per built-in Dice Set, named by its identity,
     * holding {@code <die kind>-<pose>.png} (for example {@code 08-steel/d20-edge-on.png}); then, in
     * {@code light-rigs/}, each built-in Tray under each Light Rig, {@code <tray identity>-<rig>.png} (for example
     * {@code light-rigs/01-green-felt-window.png}); then, for each Die Kind, a sheet of all its Numerals as its atlas
     * lays them out, {@code <die kind>-numerals.png}. Replaces any of the same name. Starts and closes the JVM's one
     * render thread.
     *
     * @param directory where to write; created if missing
     * @return the files written: the stills Dice Set by Dice Set in catalogue order, each Die Kind in enum order
     *     and its poses in enum order; then the Trays in built-in order, each under the rigs in enum order; then the
     *     sheets, Die Kind by Die Kind
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
            for (DiceSet set : DiceSetCatalogue.builtIn().diceSets()) {
                Path setDirectory = Files.createDirectories(directory.resolve(set.identity()));
                for (DieKind kind : DieKind.values()) {
                    for (Pose pose : Pose.values()) {
                        Still still = await(capture.take(renderer, () -> {
                            scene.setDiceSet(set);
                            scene.setCamera(camera(kind));
                            scene.setDice(List.of(new DiceScene.Die(kind, new Vector3d(),
                                    orientation(kind, pose))));
                        }));
                        Path file = setDirectory.resolve(kind.name().toLowerCase(Locale.ROOT) + "-"
                                + pose.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png");
                        png(still.toImage(), file);
                        written.add(file);
                    }
                }
            }
            // After the Dice Sets: once the scene has a Tray it always draws one.
            Path rigDirectory = Files.createDirectories(directory.resolve(LIGHT_RIG_DIRECTORY));
            for (Tray tray : BuiltInTrays.all()) {
                DiceScene.Camera camera = Framing.of(tray.geometry()).wholeTray(1).camera();
                for (LightRig rig : LightRig.values()) {
                    Still still = await(capture.take(renderer, () -> {
                        scene.setLook(DiceSetCatalogue.builtIn().fallback(), tray, rig);
                        scene.setDiceAndCamera(restingSet(), camera);
                    }));
                    Path file = rigDirectory.resolve(tray.identity() + "-" + rig.name().toLowerCase(Locale.ROOT)
                            + ".png");
                    png(still.toImage(), file);
                    written.add(file);
                }
            }
        }
        // The sheets are pure Java2D, no GL, and the same in every Dice Set.
        for (DieKind kind : DieKind.values()) {
            Path sheet = directory.resolve(kind.name().toLowerCase(Locale.ROOT) + "-numerals.png");
            png(NumeralAtlas.rasterise(NumeralLayout.of(kind)).sheet(SHEET_MAX_PX), sheet);
            written.add(sheet);
        }
        return List.copyOf(written);
    }

    /** Writes an image as a PNG. */
    private static void png(RenderedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("no PNG writer for " + file);
        }
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

    /**
     * The unit direction from the target toward the camera: from the near side (-z), as the tray prototype's camera
     * at zero yaw, which is where the Studio key light shines from.
     */
    private static Vector3dc towardCamera() {
        double pitch = Math.toRadians(PITCH_DEGREES);
        return new Vector3d(0, Math.sin(pitch), -Math.cos(pitch));
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

    /**
     * One Die of every Kind (the 7-die set) resting face up in a row across the Tray's middle, each on the floor:
     * its centre an inradius up, as its lowest face lies flat.
     */
    private static List<DiceScene.Die> restingSet() {
        List<DiceScene.Die> dice = new ArrayList<>();
        DieKind[] kinds = DieKind.values();
        for (int i = 0; i < kinds.length; i++) {
            DieKind kind = kinds[i];
            double x = (i - (kinds.length - 1) / 2.0) * REST_SPACING_MM;
            dice.add(new DiceScene.Die(kind, new Vector3d(x, kind.inradius(), 0), orientation(kind, Pose.FACE_UP)));
        }
        return dice;
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
