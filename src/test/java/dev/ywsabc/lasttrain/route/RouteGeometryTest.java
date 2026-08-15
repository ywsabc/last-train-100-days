package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import org.junit.jupiter.api.Test;

class RouteGeometryTest {
    @Test
    void segmentBoundariesAreStable() {
        assertEquals(25, RouteGeometry.segmentStartOffset(1));
        assertEquals(88, RouteGeometry.segmentEndOffset(1));
        assertEquals(89, RouteGeometry.segmentStartOffset(2));
        assertEquals(0, RouteGeometry.segmentForOffset(24));
        assertEquals(1, RouteGeometry.segmentForOffset(25));
        assertEquals(1, RouteGeometry.segmentForOffset(88));
        assertEquals(2, RouteGeometry.segmentForOffset(89));
    }

    @Test
    void missionsArePlacedOneSegmentAhead() {
        assertEquals(1, RouteGeometry.missionSegment(0));
        assertEquals(8, RouteGeometry.missionSegment(7));
        assertEquals(57, RouteGeometry.missionCenterOffset(0));
    }

    @Test
    void missionPlacementClampsToLastGeneratableSegmentWithoutOverflow() {
        int lastSegment = CampaignSavedData.MAX_ROUTE_SEGMENT;
        int lastCenter = RouteGeometry.segmentStartOffset(lastSegment)
                + RouteGeometry.SEGMENT_LENGTH / 2;

        assertEquals(lastSegment, RouteGeometry.missionSegment(lastSegment - 1));
        assertEquals(lastSegment, RouteGeometry.missionSegment(lastSegment));
        assertEquals(lastSegment, RouteGeometry.missionSegment(Integer.MAX_VALUE));
        assertEquals(lastCenter, RouteGeometry.missionCenterOffset(lastSegment));
        assertEquals(lastCenter, RouteGeometry.missionCenterOffset(Integer.MAX_VALUE));
    }

    @Test
    void segmentZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RouteGeometry.segmentStartOffset(0));
    }
}
