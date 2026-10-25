package io.github.evildarkarchon.andysdice.core.diceset;

import java.util.Objects;

/**
 * A Dice Set: the whole look of the Dice in a Roll, as the parameters of one uber-shader (ADR-0009). A solid resin
 * is simply a Dice Set whose swirl, flakes, pearl and transmission are all zero. Read from a {@code .diceset} file
 * by {@link DiceSetFile}, which also holds each key's default and range.
 *
 * @param identity the file's name without {@code .diceset}, such as {@code 01-onyx}: what the settings store and
 *     the catalogue finds it by
 * @param name the name the Player sees, such as {@code Onyx / white}
 * @param base the body's colour; for a metal, its reflectance (F0)
 * @param roughness the body's roughness, 0.02..1
 * @param metallic how metallic the body is, 0..1
 * @param swirl a second resin folded through the body
 * @param flakes speckle or glitter in the body
 * @param pearl the strength of a colour-shifting sheen where the swirl is, 0..2
 * @param transmission how translucent the body is (ticket #43 draws it; until then every Dice Set is drawn opaque)
 * @param ink the paint in the engraved Numerals
 */
public record DiceSet(String identity, String name, SrgbColour base, double roughness, double metallic,
        Swirl swirl, Flakes flakes, double pearl, Transmission transmission, Ink ink) {

    /**
     * A second resin folded through the body: a periodic, domain-warped noise field in model space (ADR-0009).
     *
     * @param colour the second resin's colour
     * @param scale the size of the noise cells, in mm, 0.5..64
     * @param warp how strongly the field is folded, 0..4
     * @param bands above 0, how many ribbons the field is run through (0 = soft clouds), 0..8
     * @param amount how much of the body is the second resin, 0..1 (0 = no swirl)
     * @param softness how soft the boundary between the two resins is, 0.005..0.5
     */
    public record Swirl(SrgbColour colour, double scale, double warp, double bands, double amount, double softness) {

        /** @throws NullPointerException if the colour is {@code null} */
        public Swirl {
            Objects.requireNonNull(colour, "colour");
        }
    }

    /**
     * Flakes in the body: one candidate per cell of {@link #size} mm, kept with probability {@link #density}.
     *
     * @param colour the flakes' colour
     * @param density the share of cells holding a flake, 0..1 (0 = none)
     * @param size the cell size, in mm, 0.05..3
     * @param metallic 0 = matt speckle, 1 = glitter that catches the light
     */
    public record Flakes(SrgbColour colour, double density, double size, double metallic) {

        /** @throws NullPointerException if the colour is {@code null} */
        public Flakes {
            Objects.requireNonNull(colour, "colour");
        }
    }

    /**
     * Light through the body (ADR-0009's analytic refraction, ticket #43).
     *
     * @param amount 0 = opaque, 1 = clear, 0..1
     * @param colour the colour of white light after 10 mm inside the body
     * @param ior the index of refraction, 1..2.5
     * @param cloudiness how much light the body scatters, 0..1
     */
    public record Transmission(double amount, SrgbColour colour, double ior, double cloudiness) {

        /** @throws NullPointerException if the colour is {@code null} */
        public Transmission {
            Objects.requireNonNull(colour, "colour");
        }
    }

    /**
     * The opaque paint in the engraved Numerals, over any body (ADR-0008, ADR-0009).
     *
     * @param colour the paint's colour
     * @param metallic how metallic it is, 0..1 (gold paint is 1)
     * @param roughness its roughness, 0.02..1
     */
    public record Ink(SrgbColour colour, double metallic, double roughness) {

        /** @throws NullPointerException if the colour is {@code null} */
        public Ink {
            Objects.requireNonNull(colour, "colour");
        }
    }

    /**
     * Checks that every part is there.
     *
     * @throws NullPointerException if a part is {@code null}
     * @throws IllegalArgumentException if the identity is blank
     */
    public DiceSet {
        Objects.requireNonNull(identity, "identity");
        if (identity.isBlank()) {
            throw new IllegalArgumentException("a Dice Set's identity is not blank");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(swirl, "swirl");
        Objects.requireNonNull(flakes, "flakes");
        Objects.requireNonNull(transmission, "transmission");
        Objects.requireNonNull(ink, "ink");
    }

    /** @return whether the body has a swirl, which the renderer bakes into a 3D texture */
    public boolean hasSwirl() {
        return swirl.amount() > 0;
    }

    /** @return whether the body lets light through ({@code transmission > 0}) */
    public boolean translucent() {
        return transmission.amount() > 0;
    }
}
