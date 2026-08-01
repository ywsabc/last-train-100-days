package dev.ywsabc.lasttrain.integration;

/** Pure decision rules used by the optional TaCZ-to-zombie bridge. */
final class GunfireAttractionPolicy {
    private GunfireAttractionPolicy() {
    }

    static boolean isWithinHearingDistance(double distanceSquared, double horizontalRadius) {
        return distanceSquared <= horizontalRadius * horizontalRadius;
    }

    static boolean shouldRetarget(
            boolean currentTargetUsable,
            double shooterDistanceSquared,
            double currentTargetDistanceSquared) {
        return !currentTargetUsable || shooterDistanceSquared <= currentTargetDistanceSquared;
    }
}
