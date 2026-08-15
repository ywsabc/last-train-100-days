package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignPacingPolicyTest {
    @Test
    void everyChapterKeyMissionHasBothDayAndMileageGates() {
        for (CampaignPacingPolicy.KeyMission keyMission
                : CampaignPacingPolicy.KeyMission.values()) {
            int requiredDay = keyMission.chapter().startDay();
            int requiredSegment = keyMission.minimumRouteSegment();

            assertFalse(
                    CampaignPacingPolicy.isKeyMissionEligible(
                            keyMission,
                            requiredDay,
                            Math.max(0, requiredSegment - 1)),
                    keyMission.name());
            assertTrue(
                    CampaignPacingPolicy.isKeyMissionEligible(
                            keyMission,
                            requiredDay,
                            requiredSegment),
                    keyMission.name());
            if (requiredDay > 1) {
                assertFalse(
                        CampaignPacingPolicy.isKeyMissionEligible(
                                keyMission,
                                requiredDay - 1,
                                requiredSegment),
                        keyMission.name());
            }
        }
    }

    @Test
    void directorDefersTheNextKeyMissionUntilItsMileageGate() {
        CampaignPacingPolicy.KeyMission next =
                CampaignPacingPolicy.nextKeyMission(
                        CampaignPacingPolicy.Chapter.SCARCITY.startDay(),
                        CampaignPacingPolicy.KeyMission.FIRST_CITY_STATION.minimumRouteSegment(),
                        Set.of(CampaignPacingPolicy.KeyMission.PROLOGUE_DEPARTURE))
                        .orElseThrow();

        assertEquals(CampaignPacingPolicy.KeyMission.FIRST_CITY_STATION, next);
        assertTrue(
                CampaignPacingPolicy.nextKeyMission(
                                CampaignPacingPolicy.Chapter.SCARCITY.startDay(),
                                next.minimumRouteSegment() - 1,
                                Set.of(CampaignPacingPolicy.KeyMission.PROLOGUE_DEPARTURE))
                        .isEmpty());
    }

    @Test
    void laggingTeamsTradeLowValueObstaclesForSupplyGuarantees() {
        int day = 30;
        int expected = CampaignPacingPolicy.expectedRouteSegment(day);

        CampaignPacingPolicy.MissionWeights onTrack =
                CampaignPacingPolicy.missionWeights(day, expected);
        CampaignPacingPolicy.MissionWeights behind =
                CampaignPacingPolicy.missionWeights(day, expected - 4);

        assertTrue(
                behind.weight(CampaignPacingPolicy.MissionCategory.LOW_VALUE_FORCED_OBSTACLE)
                        < onTrack.weight(CampaignPacingPolicy.MissionCategory.LOW_VALUE_FORCED_OBSTACLE));
        assertTrue(
                behind.weight(CampaignPacingPolicy.MissionCategory.FUEL_SUPPLY_GUARANTEE)
                        > onTrack.weight(CampaignPacingPolicy.MissionCategory.FUEL_SUPPLY_GUARANTEE));
    }

    @Test
    void leadingTeamsGainOptionalRiskWithoutLosingMainlineOpportunity() {
        int day = 30;
        int expected = CampaignPacingPolicy.expectedRouteSegment(day);

        CampaignPacingPolicy.MissionWeights onTrack =
                CampaignPacingPolicy.missionWeights(day, expected);
        CampaignPacingPolicy.MissionWeights ahead =
                CampaignPacingPolicy.missionWeights(day, expected + 6);

        assertTrue(
                ahead.weight(CampaignPacingPolicy.MissionCategory.OPTIONAL_HIGH_RISK_LOCATION)
                        > onTrack.weight(CampaignPacingPolicy.MissionCategory.OPTIONAL_HIGH_RISK_LOCATION));
        assertTrue(
                ahead.weight(CampaignPacingPolicy.MissionCategory.MAINLINE_PROGRESS_OPPORTUNITY)
                        > 0);
        assertTrue(CampaignPacingPolicy.mainlineProgressAvailable(day, expected + 6));
        assertFalse(CampaignPacingPolicy.requiresForcedWait(day, expected + 6));
    }
}
