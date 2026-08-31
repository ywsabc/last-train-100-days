package dev.ywsabc.lasttrain.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 设计文档 6.3.D：任务只能来自 RouteDirector 的真实分支线路。 */
class SwitchSignalMissionTest {
    private static final BlockPos STATION = new BlockPos(0, 64, 0);

    @Test
    void routeDirectorExtractsTurnoutFromBranchButNotStraightTrack() {
        RouteSegmentLayout city = RouteSegmentLayout.compute(
                STATION,
                2,
                cityPlan(2));
        List<RouteTurnout> turnouts = RouteDirector.turnouts(city);
        assertEquals(1, turnouts.size());
        assertEquals(2, turnouts.getFirst().segment());
        assertEquals(city.turnPoints().getFirst(), turnouts.getFirst().junction());
        assertEquals(
                city.branchTrackSections().getFirst().direction(),
                turnouts.getFirst().branchDirection());

        RouteSegmentLayout straight = RouteSegmentLayout.compute(
                STATION,
                1,
                straightPlan(1));
        assertTrue(RouteDirector.turnouts(straight).isEmpty());
    }

    @Test
    void missionGenerationClaimsPersistedTurnoutAndCompletesInOrder() {
        CampaignSavedData data = started();
        data.markRouteSegmentGenerated(1);
        data.markRouteSegmentGenerated(2);
        RouteTurnout turnout = RouteDirector.turnouts(RouteSegmentLayout.compute(
                        STATION,
                        2,
                        cityPlan(2)))
                .getFirst();
        data.recordRouteTurnouts(List.of(turnout));
        assertTrue(data.hasAvailableRouteTurnout());

        assertTrue(data.createMission(MissionType.SWITCH_SIGNAL));
        ActiveMission mission = data.activeMission();
        assertEquals(turnout.junction(), mission.site());
        assertEquals(turnout.branchDirection(), mission.objectiveDirection());
        assertFalse(data.hasAvailableRouteTurnout());
        assertEquals(MissionType.MissionPhase.REPAIR_SWITCH_BOX, mission.currentPhase());

        data.addMissionProgress(mission.target());
        assertEquals(MissionType.MissionPhase.CONFIRM_SIGNALS, mission.currentPhase());
        assertEquals(MissionStage.ACTIVE, mission.stage());
        data.setMissionObservedProgress(1);
        assertEquals(MissionStage.ACTIVE, mission.stage());
        data.setMissionObservedProgress(2);
        assertEquals(MissionStage.READY_TO_TURN_IN, mission.stage());
    }

    @Test
    void restartKeepsUnclaimedTurnoutAndStraightRouteCannotCreateMission() {
        CampaignSavedData straight = started();
        assertFalse(straight.createMission(MissionType.SWITCH_SIGNAL));

        CampaignSavedData withTurnout = started();
        withTurnout.markRouteSegmentGenerated(1);
        withTurnout.markRouteSegmentGenerated(2);
        RouteTurnout turnout = RouteDirector.turnouts(RouteSegmentLayout.compute(
                        STATION,
                        2,
                        cityPlan(2)))
                .getFirst();
        withTurnout.recordRouteTurnouts(List.of(turnout));

        CampaignSavedData loaded = CampaignSavedData.load(
                withTurnout.save(new net.minecraft.nbt.CompoundTag(), null),
                null);
        assertEquals(List.of(turnout), loaded.routeTurnouts());
        assertTrue(loaded.createMission(MissionType.SWITCH_SIGNAL));
        assertEquals(turnout.junction(), loaded.activeMission().site());
        assertEquals(turnout.branchDirection(), loaded.activeMission().objectiveDirection());
    }

    private static RouteSegmentPlan straightPlan(int segment) {
        return new RouteSegmentPlan(
                segment,
                1L,
                SegmentTemplate.STRAIGHT,
                List.of(),
                List.of(new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH)));
    }

    private static RouteSegmentPlan cityPlan(int segment) {
        return new RouteSegmentPlan(
                segment,
                2L,
                SegmentTemplate.CITY_BYPASS,
                List.of(new RoutePoi(RoutePoiType.CITY, 24)),
                List.of(
                        new RouteExit(RouteExitKind.MAIN_LINE, RouteGeometry.SEGMENT_LENGTH),
                        new RouteExit(RouteExitKind.BRANCH, 24)));
    }

    private static CampaignSavedData started() {
        CampaignSavedData data = new CampaignSavedData();
        data.initialize(9L);
        data.start();
        data.markStarterStationBuilt(STATION);
        return data;
    }
}
