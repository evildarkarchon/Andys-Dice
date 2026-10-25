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
 * The dev gallery on a real GL 3.3 core context (ADR-0005): one still of every Die Kind in every fixed pose,
 * each showing a Die. Whether they look right is for an eye to judge; this checks that they are all there.
 *
 * <p>The gallery starts the JVM's one render thread itself, so it has a test class (and a JVM) of its own.
 */
@Tag("gl")
class GalleryTest {

    @Test
    void writesAStillOfEveryDieKindInEveryPose(@TempDir Path directory) throws Exception {
        List<Path> written = Gallery.write(directory);

        List<Path> expected = new ArrayList<>();
        for (DieKind kind : DieKind.values()) {
            for (Gallery.Pose pose : Gallery.Pose.values()) {
                expected.add(directory.resolve(kind.name().toLowerCase(Locale.ROOT) + "-"
                        + pose.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".png"));
            }
        }
        assertThat(written).containsExactlyElementsOf(expected);
        for (Path file : written) {
            assertThat(file).exists();
            BufferedImage image = ImageIO.read(file.toFile());
            assertThat(image.getWidth()).isEqualTo(Gallery.SIZE_PX);
            assertThat(image.getHeight()).isEqualTo(Gallery.SIZE_PX);
            assertThat(image.getRGB(0, 0)).as("%s corner", file).isEqualTo(DiceScene.BACKGROUND_ARGB);
            assertThat(coveredFraction(image)).as("%s shows a Die filling the frame", file)
                    .isBetween(0.15, 0.9);
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
}
