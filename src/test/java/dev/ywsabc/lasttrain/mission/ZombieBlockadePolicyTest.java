package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZombieBlockadePolicyTest {
    @Test
    void activationIncludesTheNinetySixBlockBoundary() {
        assertTrue(ZombieBlockadePolicy.isWithinActivationDistance(96.0D * 96.0D));
        assertFalse(ZombieBlockadePolicy.isWithinActivationDistance(Math.nextUp(96.0D * 96.0D)));
    }

    @Test
    void initialWaveFillsTheWholeRemainingTarget() {
        ZombieBlockadePolicy.Reconciliation result =
                ZombieBlockadePolicy.reconcile(12, 0, 0);

        assertEquals(12, result.desiredLiving());
        assertEquals(12, result.toSpawn());
        assertEquals(0, result.toDiscard());
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
}
