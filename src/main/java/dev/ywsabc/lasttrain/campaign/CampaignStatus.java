package dev.ywsabc.lasttrain.campaign;

public enum CampaignStatus {
    NOT_STARTED,
    RUNNING,
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
