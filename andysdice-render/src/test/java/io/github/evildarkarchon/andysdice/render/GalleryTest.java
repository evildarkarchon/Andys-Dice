package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
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
 * The dev gallery on a real GL 3.3 core context (ADR-0005): one still of every Die Kind in every fixed pose, each
 * showing a Die with inked Numerals, and a sheet of every Die Kind's Numerals as its atlas lays them out. Whether
 * they look right is for an eye to judge; this checks that they are all there.
 *
 * <p>The gallery starts the JVM's one render thread itself, so it has a test class (and a JVM) of its own.
 */
@Tag("gl")
class GalleryTest {

    @Test
    void writesAStillOfEveryDieKindInEveryPoseAndASheetOfItsNumerals(@TempDir Path directory) throws Exception {
        List<Path> written = Gallery.write(directory);

        List<Path> expected = new ArrayList<>();
        List<Path> stills = new ArrayList<>();
        List<Path> faceUp = new ArrayList<>();
        List<Path> sheets = new ArrayList<>();
        for (DieKind kind : DieKind.values()) {
            String name = kind.name().toLowerCase(Locale.ROOT);
            for (Gallery.Pose pose : Gallery.Pose.values()) {
                Path still = directory.resolve(name + "-"
                        + pose.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png");
                expected.add(still);
                stills.add(still);
                if (pose == Gallery.Pose.FACE_UP) {
                    faceUp.add(still);
                }
            }
            Path sheet = directory.resolve(name + "-numerals.png");
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
        for (Path file : sheets) {
            BufferedImage image = ImageIO.read(file.toFile());
            assertThat(image.getWidth()).isEqualTo(image.getHeight()).isLessThanOrEqualTo(Gallery.SHEET_MAX_PX);
            assertThat(inkFraction(image)).as("%s shows Numerals", file).isGreaterThan(0.005);
        }
        try (var listing = Files.list(directory)) {
            assertThat(listing.count()).isEqualTo(written.size());
        }
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
