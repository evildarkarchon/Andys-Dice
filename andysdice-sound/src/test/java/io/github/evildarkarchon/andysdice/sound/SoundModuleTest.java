package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code sound} is built and tested as the JPMS module the spec's §5 table describes: named
 * {@code io.github.evildarkarchon.andysdice.sound}, depending only on {@code core}, and denied native access.
 */
class SoundModuleTest {

    private static final Module MODULE = SoundModuleTest.class.getModule();

    @Test
    void testsRunInsideTheNamedSoundModule() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.sound");
    }

    @Test
    void requiresOnlyCore() {
        assertThat(MODULE.getDescriptor().requires().stream()
                        .map(ModuleDescriptor.Requires::name)
                        .filter(name -> name.startsWith("io.github.evildarkarchon.andysdice."))
                        .collect(Collectors.toSet()))
                .containsExactly("io.github.evildarkarchon.andysdice.core");
    }

    /** Under {@code --illegal-native-access=deny} a restricted call from an ungranted module fails. */
    @Test
    @SuppressWarnings("restricted") // the restricted call is the point of the test
    void isDeniedNativeAccess() {
        assertThat(MODULE.isNativeAccessEnabled()).isFalse();
        assertThatThrownBy(() -> MemorySegment.NULL.reinterpret(1)).isInstanceOf(IllegalCallerException.class);
    }
}
