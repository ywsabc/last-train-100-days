package dev.ywsabc.lasttrain.testing;

import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, thread-safe failure-point switches for degradation tests.
 *
 * <p>Every switch defaults to off, and the production logical server never
 * registers one, so the shipped code runs the no-op path: with no active
 * injection {@link #shouldFail(FailurePoint)} is a single volatile reference
 * read with no allocation and no locking. Tests (and future operator tooling)
 * register a failure for exactly {@code failCount} occurrences or until
 * {@link #unregister(FailurePoint)}/always; each injected failure is consumed
 * by a CAS counter, so concurrent readers can never double-consume or skip
 * one occurrence. Mutations are synchronized and published through a
 * volatile map reference, which is enough for the logical-server-thread
 * model the campaign uses.</p>
 *
 * <p>Injected failures are expected to flow through the production
 * degradation paths — SAFE_MODE, mission fallback, reward outbox retries,
 * corrupt-entry drops — never through new error branches. Removing the
 * injection restores normal behavior immediately.</p>
 */
public final class FaultInjection {
    /**
     * The recoverable failure points the campaign code consults.
     */
    public enum FailurePoint {
        /** Mission world content generation fails; the director retries later. */
        MISSION_WORLD_PREPARE,
        /** A saved entry is treated as corrupt/missing and dropped on load. */
        SAVE_LOAD_CORRUPT_ENTRY,
        /** Mission settlement defers because the site chunk is still unloaded. */
        MISSION_SETTLE_CHUNK_UNLOADED,
        /** The durable reward transition crashes; the PENDING receipt hangs. */
        REWARD_PERSIST,
        /** Route segment materialization fails; the segment is retried. */
        ROUTE_SEGMENT_GENERATION,
        /** The vehicle stack is unavailable; the campaign enters SAFE_MODE. */
        VEHICLE_STACK_UNAVAILABLE
    }

    /** One registered switch: an atomic remaining-failure budget. */
    private static final class Injection {
        private final AtomicInteger remaining;

        private Injection(int failCount) {
            this.remaining = new AtomicInteger(failCount);
        }
    }

    private static final Object MUTEX = new Object();
    private static volatile EnumMap<FailurePoint, Injection> ACTIVE;

    private FaultInjection() {
    }

    /**
     * Arms {@code point} to fail for the next {@code failCount} occurrences.
     *
     * <p>Registering a point again replaces its remaining budget.</p>
     */
    public static void register(FailurePoint point, int failCount) {
        Objects.requireNonNull(point, "point");
        if (failCount < 1) {
            throw new IllegalArgumentException("failCount must be >= 1: " + failCount);
        }
        synchronized (MUTEX) {
            EnumMap<FailurePoint, Injection> next = ACTIVE == null
                    ? new EnumMap<>(FailurePoint.class)
                    : ACTIVE.clone();
            next.put(point, new Injection(failCount));
            ACTIVE = next;
        }
    }

    /** Arms {@code point} to fail until explicitly unregistered or cleared. */
    public static void registerAlways(FailurePoint point) {
        register(point, Integer.MAX_VALUE);
    }

    /** Disarms one point; other points are untouched. */
    public static void unregister(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        synchronized (MUTEX) {
            if (ACTIVE == null || !ACTIVE.containsKey(point)) {
                return;
            }
            EnumMap<FailurePoint, Injection> next = ACTIVE.clone();
            next.remove(point);
            ACTIVE = next.isEmpty() ? null : next;
        }
    }

    /** Disarms every point, restoring the production no-op default. */
    public static void clear() {
        synchronized (MUTEX) {
            ACTIVE = null;
        }
    }

    /** True while at least one failure point is armed. */
    public static boolean anyRegistered() {
        return ACTIVE != null;
    }

    /**
     * The unconsumed failure budget of {@code point}, or 0 when disarmed.
     * Informational: concurrent readers may change the value between the read
     * and its use.
     */
    public static int remaining(FailurePoint point) {
        Objects.requireNonNull(point, "point");
        EnumMap<FailurePoint, Injection> active = ACTIVE;
        Injection injection = active == null ? null : active.get(point);
        return injection == null ? 0 : injection.remaining.get();
    }

    /**
     * Consumes one failure of {@code point} when armed. Call sites use the
     * {@code true} branch to simulate the failure and fall into the existing
     * degradation path.
     */
    public static boolean shouldFail(FailurePoint point) {
        EnumMap<FailurePoint, Injection> active = ACTIVE;
        if (active == null) {
            return false;
        }
        Injection injection = active.get(point);
        return injection != null && consume(injection);
    }

    /** CAS budget consumption: every injected failure fires exactly once. */
    private static boolean consume(Injection injection) {
        while (true) {
            int current = injection.remaining.get();
            if (current <= 0) {
                return false;
            }
            if (injection.remaining.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
