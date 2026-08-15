package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouteProgressPolicyTest {
    @Test
    void cannotJumpPastGeneratedTrackOrMoreThanOneSegmentPerObservation() {
        assertEquals(6, RouteProgressPolicy.nextSegment(5, 20, 1_000, null));
        assertEquals(5, RouteProgressPolicy.nextSegment(5, 5, 1_000, null));
    }

    @Test
    void activeMissionLocksProgressAtItsCheckpoint() {
        assertEquals(5, RouteProgressPolicy.nextSegment(5, 20, 12, 5));
    }

    @Test
    void movingBackwardNeverRegressesCampaignProgress() {
        assertEquals(7, RouteProgressPolicy.nextSegment(7, 10, 2, null));
    }
}
