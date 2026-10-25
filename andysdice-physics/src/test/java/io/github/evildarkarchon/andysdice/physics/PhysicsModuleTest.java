package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.foreign.MemorySegment;
import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code physics} is built and tested as the JPMS module the spec's §5 table describes: named
 * {@code io.github.evildarkarchon.andysdice.physics}, depending only on {@code core}, and granted native
 * access by the build's shared option set, because it holds our native loader (ADR-0004).
 */
class PhysicsModuleTest {

    private static final Module MODULE = PhysicsModuleTest.class.getModule();

    @Test
    void testsRunInsideTheNamedPhysicsModule() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.physics");
    }

    @Test
    void requiresOnlyCore() {
        assertThat(MODULE.getDescriptor().requires().stream()
                        .map(ModuleDescriptor.Requires::name)
                        .filter(name -> name.startsWith("io.github.evildarkarchon.andysdice."))
                        .collect(Collectors.toSet()))
                .containsExactly("io.github.evildarkarchon.andysdice.core");
    }

    /** Under {@code --illegal-native-access=deny}, only a granted module may make a restricted call. */
    @Test
    @SuppressWarnings("restricted") // the restricted call is the point of the test
    void isGrantedNativeAccess() {
        assertThat(MODULE.isNativeAccessEnabled()).isTrue();
        assertThatCode(() -> MemorySegment.NULL.reinterpret(1)).doesNotThrowAnyException();
    }
}
