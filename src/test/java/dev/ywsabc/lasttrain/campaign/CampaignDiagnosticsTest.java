package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignDiagnosticsTest {
    @Test
    void snapshotFreezesStatusRouteMissionAndQueueStatisticsTogether() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start(UUID.fromString("00000000-0000-0000-0000-000000000061")));
        assertTrue(data.createMission(MissionType.RAIL_BREAK));

        CampaignDiagnostics snapshot = CampaignDiagnostics.snapshot(data, 4);
        CampaignDiagnostics.MissionView mission = snapshot.activeMission().orElseThrow();

        assertEquals(CampaignStatus.RUNNING, snapshot.status());
        assertEquals(CampaignMode.STORY_100_DAYS, snapshot.mode());
        assertEquals(4, snapshot.onlinePlayers());
        assertEquals(data.routeSegment() - data.expectedRouteSegment(), snapshot.routeDeltaFromExpected());
        assertEquals(data.generatedRouteSegment() - data.routeSegment(), snapshot.generatedLead());
        assertEquals(MissionType.RAIL_BREAK, mission.type());
        assertEquals(data.activeMission().id(), mission.id());
        assertEquals(0, snapshot.pendingRewards());
        assertFalse(snapshot.proposalPending());

        // 后续可变存档变化不会回写到已经采样的详细视图。
        data.addMissionProgress(data.activeMission().target());
        data.turnInMission();
        assertTrue(snapshot.activeMission().isPresent());
        assertEquals(0, snapshot.pendingRewards());
        assertEquals(1, CampaignDiagnostics.snapshot(data, 4).pendingRewards());
    }

    @Test
    void snapshotRejectsMissingDataInsteadOfProducingPartialOutput() {
        assertThrows(NullPointerException.class, () -> CampaignDiagnostics.snapshot(null, 1));
    }
}
