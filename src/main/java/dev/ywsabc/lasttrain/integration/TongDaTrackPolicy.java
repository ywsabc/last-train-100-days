package dev.ywsabc.lasttrain.integration;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Pure geometry and completion policy for one TongDa-backed route segment.
 *
 * <p>This class deliberately has no Minecraft or TongDa types. The runtime
 * bridge can therefore keep the optional mod behind reflection, while the
 * important rule remains directly testable: a queued spawner is pending work,
 * not proof that all 64 physical Create tracks exist.</p>
 */
public final class TongDaTrackPolicy {
    public static final int SEGMENT_LENGTH = 64;
    public static final int SPAWNER_LATERAL_OFFSET = 4;

    private static final List<Offset> EASTBOUND_TRACK_OFFSETS = IntStream.range(0, SEGMENT_LENGTH)
            .mapToObj(east -> new Offset(east, 0, 0))
            .toList();
    private static final Offset EASTBOUND_SPAWNER_OFFSET =
            new Offset(0, 0, SPAWNER_LATERAL_OFFSET);

    private TongDaTrackPolicy() {
    }

    /** Returns the 64 block positions, relative to the westernmost track. */
    public static List<Offset> eastboundTrackOffsets() {
        return EASTBOUND_TRACK_OFFSETS;
    }

    /**
     * Returns the deterministic TongDa spawner location relative to the first
     * track. It sits outside the campaign's three-block vehicle envelope.
     */
    public static Offset eastboundSpawnerOffset() {
        return EASTBOUND_SPAWNER_OFFSET;
    }

    /**
     * Classifies an observation without equating a queued spawner with
     * materialization success.
     */
    public static CompletionState completionState(
            int loadedTrackPositions,
            int matchingTrackPositions,
            boolean spawnerPresent) {
        if (loadedTrackPositions < 0 || loadedTrackPositions > SEGMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "loadedTrackPositions must be between 0 and " + SEGMENT_LENGTH);
        }
        if (matchingTrackPositions < 0 || matchingTrackPositions > loadedTrackPositions) {
            throw new IllegalArgumentException(
                    "matchingTrackPositions must be between 0 and loadedTrackPositions");
        }

        if (loadedTrackPositions == SEGMENT_LENGTH
                && matchingTrackPositions == SEGMENT_LENGTH) {
            return CompletionState.COMPLETE;
        }
        if (spawnerPresent) {
            return CompletionState.MATERIALIZING;
        }
        if (loadedTrackPositions < SEGMENT_LENGTH) {
            return CompletionState.UNVERIFIED;
        }
        return CompletionState.INCOMPLETE;
    }

    /** Only a complete physical observation may advance persistent progress. */
    public static boolean canMarkMaterialized(CompletionState state) {
        return state == CompletionState.COMPLETE;
    }

    /**
     * A fired TongDa spawner may be retried only after every position was
     * loaded and the observation proved that at least one XO track is absent.
     * Unknown/unloaded chunks must be checked later instead of triggering a
     * redundant replacement spawner.
     */
    public static boolean shouldRetryAfterFiredSpawner(
            CompletionState state,
            int conflictingCreateTracks) {
        if (conflictingCreateTracks < 0 || conflictingCreateTracks > SEGMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "conflictingCreateTracks must be between 0 and " + SEGMENT_LENGTH);
        }
        return state == CompletionState.INCOMPLETE && conflictingCreateTracks == 0;
    }

    public enum CompletionState {
        /** All 64 loaded positions contain a Create track with shape XO. */
        COMPLETE,
        /** The TongDa spawner still exists; track placement has not been proven. */
        MATERIALIZING,
        /** At least one track position was not loaded and could not be checked. */
        UNVERIFIED,
        /** Every position was checked, but at least one XO track is absent. */
        INCOMPLETE
    }

    public record Offset(int east, int up, int south) {
    }
}
