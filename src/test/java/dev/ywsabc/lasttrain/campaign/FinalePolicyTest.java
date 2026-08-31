package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalePolicyTest {
    @Test
    void completedCampaignsNeverRequestMoreWork() {
        assertFalse(FinalePolicy.shouldOpenArrival(
                CampaignMode.STORY_100_DAYS,
                CampaignStatus.COMPLETED,
                FinalePolicy.FINAL_DAY,
                FinalePhase.DORMANT));
        assertEquals(
                false,
                FinalePolicy.allowsOrdinaryMission(
                        CampaignStatus.COMPLETED,
                        FinalePolicy.FINAL_DAY - 1));
        assertEquals(
                false,
                FinalePolicy.allowsOrdinaryMission(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY));
        assertEquals(
                true,
                FinalePolicy.allowsOrdinaryMission(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY - 1));
    }

    @Test
    void finaleMissionIdIsStablePerCampaignAndDistinctBetweenCampaigns() {
        UUID firstCampaign = UUID.randomUUID();
        UUID secondCampaign = UUID.randomUUID();

        assertEquals(
                FinalePolicy.missionId(firstCampaign),
                FinalePolicy.missionId(firstCampaign));
        assertNotEquals(
                FinalePolicy.missionId(firstCampaign),
                FinalePolicy.missionId(secondCampaign));
    }

    @Test
    void schemaFiveCompletionReopensWithOnlyTheTimerGateSatisfied() {
        FinalePolicy.MigratedState migrated = FinalePolicy.migrate(
                5,
                CampaignStatus.COMPLETED,
                FinalePolicy.FINAL_DAY,
                false,
                false,
                false);

        assertEquals(CampaignStatus.RUNNING, migrated.status());
        assertEquals(true, migrated.finalDayElapsed());
        assertEquals(false, migrated.finaleMissionCompleted());
        assertEquals(FinalePhase.ARRIVAL, migrated.phase());
    }

    @Test
    void invalidNewCompletionIsReopenedInsteadOfTrustingCorruptFlags() {
        FinalePolicy.MigratedState migrated = FinalePolicy.migrate(
                FinalePolicy.CURRENT_SCHEMA,
                CampaignStatus.COMPLETED,
                FinalePolicy.FINAL_DAY,
                false,
                false,
                false);

        assertEquals(CampaignStatus.RUNNING, migrated.status());
        assertEquals(false, migrated.finalDayElapsed());
        assertEquals(false, migrated.finaleMissionCompleted());
        assertEquals(FinalePhase.ARRIVAL, migrated.phase());
    }

    @Test
    void aValidCompletedSaveRemainsCompleted() {
        FinalePolicy.MigratedState migrated = FinalePolicy.migrate(
                FinalePolicy.CURRENT_SCHEMA,
                CampaignStatus.COMPLETED,
                FinalePolicy.FINAL_DAY,
                true,
                true,
                false);

        assertEquals(CampaignStatus.COMPLETED, migrated.status());
        assertEquals(true, migrated.finalDayElapsed());
        assertEquals(true, migrated.finaleMissionCompleted());
        assertEquals(FinalePhase.COMPLETED, migrated.phase());
    }

    @Test
    void phasesExposeDistinctThreatAndMissionEffects() {
        FinalePolicy.PhaseEffect arrival = FinalePolicy.phaseEffect(FinalePhase.ARRIVAL);
        FinalePolicy.PhaseEffect restart = FinalePolicy.phaseEffect(FinalePhase.RESTART);
        FinalePolicy.PhaseEffect dawn = FinalePolicy.phaseEffect(FinalePhase.HOLD_DAWN);

        assertEquals(60, arrival.minimumThreat());
        assertEquals(null, arrival.missionType());
        assertEquals(75, restart.minimumThreat());
        assertEquals(MissionType.STATION_POWER, restart.missionType());
        assertEquals(3, restart.fixedTarget());
        assertEquals(90, dawn.minimumThreat());
        assertEquals(MissionType.ZOMBIE_BLOCKADE, dawn.missionType());
        assertTrue(FinalePolicy.dawnTarget(12) >= 24);
    }

    @Test
    void arrivalRequiresBothMaterializedHubAndTrainAtItsSegment() {
        assertFalse(FinalePolicy.arrivalComplete(
                FinalePhase.ARRIVAL, false, 20, 20));
        assertFalse(FinalePolicy.arrivalComplete(
                FinalePhase.ARRIVAL, true, 19, 20));
        assertTrue(FinalePolicy.arrivalComplete(
                FinalePhase.ARRIVAL, true, 20, 20));
    }

    @Test
    void hubWindowStartsBeyondTheAlreadyGeneratedFrontier() {
        FinalePolicy.HubWindow window = FinalePolicy.finaleHubWindow(10, 13);
        int chosen = FinalePolicy.chooseFinaleHubSegment(UUID.randomUUID(), 10, 13);

        assertEquals(14, window.firstSegment());
        assertTrue(chosen >= window.firstSegment());
        assertTrue(chosen <= window.lastSegment());
    }
}
