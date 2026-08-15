package dev.ywsabc.lasttrain.testing;

/**
 * Environment gate for development-only entry points.
 *
 * <p>{@code FastForwardMode.enable} and {@code FaultInjection.register}
 * mutate live campaign behavior and must never fire on a production server
 * by accident. They refuse to run unless the JVM was started with the
 * explicit startup parameter {@code -Dlasttrain.devTools=true}. Production
 * servers never pass that parameter, and unit tests receive it from the
 * Gradle test task configuration, so the gate is a single volatile-free
 * system-property read with negligible fixed cost.</p>
 */
public final class DevToolsGate {
    /** JVM system property that explicitly opts a launch into dev tooling. */
    public static final String SYSTEM_PROPERTY = "lasttrain.devTools";

    private DevToolsGate() {
    }

    /** True when the launch explicitly opted into development tooling. */
    public static boolean enabled() {
        return Boolean.getBoolean(SYSTEM_PROPERTY);
    }

    /**
     * Rejects the named development-only entry point outside a development
     * environment: without the explicit startup parameter the call throws and
     * never touches campaign state.
     */
    public static void requireEnabled(String entryPoint) {
        if (!enabled()) {
            throw new IllegalStateException(
                    entryPoint
                            + " is a development-only entry point and requires the explicit "
                            + "JVM startup parameter -D"
                            + SYSTEM_PROPERTY
                            + "=true");
        }
    }
}
