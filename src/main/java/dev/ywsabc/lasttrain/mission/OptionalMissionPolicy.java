package dev.ywsabc.lasttrain.mission;

import java.util.Objects;

/**
 * Pure deadline and slot policy for optional missions.
 *
 * <p>Optional mission deadlines are absolute, monotonically increasing tick
 * values captured from the campaign's total active tick counter at proposal
 * time. The alternative — comparing {@code currentDay - createdDay} — breaks
 * at the day-100 finale because the day field is clamped: a mission proposed
 * on day 99 could never age past one day. Tick deadlines keep advancing as
 * long as the campaign ticks, and the finale settlement path guarantees that
 * anything still pending on day 100 is settled there.</p>
 */
public final class OptionalMissionPolicy {
    /**
     * One campaign day in active ticks; the production day length. Optional
     * grace periods stay absolute-tick values even while the test-only
     * fast-forward mode shortens the in-memory campaign day.
     */
    public static final long TICKS_PER_DAY = 24_000L;
    public static final int RESCUE_GRACE_DAYS = 3;
    public static final int SALVAGE_GRACE_DAYS = 7;
    public static final int MAX_ACTIVE_OPTIONAL_MISSIONS = 3;

    private OptionalMissionPolicy() {
    }

    public static boolean isOptional(MissionType type) {
        Objects.requireNonNull(type, "type");
        return type.category() == MissionType.Category.OPTIONAL;
    }

    /** Timed grace period of an optional mission type, in campaign days. */
    public static int graceDays(MissionType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case RESCUE_SURVIVOR -> RESCUE_GRACE_DAYS;
            case SALVAGE_CAR -> SALVAGE_GRACE_DAYS;
            default -> throw new IllegalArgumentException(
                    type + " is not an optional mission type");
        };
    }

    /**
     * Absolute tick at which a mission proposed at {@code createdTick} times
     * out. Saturation keeps a corrupt or extreme counter from overflowing.
     */
    public static long deadlineTick(long createdTick, MissionType type) {
        long span = (long) graceDays(type) * TICKS_PER_DAY;
        long sum = createdTick + span;
        return sum < createdTick ? Long.MAX_VALUE : sum;
    }

    public static boolean shouldTimeout(long deadlineTick, long currentTick) {
        return currentTick >= deadlineTick;
    }
}
