package dev.ywsabc.lasttrain.route;

/** Pure progression limits applied to the physical train's observed position. */
final class RouteProgressPolicy {
    private RouteProgressPolicy() {
    }

    static int nextSegment(
            int currentSegment,
            int generatedSegment,
            int observedTrainSegment,
            Integer activeMissionCheckpoint) {
        int next = Math.min(observedTrainSegment, generatedSegment);
        next = Math.min(next, currentSegment + 1);
        if (activeMissionCheckpoint != null) {
            next = Math.min(next, activeMissionCheckpoint);
        }
        return Math.max(currentSegment, next);
    }
}
