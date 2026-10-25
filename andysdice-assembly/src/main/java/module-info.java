/**
 * The packaging module: the {@code jpackage} app-image and zip, {@code app\legal\}, and the Failsafe smoke
 * tests that run the built launcher (spec §5, ADR-0004, ADR-0005). It depends on {@code app}, which brings the
 * rest of the reactor.
 */
module io.github.evildarkarchon.andysdice.assembly {
    requires io.github.evildarkarchon.andysdice.app;
}
