package dev.ywsabc.lasttrain.mission;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum MissionType {
    RAIL_BREAK("rail_break", 3),
    STATION_POWER("station_power", 4),
    STATION_GATE("station_gate", 2),
    SUPPLY_RECOVERY("supply_recovery", 5),
    ZOMBIE_BLOCKADE("zombie_blockade", 12);

    private final String serializedName;
    private final int defaultTarget;

    MissionType(String serializedName, int defaultTarget) {
        this.serializedName = serializedName;
        this.defaultTarget = defaultTarget;
    }

    public String serializedName() {
        return serializedName;
    }

    public int defaultTarget() {
        return defaultTarget;
    }

    public static Optional<MissionType> parse(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.serializedName.equals(normalized))
                .findFirst();
    }
}
