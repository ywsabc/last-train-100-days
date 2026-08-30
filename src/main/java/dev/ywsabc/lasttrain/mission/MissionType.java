package dev.ywsabc.lasttrain.mission;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Mission definitions for the campaign director.
 *
 * <p>{@link Category#MAIN} missions occupy the single mission slot, act as
 * progression checkpoints and block the route. {@link Category#SUPPORT}
 * missions occupy the same single slot but never block route progress (the
 * fuel/supply guarantee). {@link Category#OPTIONAL} missions run beside the
 * main line, are offered as proposals first and never occupy the slot.</p>
 */
public enum MissionType {
    RAIL_BREAK("rail_break", 3, Category.MAIN),
    STATION_POWER("station_power", 4, Category.MAIN),
    STATION_GATE("station_gate", 2, Category.MAIN),
    /** 隧道坍塌或废车形成的真实净空障碍，可用工具或爆炸清除。 */
    TRACK_CLEARANCE("track_clearance", 5, Category.MAIN),
    SUPPLY_RECOVERY("supply_recovery", 5, Category.SUPPORT),
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
     * The single authoritative mainline criterion: only MAIN missions hold
     * the route checkpoint. SUPPORT, OPTIONAL and unaccepted proposal
     * missions must never stop the train from progressing.
     */
    public boolean blocksRoute() {
        return category == Category.MAIN;
    }

    /** True for every mission that occupies the single mainline mission slot. */
    public boolean occupiesMainlineSlot() {
        return category != Category.OPTIONAL;
    }

    /** Missions whose own reward loop guarantees a material safety net. */
    public boolean guaranteedSupplies() {
        return this == SUPPLY_RECOVERY;
    }

    public static Optional<MissionType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.serializedName.equals(normalized))
                .findFirst();
    }

    public enum Category {
        /** Route-blocking progression checkpoint. */
        MAIN,
        /** Occupies the mainline slot but never blocks the route. */
        SUPPORT,
        /** Side mission offered as a proposal; never occupies the mainline slot. */
        OPTIONAL
    }
}
