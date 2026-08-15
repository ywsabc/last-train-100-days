package dev.ywsabc.lasttrain.campaign;

import java.util.Locale;

/** Persistent pacing mode for a campaign save. */
public enum CampaignMode {
    STORY_100_DAYS("story_100_days", false),
    ENDLESS("endless", true);

    private final String serializedName;
    private final boolean irreversible;

    CampaignMode(String serializedName, boolean irreversible) {
        this.serializedName = serializedName;
        this.irreversible = irreversible;
    }

    public String serializedName() {
        return serializedName;
    }

    /** Endless mode deliberately has no reverse transition. */
    public boolean isIrreversible() {
        return irreversible;
    }

    public static CampaignMode fromSerializedName(String value) {
        if (value == null || value.isBlank()) {
            return STORY_100_DAYS;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CampaignMode mode : values()) {
            if (mode.serializedName.equals(normalized)
                    || mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return mode;
            }
        }
        return STORY_100_DAYS;
    }
}
