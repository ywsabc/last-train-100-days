package dev.ywsabc.lasttrain.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Development-environment gate: dev-only entry points refuse to run without
 * the explicit {@code -Dlasttrain.devTools=true} startup parameter, and the
 * gate follows the property directly.
 */
class DevToolsGateTest {
    @AfterEach
    void restoreProperty() {
        System.setProperty(DevToolsGate.SYSTEM_PROPERTY, "true");
    }

    @Test
    void gateFollowsTheExplicitStartupParameter() {
        System.clearProperty(DevToolsGate.SYSTEM_PROPERTY);
        assertFalse(DevToolsGate.enabled());
        assertThrows(IllegalStateException.class, () -> DevToolsGate.requireEnabled("entry"));

        System.setProperty(DevToolsGate.SYSTEM_PROPERTY, "true");
        assertTrue(DevToolsGate.enabled());
        DevToolsGate.requireEnabled("entry");
    }

    @Test
    void faultInjectionRegisterRefusesOutsideADevelopmentEnvironment() {
        System.clearProperty(DevToolsGate.SYSTEM_PROPERTY);
        assertThrows(
                IllegalStateException.class,
                () -> FaultInjection.register(FaultInjection.FailurePoint.REWARD_PERSIST, 1));
        assertThrows(
                IllegalStateException.class,
                () -> FaultInjection.registerAlways(FaultInjection.FailurePoint.REWARD_PERSIST));
        assertFalse(FaultInjection.anyRegistered());
    }

    @Test
    void shouldFailStaysCheapAndUngatedOutsideADevelopmentEnvironment() {
        // The production hot path reads the switch registry directly: it must
        // stay a no-op (never a gate exception) without dev tooling.
        System.clearProperty(DevToolsGate.SYSTEM_PROPERTY);
        assertFalse(FaultInjection.shouldFail(FaultInjection.FailurePoint.REWARD_PERSIST));
        assertEquals(0, FaultInjection.remaining(FaultInjection.FailurePoint.REWARD_PERSIST));
    }
}
