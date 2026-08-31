package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import org.junit.jupiter.api.Test;

class CampaignSavedDataMissionFallbackTest {
    @Test
    void ordinaryMissionFailureClearsTheRoadblockAndAppliesThreat() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.TRACK_CLEARANCE));
        assertNotNull(data.activeMission());

        assertTrue(data.failMission(6));

        assertNull(data.activeMission());
        assertEquals(6, data.threat());
    }

    @Test
    void negativePenaltyIsIgnoredButStillClearsTheOrdinaryMission() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.RAIL_BREAK));

        assertTrue(data.failMission(-2));
        assertNull(data.activeMission());
        assertEquals(0, data.threat());
    }

    @Test
    void finaleMissionCannotBeFailedByFallback() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        data.advanceDays(CampaignSavedData.FINAL_DAY - 1);
        assertEquals(CampaignSavedData.FINAL_DAY, data.day());
        assertEquals(CampaignSavedData.TickOutcome.FINALE_PHASE_ADVANCED, data.tick());
        int hub = data.finaleHubRouteSegment();
        while (data.generatedRouteSegment() < hub) {
            assertTrue(data.markRouteSegmentGenerated(data.generatedRouteSegment() + 1));
        }
        assertTrue(data.markFinaleHubMaterialized(hub));
        assertTrue(data.advanceRouteTo(hub));

        assertNotNull(data.activeMission());
        assertTrue(data.isFinaleMission(data.activeMission()));
        int threatBefore = data.threat();

        assertFalse(data.failMission(6));
        assertNotNull(data.activeMission());
        assertEquals(threatBefore, data.threat());
    }
}
