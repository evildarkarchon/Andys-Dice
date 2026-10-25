package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code app} is built and tested as the JPMS module the spec's §5 table describes: named
 * {@code io.github.evildarkarchon.andysdice.app}, depending on all four modules below it, and denied native
 * access (JavaFX's natives are granted to {@code javafx.graphics}, not to us).
 */
class AppModuleTest {

    private static final Module MODULE = AppModuleTest.class.getModule();

    @Test
    void testsRunInsideTheNamedAppModule() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.app");
    }

    @Test
    void requiresEveryModuleBelowIt() {
        assertThat(MODULE.getDescriptor().requires().stream()
                        .map(ModuleDescriptor.Requires::name)
                        .filter(name -> name.startsWith("io.github.evildarkarchon.andysdice."))
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "io.github.evildarkarchon.andysdice.core",
                        "io.github.evildarkarchon.andysdice.physics",
                        "io.github.evildarkarchon.andysdice.render",
                        "io.github.evildarkarchon.andysdice.sound");
    }

    /** Under {@code --illegal-native-access=deny} a restricted call from an ungranted module fails. */
    @Test
    @SuppressWarnings("restricted") // the restricted call is the point of the test
    void isDeniedNativeAccess() {
        assertThat(MODULE.isNativeAccessEnabled()).isFalse();
        assertThatThrownBy(() -> MemorySegment.NULL.reinterpret(1)).isInstanceOf(IllegalCallerException.class);
    }
}
