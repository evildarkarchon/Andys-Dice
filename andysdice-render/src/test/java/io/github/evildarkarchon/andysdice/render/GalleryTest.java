package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dev gallery on a real GL 3.3 core context (ADR-0005): one still of every Die Kind in every fixed pose in every
 * built-in Dice Set, each showing a Die with inked Numerals, and a sheet of every Die Kind's Numerals as its atlas
 * lays them out, and every built-in Tray under every Light Rig. Whether they look right is for an eye to judge; this
 * checks that they are all there.
 *
 * <p>The gallery starts the JVM's one render thread itself, so it has a test class (and a JVM) of its own.
 */
@Tag("gl")
class GalleryTest {

    @Test
    void writesAStillOfEveryDieKindInEveryPoseInEveryDiceSetAndASheetOfItsNumerals(@TempDir Path directory)
            throws Exception {
        List<Path> written = Gallery.write(directory);

        List<Path> expected = new ArrayList<>();
        List<Path> stills = new ArrayList<>();
        List<Path> faceUp = new ArrayList<>();
        List<Path> sheets = new ArrayList<>();
        // Ticket #42: every Die Kind in every built-in Dice Set, a directory per Dice Set, in catalogue order.
        for (DiceSet set : DiceSetCatalogue.builtIn().diceSets()) {
            for (DieKind kind : DieKind.values()) {
                String name = kind.name().toLowerCase(Locale.ROOT);
                for (Gallery.Pose pose : Gallery.Pose.values()) {
                    Path still = directory.resolve(set.identity()).resolve(name + "-"
                            + pose.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png");
                    expected.add(still);
                    stills.add(still);
                    // Ivory / black's dark ink on a light body is told apart by colour alone.
                    if (pose == Gallery.Pose.FACE_UP && set.identity().equals("02-ivory")) {
                        faceUp.add(still);
                    }
                }
            }
        }
        // Ticket #45 (ADR-0010): every built-in Tray under every Light Rig, with Dice resting in it.
        List<List<Path>> rigsByTray = new ArrayList<>();
        for (Tray tray : BuiltInTrays.all()) {
            List<Path> rigs = new ArrayList<>();
            for (LightRig rig : LightRig.values()) {
                Path still = directory.resolve("light-rigs")
                        .resolve(tray.identity() + "-" + rig.name().toLowerCase(Locale.ROOT) + ".png");
                expected.add(still);
                rigs.add(still);
            }
            rigsByTray.add(rigs);
        }
        for (DieKind kind : DieKind.values()) {
            Path sheet = directory.resolve(kind.name().toLowerCase(Locale.ROOT) + "-numerals.png");
            expected.add(sheet);
            sheets.add(sheet);
        }
        assertThat(written).containsExactlyElementsOf(expected);
        for (Path file : stills) {
            assertThat(file).exists();
            BufferedImage image = ImageIO.read(file.toFile());
            assertThat(image.getWidth()).isEqualTo(Gallery.SIZE_PX);
            assertThat(image.getHeight()).isEqualTo(Gallery.SIZE_PX);
            assertThat(image.getRGB(0, 0)).as("%s corner", file).isEqualTo(DiceScene.BACKGROUND_ARGB);
            assertThat(coveredFraction(image)).as("%s shows a Die filling the frame", file)
                    .isBetween(0.15, 0.9);
        }
        for (Path file : faceUp) {
            assertThat(inkFraction(ImageIO.read(file.toFile()))).as("%s shows inked Numerals", file)
                    .isGreaterThan(0.002);
        }
        for (List<Path> rigs : rigsByTray) {
            List<BufferedImage> images = new ArrayList<>();
            for (Path file : rigs) {
                BufferedImage image = ImageIO.read(file.toFile());
                assertThat(image.getWidth()).isEqualTo(Gallery.SIZE_PX);
                assertThat(image.getHeight()).isEqualTo(Gallery.SIZE_PX);
                assertThat(coveredFraction(image)).as("%s shows the Tray and the table filling the frame", file)
                        .isGreaterThan(0.9);
                images.add(image);
            }
            // The same Tray and Dice look different under each rig.
            for (int a = 0; a < images.size(); a++) {
                for (int b = a + 1; b < images.size(); b++) {
                    assertThat(differingFraction(images.get(a), images.get(b)))
                            .as("%s vs %s", rigs.get(a), rigs.get(b)).isGreaterThan(0.5);
                }
            }
        }
        for (Path file : sheets) {
            BufferedImage image = ImageIO.read(file.toFile());
            assertThat(image.getWidth()).isEqualTo(image.getHeight()).isLessThanOrEqualTo(Gallery.SHEET_MAX_PX);
            assertThat(inkFraction(image)).as("%s shows Numerals", file).isGreaterThan(0.005);
        }
        try (var listing = Files.walk(directory)) {
            assertThat(listing.filter(Files::isRegularFile).count()).isEqualTo(written.size());
        }
    }

    private static double differingFraction(BufferedImage a, BufferedImage b) {
        int differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing / (double) (a.getWidth() * a.getHeight());
    }

    private static double coveredFraction(BufferedImage image) {
        int covered = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != DiceScene.BACKGROUND_ARGB) {
                    covered++;
                }
            }
        }
        return covered / (double) (image.getWidth() * image.getHeight());
    }

    /** The share of dark grey pixels: ink, not the felt-green background or the shaded ivory body. */
    private static double inkFraction(BufferedImage image) {
        int ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                if (Math.max(r, Math.max(g, b)) < 70 && Math.abs(g - r) < 16 && Math.abs(g - b) < 16) {
                    ink++;
                }
            }
        }
        return ink / (double) (image.getWidth() * image.getHeight());
    }
}
