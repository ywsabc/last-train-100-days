package dev.ywsabc.lasttrain.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TongDaTrackPolicyTest {
    @Test
    void eastboundSegmentContainsExactly64UniqueConsecutivePositions() {
        List<TongDaTrackPolicy.Offset> offsets = TongDaTrackPolicy.eastboundTrackOffsets();

        assertEquals(64, offsets.size());
        assertEquals(64, new HashSet<>(offsets).size());
        assertEquals(new TongDaTrackPolicy.Offset(0, 0, 0), offsets.getFirst());
        assertEquals(new TongDaTrackPolicy.Offset(63, 0, 0), offsets.getLast());
        for (int index = 0; index < offsets.size(); index++) {
            assertEquals(new TongDaTrackPolicy.Offset(index, 0, 0), offsets.get(index));
        }
    }

    @Test
    void spawnerIsOutsideTrackAndVehicleEnvelope() {
        TongDaTrackPolicy.Offset spawner = TongDaTrackPolicy.eastboundSpawnerOffset();

        assertEquals(new TongDaTrackPolicy.Offset(0, 0, 4), spawner);
        assertFalse(TongDaTrackPolicy.eastboundTrackOffsets().contains(spawner));
        assertTrue(Math.abs(spawner.south()) > 3);
    }

    @Test
    void only64ObservedXoTracksAreMaterialized() {
        TongDaTrackPolicy.CompletionState complete = TongDaTrackPolicy.completionState(
                64,
                64,
                false);

        assertEquals(TongDaTrackPolicy.CompletionState.COMPLETE, complete);
        assertTrue(TongDaTrackPolicy.canMarkMaterialized(complete));

        TongDaTrackPolicy.CompletionState queued = TongDaTrackPolicy.completionState(
                64,
                0,
                true);
        assertEquals(TongDaTrackPolicy.CompletionState.MATERIALIZING, queued);
        assertFalse(TongDaTrackPolicy.canMarkMaterialized(queued));
    }

    @Test
    void missingOrUnloadedPositionsNeverCountAsComplete() {
        TongDaTrackPolicy.CompletionState incomplete = TongDaTrackPolicy.completionState(
                64,
                63,
                false);
        TongDaTrackPolicy.CompletionState unloaded = TongDaTrackPolicy.completionState(
                63,
                63,
                false);

        assertEquals(TongDaTrackPolicy.CompletionState.INCOMPLETE, incomplete);
        assertEquals(TongDaTrackPolicy.CompletionState.UNVERIFIED, unloaded);
        assertFalse(TongDaTrackPolicy.canMarkMaterialized(incomplete));
        assertFalse(TongDaTrackPolicy.canMarkMaterialized(unloaded));
    }

    @Test
    void firedSpawnerRetriesOnlyAfterACompleteObservationProvesMissingTrack() {
        assertTrue(TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                TongDaTrackPolicy.CompletionState.INCOMPLETE, 0));
        assertFalse(TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                TongDaTrackPolicy.CompletionState.INCOMPLETE, 1));
        assertFalse(TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                TongDaTrackPolicy.CompletionState.UNVERIFIED, 0));
        assertFalse(TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                TongDaTrackPolicy.CompletionState.MATERIALIZING, 0));
        assertFalse(TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                TongDaTrackPolicy.CompletionState.COMPLETE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TongDaTrackPolicy.shouldRetryAfterFiredSpawner(
                        TongDaTrackPolicy.CompletionState.INCOMPLETE, -1));
    }

    @Test
    void observationCountersRejectImpossibleStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TongDaTrackPolicy.completionState(-1, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> TongDaTrackPolicy.completionState(65, 64, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> TongDaTrackPolicy.completionState(10, 11, false));
    }
}
