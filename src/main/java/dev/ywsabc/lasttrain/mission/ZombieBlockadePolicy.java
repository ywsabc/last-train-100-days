package dev.ywsabc.lasttrain.mission;

/** Pure policy for activating and reconciling a zombie-blockade mission. */
final class ZombieBlockadePolicy {
    static final double ACTIVATION_DISTANCE = 96.0D;
    private static final double ACTIVATION_DISTANCE_SQUARED =
            ACTIVATION_DISTANCE * ACTIVATION_DISTANCE;

    private ZombieBlockadePolicy() {
    }

    static boolean isWithinActivationDistance(double distanceSquared) {
        return distanceSquared <= ACTIVATION_DISTANCE_SQUARED;
    }

    static Reconciliation reconcile(int target, int progress, int livingTaggedZombies) {
        int normalizedTarget = Math.max(1, target);
        int normalizedProgress = Math.clamp(progress, 0, normalizedTarget);
        int normalizedLiving = Math.max(0, livingTaggedZombies);
        int desiredLiving = normalizedTarget - normalizedProgress;
        return new Reconciliation(
                desiredLiving,
                Math.max(0, desiredLiving - normalizedLiving),
                Math.max(0, normalizedLiving - desiredLiving));
    }

    record Reconciliation(int desiredLiving, int toSpawn, int toDiscard) {
    }
}
