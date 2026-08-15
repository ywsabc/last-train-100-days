package dev.ywsabc.lasttrain.mission;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Mission definitions for the campaign director.
 *
 * <p>{@link Category#MAIN} missions occupy the single route-blocking mission
 * slot and act as progression checkpoints. {@link Category#OPTIONAL} missions
 * run beside the main line, are offered as proposals first and never block
 * route progress.</p>
 */
public enum MissionType {
    RAIL_BREAK("rail_break", 3, Category.MAIN),
    STATION_POWER("station_power", 4, Category.MAIN),
    STATION_GATE("station_gate", 2, Category.MAIN),
    SUPPLY_RECOVERY("supply_recovery", 5, Category.MAIN),
    ZOMBIE_BLOCKADE("zombie_blockade", 12, Category.MAIN),
    RESCUE_SURVIVOR("rescue_survivor", 1, Category.OPTIONAL),
    SALVAGE_CAR("salvage_car", 6, Category.OPTIONAL);

    private final String serializedName;
    private final int defaultTarget;
    private final Category category;

    MissionType(String serializedName, int defaultTarget, Category category) {
        this.serializedName = serializedName;
        this.defaultTarget = defaultTarget;
        this.category = category;
    }

    public String serializedName() {
        return serializedName;
    }

    public int defaultTarget() {
        return defaultTarget;
    }

    public Category category() {
        return category;
    }

    /**
     * Only mainline missions hold the route checkpoint. Optional missions and
     * unaccepted proposals must never stop the train from progressing.
     */
    public boolean blocksRoute() {
        return category == Category.MAIN;
    }

    /** Missions whose own reward loop guarantees a material safety net. */
    public boolean guaranteedSupplies() {
        return this == SUPPLY_RECOVERY;
    }

    public static Optional<MissionType> parse(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.serializedName.equals(normalized))
                .findFirst();
    }

    public enum Category {
        MAIN,
        OPTIONAL
    }
}
