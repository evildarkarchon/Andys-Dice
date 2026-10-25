package io.github.evildarkarchon.andysdice.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code core} is built and tested as the JPMS module the spec's §5 table describes: named
 * {@code io.github.evildarkarchon.andysdice.core}, depending on none of our modules, and denied native access
 * by the build's shared native-access option set.
 */
class CoreModuleTest {

    private static final Module MODULE = CoreModuleTest.class.getModule();

    @Test
    void testsRunInsideTheNamedCoreModule() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.core");
    }

    @Test
    void requiresNoneOfOurModules() {
        assertThat(MODULE.getDescriptor().requires().stream()
                        .map(ModuleDescriptor.Requires::name)
                        .filter(name -> name.startsWith("io.github.evildarkarchon.andysdice."))
                        .collect(Collectors.toSet()))
                .isEmpty();
    }

    /**
     * {@code --illegal-native-access=deny} turns a restricted call from an ungranted module into an
     * {@link IllegalCallerException}; under the JDK's default it would only print a warning and succeed.
     */
    @Test
    @SuppressWarnings("restricted") // the restricted call is the point of the test
    void isDeniedNativeAccess() {
        assertThat(MODULE.isNativeAccessEnabled()).isFalse();
        assertThatThrownBy(() -> MemorySegment.NULL.reinterpret(1)).isInstanceOf(IllegalCallerException.class);
    }
}
