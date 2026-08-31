package dev.ywsabc.lasttrain.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Pure pool-constraint policy for the mission director.
 *
 * <p>Rules encoded here:</p>
 * <ul>
 * <li>at most one route-blocking mainline mission exists at a time (enforced
 * by the saved data's single mainline slot, checked here against history);</li>
 * <li>the same mainline type never runs twice in a row — including zombie
 * blockades, so a siege right after a cleared blockade is postponed until
 * another mainline mission ran in between;</li>
 * <li>after two consecutive failures the director only draws short missions
 * or missions with guaranteed supplies.</li>
 * </ul>
 */
public final class MissionPoolPolicy {
    public static final int HISTORY_LIMIT = 32;
    public static final int FAILURE_STREAK_THRESHOLD = 2;
    public static final int MAX_GRACE_DAYS_FOR_SHORT_MISSION = 4;

    /**
     * Mission types the director may draw for the single mission slot, in the
     * historical selection order. Supply recovery is a support mission: it
     * occupies the slot but never blocks the route.
     */
    public static final List<MissionType> MAINLINE_TYPES = List.of(
            MissionType.RAIL_BREAK,
            MissionType.STATION_POWER,
            MissionType.TRACK_CLEARANCE,
            MissionType.SUPPLY_RECOVERY,
            MissionType.ZOMBIE_BLOCKADE);

    public static final List<MissionType> OPTIONAL_TYPES = List.of(
            MissionType.RESCUE_SURVIVOR,
            MissionType.SALVAGE_CAR);

    private MissionPoolPolicy() {
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        SKIPPED
    }

    /** One settled mission run, oldest entries first in history lists. */
    public record Entry(MissionType type, boolean mainline, Outcome outcome) {
        public Entry {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public static Entry entry(MissionType type, Outcome outcome) {
        Objects.requireNonNull(type, "type");
        return new Entry(type, type.occupiesMainlineSlot(), outcome);
    }

    /** Truncates a loaded history to its newest {@link #HISTORY_LIMIT} entries. */
    public static List<Entry> bounded(List<Entry> history) {
        Objects.requireNonNull(history, "history");
        if (history.size() <= HISTORY_LIMIT) {
            return List.copyOf(history);
        }
        return List.copyOf(history.subList(history.size() - HISTORY_LIMIT, history.size()));
    }

    public static Optional<MissionType> lastMainlineType(List<Entry> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Entry entry = history.get(index);
            if (entry.mainline()) {
                return Optional.of(entry.type());
            }
        }
        return Optional.empty();
    }

    public static int consecutiveFailures(List<Entry> history) {
        int failures = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).outcome() != Outcome.FAILED) {
                break;
            }
            failures++;
        }
        return failures;
    }

    /**
     * Hard consecutive-type constraint, including pressure zombie blockades:
     * a second mission of the same slot-occupying type may only start after
     * some other slot-occupying mission ran in between. Support missions such
     * as supply recovery occupy the slot and obey the same constraint.
     */
    public static boolean mayCreateMainline(List<Entry> history, MissionType type) {
        Objects.requireNonNull(type, "type");
        if (!type.occupiesMainlineSlot()) {
            return false;
        }
        return lastMainlineType(history).map(last -> last != type).orElse(true);
    }

    /** Short missions and guaranteed-supply missions are the recovery pool. */
    public static boolean isShortOrSupplied(MissionType type) {
        Objects.requireNonNull(type, "type");
        return MissionFallbackPolicy.graceDays(type) <= MAX_GRACE_DAYS_FOR_SHORT_MISSION
                || type.guaranteedSupplies();
    }

    /**
     * Draws the next mainline mission type. After {@link #FAILURE_STREAK_THRESHOLD}
     * consecutive failures the pool shrinks to short or guaranteed-supply
     * missions. The uniform draw over the full pool keeps the pre-existing
     * deterministic sequence for a team with no recent failures.
     */
    public static Optional<MissionType> selectMainMissionType(
            List<Entry> history,
            RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        List<MissionType> candidates = new ArrayList<>();
        boolean recoveryPool =
                consecutiveFailures(history) >= FAILURE_STREAK_THRESHOLD;
        for (MissionType type : MAINLINE_TYPES) {
            if (!mayCreateMainline(history, type)) {
                continue;
            }
            if (recoveryPool && !isShortOrSupplied(type)) {
                continue;
            }
            candidates.add(type);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(random.nextInt(candidates.size())));
    }

    public static Optional<MissionType> selectOptionalType(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (OPTIONAL_TYPES.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(OPTIONAL_TYPES.get(random.nextInt(OPTIONAL_TYPES.size())));
    }

    /** Bounded history used by saved-data deserialization. */
    public static List<Entry> limitToNewest(List<Entry> history, int limit) {
        Objects.requireNonNull(history, "history");
        if (history.size() <= limit) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - limit, history.size()));
    }
}
