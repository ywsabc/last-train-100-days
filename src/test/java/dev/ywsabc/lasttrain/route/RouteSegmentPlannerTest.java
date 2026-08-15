package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RouteSegmentPlannerTest {
    private static final long CAMPAIGN_SEED = 0x4C415354L;
    private static final int SAMPLE_SEGMENTS = 256;
    private static final int MIX_SCAN_SEGMENTS = 2048;
    private static final int TEMPLATE_SCAN_SEGMENTS = 4096;

    @Test
    void sameInputsProduceIdenticalPlans() {
        RouteSegmentPlanner first = new RouteSegmentPlanner(CAMPAIGN_SEED);
        RouteSegmentPlanner second = new RouteSegmentPlanner(CAMPAIGN_SEED);
        for (int segment = 1; segment <= SAMPLE_SEGMENTS; segment++) {
            assertEquals(first.plan(segment), second.plan(segment));
        }
    }

    @Test
    void plansAreStableAcrossOutOfOrderQueries() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        RouteSegmentPlan far = planner.plan(300);
        assertEquals(far, planner.plan(300));
        assertEquals(new RouteSegmentPlanner(CAMPAIGN_SEED).plan(10), planner.plan(10));
    }

    @Test
    void templateChoiceReactsToEachSeedInput() {
        RouteSegmentPlanner baseline = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1);
        RouteSegmentPlanner otherRules = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 2);
        RouteSegmentPlanner otherRoute = new RouteSegmentPlanner(CAMPAIGN_SEED, 1, 1);
        RouteSegmentPlanner otherCampaign = new RouteSegmentPlanner(CAMPAIGN_SEED + 1, 0, 1);
        assertTrue(anyTemplateDiffers(baseline, otherRules, SAMPLE_SEGMENTS));
        assertTrue(anyTemplateDiffers(baseline, otherRoute, SAMPLE_SEGMENTS));
        assertTrue(anyTemplateDiffers(baseline, otherCampaign, SAMPLE_SEGMENTS));
    }

    @Test
    void allFourTemplatesAppearAcrossLongRoute() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        EnumSet<SegmentTemplate> seen = EnumSet.noneOf(SegmentTemplate.class);
        for (int segment = 1; segment <= TEMPLATE_SCAN_SEGMENTS; segment++) {
            seen.add(planner.plan(segment).template());
        }
        assertEquals(EnumSet.allOf(SegmentTemplate.class), seen);
    }

    @Test
    void cityBypassNeverAppearsConsecutively() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        for (int segment = 2; segment <= MIX_SCAN_SEGMENTS; segment++) {
            if (planner.plan(segment).template() == SegmentTemplate.CITY_BYPASS) {
                assertNotEquals(
                        SegmentTemplate.CITY_BYPASS,
                        planner.plan(segment - 1).template(),
                        "city bypass at segment " + segment);
            }
        }
    }

    @Test
    void bridgeTunnelStaysInsideConfiguredBudget() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, config);
        for (int end = 1; end <= MIX_SCAN_SEGMENTS; end++) {
            int start = Math.max(1, end - config.bridgeTunnelWindow() + 1);
            int bridges = 0;
            for (int segment = start; segment <= end; segment++) {
                if (planner.plan(segment).template() == SegmentTemplate.BRIDGE_TUNNEL) {
                    bridges++;
                }
            }
            assertTrue(
                    bridges <= config.bridgeTunnelMaxPerWindow(),
                    "bridge budget exceeded in window ending at " + end);
        }
    }

    @Test
    void stationRespectsConfiguredMinimumGap() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, config);
        int lastStation = 0;
        for (int segment = 1; segment <= MIX_SCAN_SEGMENTS; segment++) {
            if (planner.plan(segment).template() == SegmentTemplate.STATION) {
                if (lastStation != 0) {
                    assertTrue(
                            segment - lastStation > config.stationMinGap(),
                            "station gap violated between "
                                    + lastStation
                                    + " and "
                                    + segment);
                }
                lastStation = segment;
            }
        }
        assertTrue(lastStation > 0);
    }

    @Test
    void starterStationGapKeepsEarlySegmentsStationFree() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        for (long seed = CAMPAIGN_SEED; seed < CAMPAIGN_SEED + 64; seed++) {
            RouteSegmentPlanner planner = new RouteSegmentPlanner(seed, 0, 1, config);
            for (int segment = 2; segment <= config.stationMinGap() + 1; segment++) {
                assertNotEquals(
                        SegmentTemplate.STATION,
                        planner.plan(segment).template(),
                        "seed " + seed + " segment " + segment);
            }
        }
    }

    @Test
    void earliestStationAfterStarterGapIsSegmentFour() {
        RouteTemplateConfig stationOnly = RouteTemplateConfig.DEFAULT.withWeights(0, 1, 0, 0);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, stationOnly);
        assertEquals(SegmentTemplate.STRAIGHT, planner.plan(1).template());
        assertEquals(SegmentTemplate.STRAIGHT, planner.plan(2).template());
        assertEquals(SegmentTemplate.STRAIGHT, planner.plan(3).template());
        assertEquals(SegmentTemplate.STATION, planner.plan(4).template());
    }

    @Test
    void everyPlanHasExactlyOneMainLineExit() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        for (int segment = 1; segment <= SAMPLE_SEGMENTS; segment++) {
            RouteSegmentPlan plan = planner.plan(segment);
            long mainExits = plan.exits().stream()
                    .filter(exit -> exit.kind() == RouteExitKind.MAIN_LINE)
                    .count();
            assertEquals(1, mainExits, "segment " + segment);
            assertEquals(RouteExitKind.MAIN_LINE, plan.mainExit().kind());
            assertEquals(RouteGeometry.SEGMENT_LENGTH, plan.mainExit().anchorOffset());
        }
    }

    @Test
    void poiSlotsFollowTemplateContract() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        for (int segment = 1; segment <= MIX_SCAN_SEGMENTS; segment++) {
            RouteSegmentPlan plan = planner.plan(segment);
            switch (plan.template()) {
                case STRAIGHT, BRIDGE_TUNNEL -> {
                    assertTrue(plan.pois().isEmpty(), "segment " + segment);
                    assertEquals(1, plan.exits().size(), "segment " + segment);
                }
                case STATION -> {
                    assertEquals(1, plan.pois().size(), "segment " + segment);
                    assertEquals(RoutePoiType.STATION, plan.pois().get(0).type());
                    assertEquals(1, plan.exits().size(), "segment " + segment);
                }
                case CITY_BYPASS -> {
                    assertEquals(1, plan.pois().size(), "segment " + segment);
                    RoutePoi city = plan.pois().get(0);
                    assertEquals(RoutePoiType.CITY, city.type());
                    assertEquals(2, plan.exits().size(), "segment " + segment);
                    RouteExit branch = plan.exits().stream()
                            .filter(exit -> exit.kind() == RouteExitKind.BRANCH)
                            .findFirst()
                            .orElseThrow();
                    assertEquals(city.anchorOffset(), branch.anchorOffset());
                }
            }
        }
    }

    @Test
    void poiPlacementIsDeterministicAndInsideConfiguredRange() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        RouteSegmentPlanner first = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, config);
        RouteSegmentPlanner second = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, config);
        boolean foundPoi = false;
        for (int segment = 1; segment <= MIX_SCAN_SEGMENTS; segment++) {
            RouteSegmentPlan plan = first.plan(segment);
            assertEquals(plan.pois(), second.plan(segment).pois());
            for (RoutePoi poi : plan.pois()) {
                foundPoi = true;
                assertTrue(poi.anchorOffset() >= config.poiAnchorMin(), "segment " + segment);
                assertTrue(poi.anchorOffset() <= config.poiAnchorMax(), "segment " + segment);
            }
        }
        assertTrue(foundPoi);
    }

    @Test
    void templateDrawsFollowJavaUtilRandomSequence() {
        // With exactly two candidate weights the template selection reduces to
        // one draw: STATION iff the first roll is 0. Pinning the roll source to
        // java.util.Random guards save compatibility, because SplittableRandom
        // does not promise a stable sequence across JVM versions.
        RouteTemplateConfig coinFlip =
                RouteTemplateConfig.DEFAULT.withWeights(1, 1, 0, 0).withStationMinGap(0);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, coinFlip);
        for (int segment = 2; segment <= 64; segment++) {
            long seed = RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, coinFlip.fingerprint(), segment);
            SegmentTemplate expected = new Random(seed).nextLong(0, 2) == 0
                    ? SegmentTemplate.STATION
                    : SegmentTemplate.STRAIGHT;
            assertEquals(expected, planner.plan(segment).template(), "segment " + segment);
        }
    }

    @Test
    void poiAnchorDrawsFollowJavaUtilRandomSequence() {
        RouteTemplateConfig config = RouteTemplateConfig.DEFAULT;
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, config);
        boolean foundPoi = false;
        for (int segment = 1; segment <= MIX_SCAN_SEGMENTS; segment++) {
            RouteSegmentPlan plan = planner.plan(segment);
            for (RoutePoi poi : plan.pois()) {
                foundPoi = true;
                long slotSeed = RouteSegmentPlanner.poiSeed(plan.segmentSeed(), 0);
                int expected = new Random(slotSeed).nextInt(config.poiAnchorMin(), config.poiAnchorMax() + 1);
                assertEquals(expected, poi.anchorOffset(), "segment " + segment);
            }
        }
        assertTrue(foundPoi);
    }

    @Test
    void planSeedsMatchPublicSeedDerivation() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        for (int segment = 1; segment <= 64; segment++) {
            assertEquals(
                    RouteSegmentPlanner.segmentSeed(
                            CAMPAIGN_SEED,
                            0,
                            1,
                            RouteTemplateConfig.DEFAULT.fingerprint(),
                            segment),
                    planner.plan(segment).segmentSeed());
        }
    }

    @Test
    void segmentSeedReactsToEachInput() {
        long defaultFingerprint = RouteTemplateConfig.DEFAULT.fingerprint();
        long seed = RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, defaultFingerprint, 7);
        assertNotEquals(0L, seed);
        assertEquals(seed, RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, defaultFingerprint, 7));
        assertNotEquals(
                seed,
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 2, defaultFingerprint, 7));
        assertNotEquals(
                seed,
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 1, 1, defaultFingerprint, 7));
        assertNotEquals(
                seed,
                RouteSegmentPlanner.segmentSeed(
                        CAMPAIGN_SEED + 1, 0, 1, defaultFingerprint, 7));
        assertNotEquals(
                seed,
                RouteSegmentPlanner.segmentSeed(
                        CAMPAIGN_SEED,
                        0,
                        1,
                        RouteTemplateConfig.DEFAULT.withStationMinGap(3).fingerprint(),
                        7));
        assertNotEquals(
                seed,
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, defaultFingerprint, 8));
    }

    @Test
    void sameRulesVersionDifferentConfigReRollsPlans() {
        RouteSegmentPlanner baseline =
                new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, RouteTemplateConfig.DEFAULT);
        RouteSegmentPlanner otherConfig = new RouteSegmentPlanner(
                CAMPAIGN_SEED,
                0,
                1,
                RouteTemplateConfig.DEFAULT.withStationMinGap(3));
        assertTrue(anyTemplateDiffers(baseline, otherConfig, SAMPLE_SEGMENTS));
    }

    @Test
    void firstSegmentIsStraight() {
        assertEquals(
                SegmentTemplate.STRAIGHT,
                new RouteSegmentPlanner(CAMPAIGN_SEED).plan(1).template());
        assertEquals(
                SegmentTemplate.STRAIGHT,
                new RouteSegmentPlanner(0xDEADBEEFL, 2, 5).plan(1).template());
    }

    @Test
    void segmentIndexBelowOneIsRejected() {
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED);
        assertThrows(IllegalArgumentException.class, () -> planner.plan(0));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(-4));
        assertThrows(
                IllegalArgumentException.class,
                () -> RouteSegmentPlanner.segmentSeed(
                        CAMPAIGN_SEED, 0, 1, RouteTemplateConfig.DEFAULT.fingerprint(), 0));
    }

    @Test
    void invalidPlannerContextIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlanner(CAMPAIGN_SEED, -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 0));
    }

    @Test
    void negativePoiSlotIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RouteSegmentPlanner.poiSeed(1L, -1));
    }

    @Test
    void plansWithoutExactlyOneMainExitAreRejected() {
        RouteExit main = new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH);
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(1, 1L, SegmentTemplate.STRAIGHT, List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteSegmentPlan(
                        1,
                        1L,
                        SegmentTemplate.STRAIGHT,
                        List.of(),
                        List.of(main, main)));
    }

    @Test
    void poiAnchorsOutsideTheSegmentAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RoutePoi(RoutePoiType.CITY, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RoutePoi(RoutePoiType.CITY, RouteGeometry.SEGMENT_LENGTH));
    }

    @Test
    void zeroingNonStraightWeightsLeavesOnlyStraight() {
        RouteTemplateConfig straightOnly = RouteTemplateConfig.DEFAULT.withWeights(1, 0, 0, 0);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, straightOnly);
        for (int segment = 1; segment <= SAMPLE_SEGMENTS; segment++) {
            assertEquals(SegmentTemplate.STRAIGHT, planner.plan(segment).template());
        }
    }

    @Test
    void cityBypassGapIsConfigurable() {
        RouteTemplateConfig busyCity =
                RouteTemplateConfig.DEFAULT.withWeights(1, 0, 100, 0).withCityBypassMinGap(3);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, busyCity);
        int lastBypass = 0;
        int bypasses = 0;
        for (int segment = 1; segment <= SAMPLE_SEGMENTS; segment++) {
            if (planner.plan(segment).template() == SegmentTemplate.CITY_BYPASS) {
                bypasses++;
                if (lastBypass != 0) {
                    assertTrue(
                            segment - lastBypass > busyCity.cityBypassMinGap(),
                            "bypass gap violated between "
                                    + lastBypass
                                    + " and "
                                    + segment);
                }
                lastBypass = segment;
            }
        }
        assertTrue(bypasses > 0);
    }

    @Test
    void bridgeTunnelBudgetIsConfigurable() {
        RouteTemplateConfig noBridges =
                RouteTemplateConfig.DEFAULT.withWeights(1, 0, 0, 100).withBridgeTunnelBudget(0, 12);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, noBridges);
        for (int segment = 1; segment <= SAMPLE_SEGMENTS; segment++) {
            assertNotEquals(SegmentTemplate.BRIDGE_TUNNEL, planner.plan(segment).template());
        }
    }

    private static boolean anyTemplateDiffers(
            RouteSegmentPlanner first,
            RouteSegmentPlanner second,
            int segments) {
        for (int segment = 1; segment <= segments; segment++) {
            if (first.plan(segment).template() != second.plan(segment).template()) {
                return true;
            }
        }
        return false;
    }
}
