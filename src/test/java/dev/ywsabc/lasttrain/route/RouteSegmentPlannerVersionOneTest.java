package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Historical rules version 1 branch of the planner: SplittableRandom draws
 * and no config input in the segment seed. The golden baselines pin the exact
 * historical outputs so old worlds that keep version 1 never silently
 * re-roll their future segments.
 */
class RouteSegmentPlannerVersionOneTest {
    private static final long CAMPAIGN_SEED = 0x4C415354L;
    private static final int SCAN_SEGMENTS = 512;
    /** 'S' station / 'T' straight for segments 2..64 of the coin-flip config under version 1. */
    private static final String COINFLIP_V1_GOLDEN_TEMPLATES =
            "TTTTSSSSSSSSTSTSSTTTSSSTTSSTTTSTSSSTTTSSSSSSSSTTTSTTTTTTTTTSTST";
    /** (segment, anchorOffset) of the first 32 POIs of the default config under version 1. */
    private static final int[][] V1_GOLDEN_POI_ANCHORS = {
        {3, 18}, {4, 38}, {7, 30}, {9, 38}, {12, 43}, {17, 34}, {18, 46}, {22, 35},
        {24, 20}, {25, 17}, {27, 29}, {28, 27}, {30, 38}, {31, 31}, {37, 27}, {38, 29},
        {40, 16}, {43, 36}, {49, 20}, {50, 20}, {54, 26}, {57, 16}, {58, 40}, {60, 22},
        {67, 40}, {68, 34}, {71, 30}, {73, 17}, {78, 33}, {79, 35}, {80, 45}, {85, 22},
    };

    @Test
    void versionOneSeedsIgnoreTheConfigAndDifferFromVersionTwo() {
        assertEquals(
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, RouteTemplateConfig.DEFAULT, 7),
                RouteSegmentPlanner.segmentSeed(
                        CAMPAIGN_SEED,
                        0,
                        1,
                        RouteTemplateConfig.DEFAULT.withStationMinGap(3),
                        7));
        assertNotEquals(
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 1, RouteTemplateConfig.DEFAULT, 7),
                RouteSegmentPlanner.segmentSeed(CAMPAIGN_SEED, 0, 2, RouteTemplateConfig.DEFAULT, 7));
    }

    @Test
    void versionOneAndVersionTwoProduceDifferentButDeterministicPlans() {
        RouteSegmentPlanner first = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1);
        RouteSegmentPlanner second = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1);
        RouteSegmentPlanner versionTwo = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 2);
        boolean differs = false;
        for (int segment = 1; segment <= SCAN_SEGMENTS; segment++) {
            RouteSegmentPlan plan = first.plan(segment);
            assertEquals(plan, second.plan(segment), "version 1 must be deterministic");
            if (plan.template() != versionTwo.plan(segment).template()) {
                differs = true;
            }
        }
        assertTrue(differs, "version 1 and version 2 must not coincide over " + SCAN_SEGMENTS + " segments");
    }

    @Test
    void versionOneTemplateDrawsFollowTheHistoricalSplittableRandomSequence() {
        assertEquals(0L, new SplittableRandom(CAMPAIGN_SEED).nextLong(0, 2));
        RouteTemplateConfig coinFlip =
                RouteTemplateConfig.DEFAULT.withWeights(1, 1, 0, 0).withStationMinGap(0);
        RouteSegmentPlanner planner = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, coinFlip);
        StringBuilder actual = new StringBuilder(COINFLIP_V1_GOLDEN_TEMPLATES.length());
        for (int segment = 2; segment <= 64; segment++) {
            actual.append(
                    planner.plan(segment).template() == SegmentTemplate.STATION ? 'S' : 'T');
        }
        assertEquals(COINFLIP_V1_GOLDEN_TEMPLATES, actual.toString());
    }

    @Test
    void versionOnePoiAnchorsArePinnedByGoldenBaseline() {
        assertEquals(33, new SplittableRandom(CAMPAIGN_SEED).nextInt(16, 49));
        RouteSegmentPlanner planner =
                new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1, RouteTemplateConfig.DEFAULT);
        int checked = 0;
        for (int segment = 1; segment <= 4096 && checked < V1_GOLDEN_POI_ANCHORS.length; segment++) {
            for (RoutePoi poi : planner.plan(segment).pois()) {
                assertEquals(
                        V1_GOLDEN_POI_ANCHORS[checked][0], segment, "POI #" + checked + " segment");
                assertEquals(
                        V1_GOLDEN_POI_ANCHORS[checked][1],
                        poi.anchorOffset(),
                        "POI #" + checked + " anchor");
                checked++;
            }
        }
        assertEquals(V1_GOLDEN_POI_ANCHORS.length, checked, "not all golden POIs found");
    }

    @Test
    void versionOneResumesFromPersistedPendingPlansWithoutReRolling() {
        RouteSegmentPlanner fresh = new RouteSegmentPlanner(CAMPAIGN_SEED, 0, 1);
        fresh.plan(5);
        RouteSegmentPlanner resumed = RouteSegmentPlanner.resume(
                CAMPAIGN_SEED,
                0,
                1,
                RouteTemplateConfig.DEFAULT,
                2,
                fresh.cursor(),
                java.util.List.of(fresh.plan(3), fresh.plan(4), fresh.plan(5)));
        assertEquals(fresh.plan(3), resumed.plan(3));
        assertEquals(fresh.plan(5), resumed.plan(5));
        assertEquals(fresh.plan(9), resumed.plan(9));
    }
}
