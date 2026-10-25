package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Checks that Failsafe's JVMs, like Surefire's, run under the build's shared native-access option set: the
 * tests run inside the named {@code assembly} module, which the set doesn't grant, so
 * {@code --illegal-native-access=deny} makes a restricted call fail. The smoke tests of the built app-image
 * (ADR-0005) will run in this same Failsafe execution.
 */
class FailsafeNativeAccessIT {

    private static final Module MODULE = FailsafeNativeAccessIT.class.getModule();

    @Test
    @SuppressWarnings("restricted") // the restricted call is the point of the test
    void failsafeJvmIsUnderTheDenyingNativeAccessSet() {
        assertThat(MODULE.getName()).isEqualTo("io.github.evildarkarchon.andysdice.assembly");
        assertThat(MODULE.isNativeAccessEnabled()).isFalse();
        assertThatThrownBy(() -> MemorySegment.NULL.reinterpret(1)).isInstanceOf(IllegalCallerException.class);
    }
}
