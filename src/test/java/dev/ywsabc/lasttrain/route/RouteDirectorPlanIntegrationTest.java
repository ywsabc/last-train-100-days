package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * Integration contract between the route director, the planner and the
 * persisted plan state: generation follows the committed segment plan
 * (template-driven geometry) instead of fixed constants, and a save/load
 * round trip with the same seed keeps every segment identical.
 */
class RouteDirectorPlanIntegrationTest {
    private static final long CAMPAIGN_SEED = 0x4C415354L;
    private static final BlockPos STATION = new BlockPos(0, 64, 0);

    private static RouteSegmentPlan plan(
            int segment,
            SegmentTemplate template,
            List<RoutePoi> pois,
            List<RouteExit> exits) {
        return new RouteSegmentPlan(segment, 0x4C415354L, template, pois, exits);
    }

    private static RouteSegmentPlan straight(int segment) {
        return plan(
                segment,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(CAMPAIGN_SEED);
        data.start();
        data.markStarterStationBuilt(STATION);
        return data;
    }

    @Test
    void layoutConsumesTheCommittedPlanInsteadOfFixedConstants() {
        RouteSegmentPlan city = plan(
                2,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                List.of(
                        new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                        new RouteExit(RouteExitKind.BRANCH, 24)));
        CampaignSavedData cityData = started();
        cityData.commitRoutePlans(List.of(straight(1), city));
        RouteSegmentLayout cityLayout = RouteDirector.layoutFor(cityData, 2);
        assertEquals(1, cityLayout.branchTrackSections().size());

        CampaignSavedData straightData = started();
        straightData.commitRoutePlans(List.of(straight(1), straight(2)));
        assertEquals(0, RouteDirector.layoutFor(straightData, 2).branchTrackSections().size());
    }

    @Test
    void cityBypassSegmentsAppendTheirBranchRunsAfterTheMainLine() {
        RouteSegmentPlan city = plan(
                2,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                List.of(
                        new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                        new RouteExit(RouteExitKind.BRANCH, 24)));
        CampaignSavedData cityData = started();
        cityData.commitRoutePlans(List.of(straight(1), city));
        RouteSegmentLayout cityLayout = RouteDirector.layoutFor(cityData, 2);

        // The ordered submission sequence: the main line first, then the
        // branch track runs — never the other way around.
        List<RouteDirector.TrackRun> runs = RouteDirector.trackRuns(cityLayout);
        assertEquals(2, runs.size());
        RouteDirector.TrackRun mainline = runs.get(0);
        assertEquals(RouteDirector.TrackRunKind.MAIN_LINE, mainline.kind());
        assertEquals(cityLayout.trackStart(), mainline.start());
        assertEquals(cityLayout.mainlineDirection(), mainline.direction());
        assertEquals(RouteGeometry.SEGMENT_LENGTH, mainline.length());

        RouteDirector.TrackRun branch = runs.get(1);
        assertEquals(RouteDirector.TrackRunKind.BRANCH, branch.kind());
        assertEquals(cityLayout.branchTrackSections().get(0).start(), branch.start());
        assertEquals(cityLayout.branchTrackSections().get(0).direction(), branch.direction());
        assertEquals(cityLayout.branchTrackSections().get(0).length(), branch.length());

        // A plain straight plan submits exactly one run: the main line.
        CampaignSavedData straightData = started();
        straightData.commitRoutePlans(List.of(straight(1), straight(2)));
        List<RouteDirector.TrackRun> straightRuns =
                RouteDirector.trackRuns(RouteDirector.layoutFor(straightData, 2));
        assertEquals(1, straightRuns.size());
        assertEquals(RouteDirector.TrackRunKind.MAIN_LINE, straightRuns.get(0).kind());
        assertEquals(Direction.EAST, straightRuns.get(0).direction());
    }

    @Test
    void sameSeedRestartKeepsSegmentPlansIdentical() {
        CampaignSavedData data = started();
        data.commitRoutePlans(List.of(straight(1), straight(2)));
        RouteSegmentLayout before = RouteDirector.layoutFor(data, 5);

        CompoundTag saved = data.save(new CompoundTag(), null);
        CampaignSavedData reloaded = CampaignSavedData.load(saved, null);

        assertEquals(data.routeRulesVersion(), reloaded.routeRulesVersion());
        assertEquals(before.plan(), RouteDirector.layoutFor(reloaded, 5).plan());
        assertEquals(
                RouteDirector.layoutFor(data, 12).plan(),
                RouteDirector.layoutFor(reloaded, 12).plan());
        // The full physical shape data — mainline steps, turn points, branch
        // runs — is identical after the restart with the same seed.
        assertEquals(before, RouteDirector.layoutFor(reloaded, 5));
        assertEquals(
                RouteDirector.trackRuns(before),
                RouteDirector.trackRuns(RouteDirector.layoutFor(reloaded, 5)));
    }

    @Test
    void layoutCarriesTheCommittedPlanOfTheRequestedSegment() {
        CampaignSavedData data = started();
        RouteSegmentLayout layout = RouteDirector.layoutFor(data, 6);
        assertEquals(6, layout.plan().segmentIndex());
        assertEquals(data.routePlan(6), layout.plan());
    }

    @Test
    void planningAheadPersistsPendingSegmentsBeyondTheRequestedOne() {
        CampaignSavedData data = started();
        RouteDirector.layoutFor(data, 2);
        assertEquals(
                CampaignSavedData.DEFAULT_ROUTE_PLAN_AHEAD,
                data.plannedRouteSegments().size(),
                "the planner must commit the forward horizon as pending plans");
        assertEquals(
                CampaignSavedData.DEFAULT_ROUTE_PLAN_AHEAD,
                data.plannedRouteSegments()
                        .get(data.plannedRouteSegments().size() - 1)
                        .segmentIndex());
    }
}
