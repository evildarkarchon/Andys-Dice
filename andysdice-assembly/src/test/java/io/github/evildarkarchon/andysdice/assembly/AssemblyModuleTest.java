package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.module.ModuleDescriptor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@code assembly} is built and tested as the JPMS module the spec's §5 table describes: named
 * {@code io.github.evildarkarchon.andysdice.assembly} and depending only on {@code app}, which brings the rest.
 */
class AssemblyModuleTest {

    private static final Module MODULE = AssemblyModuleTest.class.getModule();

    @Test
    void testsRunInsideTheNamedAssemblyModule() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.assembly");
    }

    @Test
    void requiresOnlyApp() {
        assertThat(MODULE.getDescriptor().requires().stream()
                        .map(ModuleDescriptor.Requires::name)
                        .filter(name -> name.startsWith("io.github.evildarkarchon.andysdice."))
                        .collect(Collectors.toSet()))
                .containsExactly("io.github.evildarkarchon.andysdice.app");
    }
}
