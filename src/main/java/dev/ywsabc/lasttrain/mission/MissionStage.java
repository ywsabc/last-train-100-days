package dev.ywsabc.lasttrain.mission;

public enum MissionStage {
    ACTIVE,
    READY_TO_TURN_IN,
    COMPLETED,
    FAILED;

    public static MissionStage fromSerializedName(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }

        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ACTIVE;
        }
    }
}
