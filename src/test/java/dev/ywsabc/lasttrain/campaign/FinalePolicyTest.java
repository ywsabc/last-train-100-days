package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalePolicyTest {
    @Test
    void finaleWaitsForAnExistingOrdinaryMission() {
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        false,
                        false,
                        true));
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        true,
                        false,
                        true));
    }

    @Test
    void anEmptyFinalDayStartsTheFinale() {
        assertEquals(
                FinalePolicy.Directive.CREATE_FINALE_MISSION,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        false,
                        false,
                        false));
    }

    @Test
    void bothIndependentGatesAreRequiredForCompletion() {
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        true,
                        false,
                        true));
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        false,
                        true,
                        false));
        assertEquals(
                FinalePolicy.Directive.COMPLETE_CAMPAIGN,
                FinalePolicy.nextDirective(
                        CampaignStatus.RUNNING,
                        FinalePolicy.FINAL_DAY,
                        true,
                        true,
                        false));
    }

    @Test
    void completedCampaignsNeverRequestMoreWork() {
        assertEquals(
                FinalePolicy.Directive.NONE,
                FinalePolicy.nextDirective(
                        CampaignStatus.COMPLETED,
                        FinalePolicy.FINAL_DAY,
                        true,
                        true,
                        false));
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
        assertEquals(
                FinalePolicy.Directive.CREATE_FINALE_MISSION,
                FinalePolicy.nextDirective(
                        migrated.status(),
                        FinalePolicy.FINAL_DAY,
                        migrated.finalDayElapsed(),
                        migrated.finaleMissionCompleted(),
                        false));
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
    }
}
