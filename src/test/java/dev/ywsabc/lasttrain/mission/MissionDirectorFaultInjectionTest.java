package dev.ywsabc.lasttrain.mission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.campaign.CampaignSavedData;
import dev.ywsabc.lasttrain.testing.FaultInjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mission-world preparation under injected generation failures.
 *
 * <p>The pure {@link MissionWorldDirector#worldPreparationAllowed()} gate is
 * what both world directors consult before materializing a mission site.
 * While the injection is armed the gate stays closed, so the mission simply
 * remains unprepared and is retried on a later tick; the fallback deadline
 * keeps an indefinitely broken generator from blocking the campaign.</p>
 */
class MissionDirectorFaultInjectionTest {
    @AfterEach
    void clearInjections() {
        FaultInjection.clear();
    }

    @Test
    void missionWorldPreparationGateFailsWhileInjectedAndRecovers() {
        assertTrue(MissionWorldDirector.worldPreparationAllowed());

        FaultInjection.register(FaultInjection.FailurePoint.MISSION_WORLD_PREPARE, 2);
        assertFalse(MissionWorldDirector.worldPreparationAllowed());
        assertFalse(MissionWorldDirector.worldPreparationAllowed());

        // Injection exhausted: the retry on the next director tick succeeds.
        assertTrue(MissionWorldDirector.worldPreparationAllowed());
        FaultInjection.unregister(FaultInjection.FailurePoint.MISSION_WORLD_PREPARE);
        assertTrue(MissionWorldDirector.worldPreparationAllowed());
    }

    @Test
    void unpreparedMissionStillFallsBackAfterItsGracePeriod() {
        // A failed preparation leaves worldPrepared false and the mission
        // ACTIVE; the production degradation path is the fallback deadline,
        // which clears the roadblock with a threat penalty instead of
        // stranding the campaign behind a broken generator.
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.createMission(MissionType.RAIL_BREAK));
        ActiveMission mission = data.activeMission();
        assertFalse(mission.worldPrepared());
        assertFalse(MissionFallbackPolicy.shouldFallback(mission, data.day(), false));

        data.advanceDays(MissionFallbackPolicy.graceDays(MissionType.RAIL_BREAK));
        assertTrue(MissionFallbackPolicy.shouldFallback(data.activeMission(), data.day(), false));
        assertTrue(data.failMission(MissionFallbackPolicy.THREAT_PENALTY));
        assertNull(data.activeMission());
    }
}
