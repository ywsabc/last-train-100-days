package dev.ywsabc.lasttrain.mission;

import java.util.UUID;

/** Pure policy for activating and reconciling a zombie-blockade mission. */
final class ZombieBlockadePolicy {
    static final double ACTIVATION_DISTANCE = 96.0D;
    static final double RECONCILIATION_RADIUS = 48.0D;
    static final int MAX_MANAGED_ZOMBIES = 48;
    static final int MAX_SPAWNS_PER_TICK = 4;
    static final int MAX_SCAN_RESULTS_PER_TICK = 64;
    static final int SCAN_FRAMES = 4;
    private static final double ACTIVATION_DISTANCE_SQUARED =
            ACTIVATION_DISTANCE * ACTIVATION_DISTANCE;

    private ZombieBlockadePolicy() {
    }

    static boolean isWithinActivationDistance(double distanceSquared) {
        return distanceSquared <= ACTIVATION_DISTANCE_SQUARED;
    }

    static boolean shouldScan(UUID missionId, int directorCycle) {
        return Math.floorMod(missionId.hashCode(), SCAN_FRAMES)
                == Math.floorMod(directorCycle, SCAN_FRAMES);
    }

    static Reconciliation reconcile(int target, int progress, int livingTaggedZombies) {
        int normalizedTarget = Math.max(1, target);
        int normalizedProgress = Math.clamp(progress, 0, normalizedTarget);
        int normalizedLiving = Math.max(0, livingTaggedZombies);
        int uncappedDesired = normalizedTarget - normalizedProgress;
        int desiredLiving = Math.min(MAX_MANAGED_ZOMBIES, uncappedDesired);
        int missing = Math.max(0, desiredLiving - normalizedLiving);
        return new Reconciliation(
                desiredLiving,
                Math.min(MAX_SPAWNS_PER_TICK, missing),
                Math.max(0, normalizedLiving - desiredLiving),
                uncappedDesired > MAX_MANAGED_ZOMBIES
                        || normalizedLiving > MAX_MANAGED_ZOMBIES,
                missing > MAX_SPAWNS_PER_TICK);
    }

    /** Farthest first, then oldest, with UUID as a deterministic tie-break. */
    static int compareForEviction(
            double leftDistanceSquared,
            int leftAgeTicks,
            UUID leftId,
            double rightDistanceSquared,
            int rightAgeTicks,
            UUID rightId) {
        int distance = Double.compare(rightDistanceSquared, leftDistanceSquared);
        if (distance != 0) {
            return distance;
        }
        int age = Integer.compare(rightAgeTicks, leftAgeTicks);
        if (age != 0) {
            return age;
        }
        return leftId.compareTo(rightId);
    }

    record Reconciliation(
            int desiredLiving,
            int toSpawn,
            int toDiscard,
            boolean hardCapApplied,
            boolean spawnBudgetApplied) {
    }
}
