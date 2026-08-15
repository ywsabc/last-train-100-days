package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Pure template → geometry contract: the same segment index produces
 * different realization layouts depending on the committed plan, so the
 * director's output is driven by plans instead of fixed constants.
 */
class RouteSegmentLayoutTest {
    private static final long SEED = 0x4C415354L;
    private static final BlockPos STATION = new BlockPos(100, 64, 200);

    private static RouteSegmentPlan plan(
            int segment,
            SegmentTemplate template,
            List<RoutePoi> pois,
            List<RouteExit> exits) {
        return new RouteSegmentPlan(segment, SEED, template, pois, exits);
    }

    private static RouteSegmentPlan straight(int segment) {
        return plan(
                segment,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    @Test
    void straightSegmentsKeepTheLegacyEveryFourthWaypointCadence() {
        assertNull(RouteSegmentLayout.compute(STATION, 3, straight(3)).platformAnchor());
        RouteSegmentLayout fourth = RouteSegmentLayout.compute(STATION, 4, straight(4));
        assertTrue(fourth.hasPlatform());
        assertEquals(RouteSegmentLayout.LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH, fourth.platformHalfLength());
        assertEquals(
                STATION.offset(
                        RouteGeometry.segmentEndOffset(4)
                                - RouteSegmentLayout.LEGACY_WAYPOINT_PLATFORM_HALF_LENGTH,
                        0,
                        0),
                fourth.platformAnchor());
        assertEquals(List.of(), fourth.branchTrackSections());
        assertEquals(List.of(), fourth.turnPoints());
    }

    @Test
    void stationTemplateBuildsItsPlatformAtTheStationPoiAnchor() {
        int anchor = 30;
        RouteSegmentPlan stationPlan = plan(
                3,
                SegmentTemplate.STATION,
                List.of(new RoutePoi(RoutePoiType.STATION, anchor)),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 3, stationPlan);
        assertEquals(
                STATION.offset(RouteGeometry.segmentStartOffset(3) + anchor, 0, 0),
                layout.platformAnchor());
        assertEquals(RouteSegmentLayout.STATION_PLATFORM_HALF_LENGTH, layout.platformHalfLength());
        // The main line runs straight through, and a real platform siding
        // (side track) accompanies it: the two junction turn points sit at
        // the siding ends on the main line.
        RouteSegmentLayout.BranchTrackSection siding = layout.branchTrackSections().get(0);
        assertEquals(RouteSegmentLayout.BranchTrackKind.STATION_SIDING, siding.kind());
        assertEquals(Direction.EAST, siding.direction());
        assertEquals(2 * RouteSegmentLayout.STATION_PLATFORM_HALF_LENGTH + 1, siding.length());
        assertEquals(
                STATION.offset(
                        RouteGeometry.segmentStartOffset(3) + anchor
                                - RouteSegmentLayout.STATION_PLATFORM_HALF_LENGTH,
                        1,
                        RouteSegmentLayout.STATION_SIDING_LATERAL_OFFSET),
                siding.start());
        assertEquals(2, layout.turnPoints().size());
        assertEquals(
                STATION.offset(
                        RouteGeometry.segmentStartOffset(3) + anchor
                                - RouteSegmentLayout.STATION_PLATFORM_HALF_LENGTH,
                        1,
                        0),
                layout.turnPoints().get(0));
        assertEquals(
                STATION.offset(
                        RouteGeometry.segmentStartOffset(3) + anchor
                                + RouteSegmentLayout.STATION_PLATFORM_HALF_LENGTH,
                        1,
                        0),
                layout.turnPoints().get(1));
    }

    @Test
    void bridgeTunnelTemplateDensifiesSupportsAndDropsThePlatform() {
        RouteSegmentPlan bridgePlan = plan(
                5,
                SegmentTemplate.BRIDGE_TUNNEL,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 5, bridgePlan);
        assertFalse(layout.hasPlatform());
        assertEquals(List.of(), layout.branchTrackSections());
        assertEquals(List.of(), layout.turnPoints());
        // 64-block segment, support columns every 4 blocks, two pillar tops each.
        assertEquals(16 * 2, layout.deckSupportTops().size());
        assertEquals(
                STATION.offset(RouteGeometry.segmentStartOffset(5), -1, -1),
                layout.deckSupportTops().get(0));
        assertEquals(
                STATION.offset(RouteGeometry.segmentStartOffset(5), -1, 1),
                layout.deckSupportTops().get(1));
    }

    @Test
    void straightTemplateKeepsWideSupportSpacing() {
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 5, straight(5));
        assertEquals(8 * 2, layout.deckSupportTops().size());
    }

    @Test
    void cityBypassTemplateDerivesARealCitySpurAtItsBranchExit() {
        int branch = 24;
        RouteSegmentPlan cityPlan = plan(
                2,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, branch)),
                List.of(
                        new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                        new RouteExit(RouteExitKind.BRANCH, branch)));
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 2, cityPlan);
        // A real north/south branch track run toward the city interest
        // point, diverging from the main line at the planned branch exit —
        // never a stone platform.
        assertEquals(1, layout.branchTrackSections().size());
        RouteSegmentLayout.BranchTrackSection spur = layout.branchTrackSections().get(0);
        assertEquals(RouteSegmentLayout.BranchTrackKind.CITY_SPUR, spur.kind());
        assertEquals(RouteSegmentLayout.CITY_SPUR_LENGTH, spur.length());
        assertTrue(
                spur.direction() == Direction.NORTH || spur.direction() == Direction.SOUTH,
                "the city spur must head north or south, got " + spur.direction());
        assertEquals(
                STATION.offset(RouteGeometry.segmentStartOffset(2) + branch, 1, 0),
                spur.start());
        // The junction on the main line is the single turn point.
        assertEquals(List.of(spur.start()), layout.turnPoints());
    }

    @Test
    void citySpurDirectionIsDeterministicPerPlan() {
        int branch = 24;
        List<RoutePoi> pois = List.of(new RoutePoi(RoutePoiType.CITY, branch));
        List<RouteExit> exits = List.of(
                new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                new RouteExit(RouteExitKind.BRANCH, branch));
        RouteSegmentLayout first = RouteSegmentLayout.compute(
                STATION,
                2,
                plan(2, SegmentTemplate.CITY_BYPASS, pois, exits));
        RouteSegmentLayout sameSeed = RouteSegmentLayout.compute(
                STATION,
                2,
                plan(2, SegmentTemplate.CITY_BYPASS, pois, exits));
        // Same plan → identical shape data (and restart-identical layouts).
        assertEquals(first.branchTrackSections(), sameSeed.branchTrackSections());
        assertEquals(first, sameSeed);
    }

    @Test
    void everyLayoutDerivesItsMainlineStepsFromThePlan() {
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 7, straight(7));
        List<RouteSegmentLayout.RouteTrackStep> steps = layout.mainlineSteps();
        assertEquals(RouteGeometry.SEGMENT_LENGTH, steps.size());
        assertEquals(
                new RouteSegmentLayout.RouteTrackStep(
                        STATION.offset(RouteGeometry.segmentStartOffset(7), 1, 0),
                        Direction.EAST),
                steps.getFirst());
        assertEquals(
                new RouteSegmentLayout.RouteTrackStep(
                        STATION.offset(RouteGeometry.segmentEndOffset(7), 1, 0),
                        Direction.EAST),
                steps.getLast());
        for (int index = 0; index < steps.size(); index++) {
            assertEquals(Direction.EAST, steps.get(index).direction());
            assertEquals(
                    STATION.offset(RouteGeometry.segmentStartOffset(7) + index, 1, 0),
                    steps.get(index).position());
        }
        assertEquals(Direction.EAST, layout.mainlineDirection());
    }

    @Test
    void everyLayoutKeepsTheTrackStartAndBorderProbe() {
        RouteSegmentLayout layout = RouteSegmentLayout.compute(STATION, 7, straight(7));
        assertEquals(
                STATION.offset(RouteGeometry.segmentStartOffset(7), 1, 0),
                layout.trackStart());
        assertEquals(
                STATION.offset(RouteGeometry.segmentEndOffset(7), 1, 0),
                layout.borderProbe());
        // Support columns stay inside the segment on both deck edges.
        for (BlockPos top : layout.deckSupportTops()) {
            int dx = top.getX() - STATION.getX();
            assertTrue(
                    dx >= RouteGeometry.segmentStartOffset(7)
                            && dx <= RouteGeometry.segmentEndOffset(7));
            assertEquals(-1, top.getY() - STATION.getY());
            assertEquals(1, Math.abs(top.getZ() - STATION.getZ()));
        }
    }

    @Test
    void layoutRejectsAPlanOfAnotherSegment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteSegmentLayout.compute(STATION, 3, straight(4)));
    }
}
