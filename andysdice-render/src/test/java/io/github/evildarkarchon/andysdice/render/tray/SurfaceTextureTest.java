package io.github.evildarkarchon.andysdice.render.tray;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.tray.FloorSurface;
import io.github.evildarkarchon.andysdice.core.tray.WallMaterial;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Tray's procedural surfaces (ADR-0010): felt fibres, pebbled leather and walnut grain generated in code as
 * seamlessly tiling textures, each a shade that the Tray file's colour tints and a height for the bump.
 */
class SurfaceTextureTest {

    private static final Map<SurfaceTexture.Kind, SurfaceTexture> TEXTURES = new EnumMap<>(SurfaceTexture.Kind.class);

    @BeforeAll
    static void generate() {
        for (SurfaceTexture.Kind kind : SurfaceTexture.Kind.values()) {
            TEXTURES.put(kind, SurfaceTexture.generate(kind));
        }
    }

    @Test
    void eachFloorSurfaceAndWallMaterialHasItsTexture() {
        assertThat(SurfaceTexture.Kind.of(FloorSurface.FELT)).isEqualTo(SurfaceTexture.Kind.FELT);
        assertThat(SurfaceTexture.Kind.of(FloorSurface.LEATHER)).isEqualTo(SurfaceTexture.Kind.LEATHER);
        assertThat(SurfaceTexture.Kind.of(FloorSurface.WOOD)).isEqualTo(SurfaceTexture.Kind.WOOD);
        assertThat(SurfaceTexture.Kind.of(WallMaterial.WOOD)).isEqualTo(SurfaceTexture.Kind.WOOD);
        assertThat(SurfaceTexture.Kind.of(WallMaterial.LEATHER)).isEqualTo(SurfaceTexture.Kind.LEATHER);
    }

    @ParameterizedTest
    @EnumSource(SurfaceTexture.Kind.class)
    void theTextureTilesWithoutASeam(SurfaceTexture.Kind kind) {
        SurfaceTexture texture = TEXTURES.get(kind);

        for (IntBinaryOperator channel : channels(texture)) {
            // Across the wrap, neighbouring texels differ no more than neighbours inside the tile do.
            assertThat(wrapStep(texture.sizePx(), channel, true))
                    .isLessThanOrEqualTo(1.5 * innerStep(texture.sizePx(), channel, true) + 1);
            assertThat(wrapStep(texture.sizePx(), channel, false))
                    .isLessThanOrEqualTo(1.5 * innerStep(texture.sizePx(), channel, false) + 1);
        }
    }

    @ParameterizedTest
    @EnumSource(SurfaceTexture.Kind.class)
    void theTextureHasDetailInBothChannels(SurfaceTexture.Kind kind) {
        SurfaceTexture texture = TEXTURES.get(kind);

        for (IntBinaryOperator channel : channels(texture)) {
            assertThat(spread(texture.sizePx(), channel)).isGreaterThan(5);
        }
    }

    @ParameterizedTest
    @EnumSource(SurfaceTexture.Kind.class)
    void onAverageTheShadeLeavesTheTraysColourAsItIs(SurfaceTexture.Kind kind) {
        SurfaceTexture texture = TEXTURES.get(kind);
        int n = texture.sizePx();
        double sum = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                sum += texture.shade(x, y);
            }
        }
        assertThat(sum / n / n).isBetween(0.8, 1.15);
    }

    @Test
    void theSameKindAlwaysComesOutTheSameAndKindsDiffer() {
        assertThat(bytes(SurfaceTexture.generate(SurfaceTexture.Kind.LEATHER)))
                .isEqualTo(bytes(TEXTURES.get(SurfaceTexture.Kind.LEATHER)));
        assertThat(bytes(TEXTURES.get(SurfaceTexture.Kind.FELT))).isNotEqualTo(bytes(TEXTURES.get(SurfaceTexture.Kind.WOOD)));
    }

    @Test
    void theUploadIsTwoBytesPerTexelShadeThenHeight() {
        SurfaceTexture texture = TEXTURES.get(SurfaceTexture.Kind.WOOD);
        byte[] bytes = bytes(texture);

        assertThat(bytes).hasSize(2 * texture.sizePx() * texture.sizePx());
        int x = 17;
        int y = 300;
        int at = 2 * (y * texture.sizePx() + x);
        assertThat(Byte.toUnsignedInt(bytes[at]))
                .isEqualTo(Math.round(texture.shade(x, y) / SurfaceTexture.SHADE_RANGE * 255));
        assertThat(Byte.toUnsignedInt(bytes[at + 1])).isEqualTo(Math.round(texture.height(x, y) * 255));
    }

    /** The shade and the height, each as 0..255. */
    private static IntBinaryOperator[] channels(SurfaceTexture texture) {
        return new IntBinaryOperator[] {
            (x, y) -> Math.round(texture.shade(x, y) / SurfaceTexture.SHADE_RANGE * 255),
            (x, y) -> Math.round(texture.height(x, y) * 255),
        };
    }

    private static double wrapStep(int n, IntBinaryOperator f, boolean alongX) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += alongX ? Math.abs(f.applyAsInt(n - 1, i) - f.applyAsInt(0, i))
                    : Math.abs(f.applyAsInt(i, n - 1) - f.applyAsInt(i, 0));
        }
        return sum / n;
    }

    private static double innerStep(int n, IntBinaryOperator f, boolean alongX) {
        double sum = 0;
        int count = 0;
        for (int line = 0; line < n; line += 7) {
            for (int i = 0; i < n - 1; i++) {
                sum += alongX ? Math.abs(f.applyAsInt(i + 1, line) - f.applyAsInt(i, line))
                        : Math.abs(f.applyAsInt(line, i + 1) - f.applyAsInt(line, i));
                count++;
            }
        }
        return sum / count;
    }

    /** The standard deviation of a 0..255 channel. */
    private static double spread(int n, IntBinaryOperator f) {
        double sum = 0;
        double squares = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = f.applyAsInt(x, y);
                sum += v;
                squares += (double) v * v;
            }
        }
        double mean = sum / n / n;
        return Math.sqrt(squares / n / n - mean * mean);
    }

    private static byte[] bytes(SurfaceTexture texture) {
        ByteBuffer buffer = ByteBuffer.allocate(2 * texture.sizePx() * texture.sizePx());
        texture.put(buffer).flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }
}
