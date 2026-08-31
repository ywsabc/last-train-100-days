package dev.ywsabc.lasttrain.mission;

import java.util.UUID;

/** Pure policy for activating and reconciling a zombie-blockade mission. */
final class ZombieBlockadePolicy {
    static final double ACTIVATION_DISTANCE = 96.0D;
    /** 活动任务实体越过该半径后按原 UUID 传送回收，不创建替代实体。 */
    static final double MANAGEMENT_RADIUS = 96.0D;
    static final int MAX_MANAGED_ZOMBIES = MissionEntityContainer.MAX_REGISTERED_ENTITIES;
    static final int MAX_SPAWNS_PER_TICK = 4;
    /** 每轮最多淘汰 64 个旧实体，兼顾收敛速度与单 tick 预算。 */
    static final int MAX_DISCARDS_PER_TICK = 64;
    static final int MAX_SCAN_RESULTS_PER_TICK =
            MAX_MANAGED_ZOMBIES + MAX_DISCARDS_PER_TICK;
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
        int surplus = Math.max(0, normalizedLiving - desiredLiving);
        return new Reconciliation(
                desiredLiving,
                Math.min(MAX_SPAWNS_PER_TICK, missing),
                Math.min(MAX_DISCARDS_PER_TICK, surplus),
                uncappedDesired > MAX_MANAGED_ZOMBIES
                        || normalizedLiving > MAX_MANAGED_ZOMBIES,
                missing > MAX_SPAWNS_PER_TICK,
                surplus > MAX_DISCARDS_PER_TICK);
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
            boolean spawnBudgetApplied,
            boolean discardBudgetApplied) {
    }
}
