package dev.ywsabc.lasttrain.server;

/** Pure readiness gate for the first automatic campaign start. */
public final class CampaignStartPolicy {
    private CampaignStartPolicy() {
    }

    public static boolean shouldAutoStart(
            boolean hasActivePlayer,
            boolean starterStationBuilt,
            boolean starterTrainAssembled,
            boolean starterTrainIdPresent,
            boolean starterTrainLocated) {
        return hasActivePlayer
                && starterStationBuilt
                && starterTrainAssembled
                && starterTrainIdPresent
                && starterTrainLocated;
    }
}
