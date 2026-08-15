package dev.ywsabc.lasttrain.route;

/**
 * Pure progression limits applied to the physical train's observed position.
 *
 * <p>The expected mileage line deliberately lives beside the route progress
 * rules. It is a logical-server policy input, not a measurement of physical
 * blocks or client position, so it remains deterministic across restarts.</p>
 */
public final class RouteProgressPolicy {
    public static final int FIRST_CAMPAIGN_DAY = 1;
    public static final int FINAL_CAMPAIGN_DAY = 100;
    public static final int DEFAULT_SEGMENTS_PER_DAY = 1;
    public static final int CLEARLY_AHEAD_MARGIN = 2;
    public static final MileageLine DEFAULT_MILEAGE_LINE =
            new MileageLine(FIRST_CAMPAIGN_DAY, 0, DEFAULT_SEGMENTS_PER_DAY, 1);

    private RouteProgressPolicy() {
    }

    /**
     * Returns the default linear expected route position for a campaign day.
     * Day one starts at the route head, and the default line advances one
     * logical segment per day.
     */
    public static int expectedRouteSegment(int day) {
        return expectedRouteSegment(day, DEFAULT_MILEAGE_LINE);
    }

    /** Returns an integer-slope line anchored at day one and segment zero. */
    public static int expectedRouteSegment(int day, int segmentsPerDay) {
        return expectedRouteSegment(
                day,
                new MileageLine(FIRST_CAMPAIGN_DAY, 0, segmentsPerDay, 1));
    }

    /** Returns the expected segment on a caller-provided deterministic line. */
    public static int expectedRouteSegment(int day, MileageLine line) {
        int boundedDay = Math.clamp(day, FIRST_CAMPAIGN_DAY, FINAL_CAMPAIGN_DAY);
        return line.expectedRouteSegment(boundedDay);
    }

    /** Classifies actual progress against the default expected mileage line. */
    public static PaceAssessment assess(int day, int actualRouteSegment) {
        return assess(day, actualRouteSegment, DEFAULT_MILEAGE_LINE);
    }

    /** Classifies actual progress against a configured expected mileage line. */
    public static PaceAssessment assess(
            int day,
            int actualRouteSegment,
            MileageLine line) {
        int expected = expectedRouteSegment(day, line);
        int actual = Math.max(0, actualRouteSegment);
        int delta = actual - expected;
        Pace pace = delta < 0
                ? Pace.BEHIND
                : delta > CLEARLY_AHEAD_MARGIN ? Pace.AHEAD : Pace.ON_TRACK;
        return new PaceAssessment(expected, actual, delta, pace);
    }

    static int nextSegment(
            int currentSegment,
            int generatedSegment,
            int observedTrainSegment,
            Integer activeMissionCheckpoint) {
        int next = Math.min(observedTrainSegment, generatedSegment);
        next = Math.min(next, currentSegment + 1);
        if (activeMissionCheckpoint != null) {
            next = Math.min(next, activeMissionCheckpoint);
        }
        return Math.max(currentSegment, next);
    }

    public enum Pace {
        BEHIND,
        ON_TRACK,
        AHEAD
    }

    public record PaceAssessment(
            int expectedRouteSegment,
            int actualRouteSegment,
            int delta,
            Pace pace) {
        public PaceAssessment {
            expectedRouteSegment = Math.max(0, expectedRouteSegment);
            actualRouteSegment = Math.max(0, actualRouteSegment);
            pace = java.util.Objects.requireNonNull(pace, "pace");
        }
    }

    /**
     * A deterministic linear mileage line. The slope is represented as a
     * non-negative fraction so pack configuration can use values such as
     * 3/2 segments per day without floating-point rounding.
     */
    public record MileageLine(
            int anchorDay,
            int anchorRouteSegment,
            int slopeNumerator,
            int slopeDenominator) {
        public MileageLine {
            if (anchorDay < FIRST_CAMPAIGN_DAY) {
                throw new IllegalArgumentException("Mileage anchor day must be positive");
            }
            if (anchorRouteSegment < 0) {
                throw new IllegalArgumentException("Mileage anchor segment must not be negative");
            }
            if (slopeNumerator < 0 || slopeDenominator < 1) {
                throw new IllegalArgumentException(
                        "Mileage slope must be a non-negative fraction");
            }
        }

        public int expectedRouteSegment(int day) {
            long elapsedDays = Math.max(0L, (long) day - anchorDay);
            long progress = elapsedDays * slopeNumerator / slopeDenominator;
            long expected = (long) anchorRouteSegment + progress;
            return (int) Math.min(Integer.MAX_VALUE, expected);
        }
    }
}
