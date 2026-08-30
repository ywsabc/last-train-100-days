package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZombieBlockadePolicyTest {
    @Test
    void activationIncludesTheNinetySixBlockBoundary() {
        assertTrue(ZombieBlockadePolicy.isWithinActivationDistance(96.0D * 96.0D));
        assertFalse(ZombieBlockadePolicy.isWithinActivationDistance(Math.nextUp(96.0D * 96.0D)));
    }

    @Test
    void initialWaveUsesThePerTickSpawnBudget() {
        ZombieBlockadePolicy.Reconciliation result =
                ZombieBlockadePolicy.reconcile(12, 0, 0);

        assertEquals(12, result.desiredLiving());
        assertEquals(ZombieBlockadePolicy.MAX_SPAWNS_PER_TICK, result.toSpawn());
        assertEquals(0, result.toDiscard());
        assertTrue(result.spawnBudgetApplied());
    }

    @Test
    void entitySaveAheadOfProgressRespawnsMissingZombies() {
        ZombieBlockadePolicy.Reconciliation result =
                ZombieBlockadePolicy.reconcile(12, 3, 7);

        assertEquals(9, result.desiredLiving());
        assertEquals(2, result.toSpawn());
        assertEquals(0, result.toDiscard());
    }

    @Test
    void progressSaveAheadOfEntityStateDiscardsOnlyTheSurplus() {
        ZombieBlockadePolicy.Reconciliation result =
                ZombieBlockadePolicy.reconcile(12, 5, 10);

        assertEquals(7, result.desiredLiving());
        assertEquals(0, result.toSpawn());
        assertEquals(3, result.toDiscard());
    }

    @Test
    void completedProgressRequestsNoLivingZombies() {
        ZombieBlockadePolicy.Reconciliation result =
                ZombieBlockadePolicy.reconcile(12, 99, 2);

        assertEquals(0, result.desiredLiving());
        assertEquals(0, result.toSpawn());
        assertEquals(2, result.toDiscard());
    }

    @Test
    void corruptHugeTargetNeverExceedsTheHardEntityCap() {
        ZombieBlockadePolicy.Reconciliation empty =
                ZombieBlockadePolicy.reconcile(4_096, 0, 0);
        ZombieBlockadePolicy.Reconciliation full =
                ZombieBlockadePolicy.reconcile(4_096, 0, 48);
        ZombieBlockadePolicy.Reconciliation overloaded =
                ZombieBlockadePolicy.reconcile(4_096, 0, 64);

        assertEquals(ZombieBlockadePolicy.MAX_MANAGED_ZOMBIES, empty.desiredLiving());
        assertEquals(ZombieBlockadePolicy.MAX_SPAWNS_PER_TICK, empty.toSpawn());
        assertTrue(empty.hardCapApplied());
        assertEquals(0, full.toSpawn());
        assertEquals(16, overloaded.toDiscard());
        assertTrue(overloaded.hardCapApplied());
    }

    @Test
    void repeatedReconciliationFillsGraduallyAndStopsAtTheCap() {
        int living = 0;
        for (int tick = 0; tick < 40; tick++) {
            ZombieBlockadePolicy.Reconciliation result =
                    ZombieBlockadePolicy.reconcile(4_096, 0, living);
            assertTrue(result.toSpawn() <= ZombieBlockadePolicy.MAX_SPAWNS_PER_TICK);
            living += result.toSpawn();
            living -= result.toDiscard();
            assertTrue(living <= ZombieBlockadePolicy.MAX_MANAGED_ZOMBIES);
        }
        assertEquals(ZombieBlockadePolicy.MAX_MANAGED_ZOMBIES, living);
    }

    @Test
    void evictionPrefersFarthestThenOldestEntity() {
        UUID nearest = new UUID(0L, 1L);
        UUID farYoung = new UUID(0L, 2L);
        UUID farOld = new UUID(0L, 3L);
        List<Candidate> candidates = new ArrayList<>(List.of(
                new Candidate(nearest, 4.0D, 900),
                new Candidate(farYoung, 100.0D, 20),
                new Candidate(farOld, 100.0D, 800)));

        candidates.sort((left, right) -> ZombieBlockadePolicy.compareForEviction(
                left.distanceSquared,
                left.ageTicks,
                left.id,
                right.distanceSquared,
                right.ageTicks,
                right.id));

        assertEquals(List.of(farOld, farYoung, nearest),
                candidates.stream().map(Candidate::id).toList());
    }

    @Test
    void missionScansAreSpreadAcrossFourDirectorFrames() {
        UUID missionId = UUID.randomUUID();
        int scans = 0;
        for (int cycle = 0; cycle < 12; cycle++) {
            if (ZombieBlockadePolicy.shouldScan(missionId, cycle)) {
                scans++;
            }
        }
        assertEquals(3, scans);
    }

    private record Candidate(UUID id, double distanceSquared, int ageTicks) {
    }
}
