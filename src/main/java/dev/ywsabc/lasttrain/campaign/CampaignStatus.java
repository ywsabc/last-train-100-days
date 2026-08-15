package dev.ywsabc.lasttrain.campaign;

public enum CampaignStatus {
    NOT_STARTED,
    RUNNING,
    /**
     * The vehicle stack is missing or the train cannot be verified. The
     * campaign clock and world side effects pause until the backend is
     * available again.
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
