package dev.ywsabc.lasttrain.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Registry semantics of the deterministic failure-point switches.
 *
 * <p>The default is a no-op for every point, every registered failure fires
 * exactly once (or always, for {@code registerAlways}), other points stay
 * untouched, and concurrent readers can never double-consume one injected
 * failure.</p>
 */
class FaultInjectionTest {
    @AfterEach
    void clearInjections() {
        FaultInjection.clear();
    }

    @Test
    void productionDefaultIsANoOpForEveryFailurePoint() {
        assertFalse(FaultInjection.anyRegistered());
        for (FaultInjection.FailurePoint point : FaultInjection.FailurePoint.values()) {
            assertFalse(FaultInjection.shouldFail(point));
            assertEquals(0, FaultInjection.remaining(point));
        }
    }

    @Test
    void registerConsumesExactlyTheRequestedFailCount() {
        FaultInjection.FailurePoint point = FaultInjection.FailurePoint.MISSION_WORLD_PREPARE;
        FaultInjection.register(point, 3);

        assertTrue(FaultInjection.shouldFail(point));
        assertTrue(FaultInjection.shouldFail(point));
        assertTrue(FaultInjection.shouldFail(point));
        assertFalse(FaultInjection.shouldFail(point));
        assertEquals(0, FaultInjection.remaining(point));
    }

    @Test
    void registerAlwaysFailsUntilExplicitlyUnregistered() {
        FaultInjection.FailurePoint point = FaultInjection.FailurePoint.REWARD_PERSIST;
        FaultInjection.registerAlways(point);

        for (int attempt = 0; attempt < 100; attempt++) {
            assertTrue(FaultInjection.shouldFail(point));
        }
        FaultInjection.unregister(point);
        assertFalse(FaultInjection.shouldFail(point));
        assertFalse(FaultInjection.anyRegistered());
    }

    @Test
    void reRegisteringReplacesTheRemainingCount() {
        FaultInjection.FailurePoint point = FaultInjection.FailurePoint.ROUTE_SEGMENT_GENERATION;
        FaultInjection.register(point, 2);
        assertTrue(FaultInjection.shouldFail(point));

        FaultInjection.register(point, 5);
        int hits = 0;
        while (FaultInjection.shouldFail(point)) {
            hits++;
        }
        assertEquals(5, hits);
    }

    @Test
    void registeringOnePointLeavesTheOthersOff() {
        FaultInjection.register(FaultInjection.FailurePoint.REWARD_PERSIST, 1);
        for (FaultInjection.FailurePoint point : FaultInjection.FailurePoint.values()) {
            if (point != FaultInjection.FailurePoint.REWARD_PERSIST) {
                assertFalse(FaultInjection.shouldFail(point));
            }
        }
        assertTrue(FaultInjection.shouldFail(FaultInjection.FailurePoint.REWARD_PERSIST));
    }

    @Test
    void clearResetsEveryRegisteredPoint() {
        FaultInjection.register(FaultInjection.FailurePoint.REWARD_PERSIST, 1);
        FaultInjection.register(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY, 1);
        assertTrue(FaultInjection.anyRegistered());

        FaultInjection.clear();
        assertFalse(FaultInjection.anyRegistered());
        assertFalse(FaultInjection.shouldFail(FaultInjection.FailurePoint.REWARD_PERSIST));
        assertFalse(FaultInjection.shouldFail(FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY));
    }

    @Test
    void registerRejectsNullPointsAndNonPositiveCounts() {
        assertThrows(
                NullPointerException.class,
                () -> FaultInjection.register(null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultInjection.register(
                        FaultInjection.FailurePoint.REWARD_PERSIST, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> FaultInjection.register(
                        FaultInjection.FailurePoint.REWARD_PERSIST, -1));
    }

    @Test
    void concurrentConsumptionDeliversExactlyTheRegisteredFailures() throws Exception {
        FaultInjection.FailurePoint point = FaultInjection.FailurePoint.SAVE_LOAD_CORRUPT_ENTRY;
        FaultInjection.register(point, 1_000);

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int hits = 0;
                    for (int attempt = 0; attempt < 1_000; attempt++) {
                        if (FaultInjection.shouldFail(point)) {
                            hits++;
                        }
                    }
                    return hits;
                }));
            }
            start.countDown();

            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1_000, total);
            assertEquals(0, FaultInjection.remaining(point));
        } finally {
            pool.shutdownNow();
        }
    }
}
