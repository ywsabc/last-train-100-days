package dev.ywsabc.lasttrain.mission;

/**
 * Unified mission state machine.
 *
 * <p>Mainline missions use {@link #ACTIVE} → {@link #READY_TO_TURN_IN} →
 * {@link #COMPLETED}. Optional missions are created as {@link #PROPOSED},
 * become {@link #ACTIVE} on acceptance, and pass through
 * {@link #REWARD_PENDING} while the durable reward outbox executes the world
 * mutation. {@link #SKIPPED} records an explicitly rejected or abandoned
 * optional mission; {@link #FAILED} records a timeout.</p>
 */
public enum MissionStage {
    PROPOSED,
    ACTIVE,
    READY_TO_TURN_IN,
    REWARD_PENDING,
    COMPLETED,
    FAILED,
    SKIPPED;

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

    /** Stages after which no objective or settlement work may happen. */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED;
    }
}
