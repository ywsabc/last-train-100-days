package dev.ywsabc.lasttrain.campaign;

public enum CampaignStatus {
    NOT_STARTED,
    RUNNING,
    /**
     * One or more persisted {@link SafeModeReason}s currently prevent safe
     * world mutation. The campaign clock and world side effects pause until
     * every owning condition is explicitly resolved.
     */
    SAFE_MODE,
    COMPLETED,
    FAILED;

    public static CampaignStatus fromSerializedName(String value) {
        if (value == null || value.isBlank()) {
            return NOT_STARTED;
        }

        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return NOT_STARTED;
        }
    }
}
