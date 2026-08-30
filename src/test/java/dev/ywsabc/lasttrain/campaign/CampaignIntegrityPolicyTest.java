package dev.ywsabc.lasttrain.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ywsabc.lasttrain.mission.ActiveMission;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class CampaignIntegrityPolicyTest {
    @Test
    void ordinaryStartedCampaignIsHealthy() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());

        CampaignIntegrityPolicy.Report report = CampaignIntegrityPolicy.audit(data);

        assertTrue(report.healthy());
        assertEquals(0, report.errors());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void routeAheadOfVerifiedGenerationIsVisibleButNotDestructivelyRepaired() {
        CampaignSavedData data = new CampaignSavedData();
        assertTrue(data.start());
        assertTrue(data.advanceRoute(3));

        CampaignIntegrityPolicy.Report report = CampaignIntegrityPolicy.audit(data);

        assertTrue(report.healthy());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code() == CampaignIntegrityPolicy.Code.ROUTE_AHEAD_OF_GENERATION
                        && issue.severity() == CampaignIntegrityPolicy.Severity.WARNING));
        assertEquals(3, data.routeSegment(), "自检不得修改存档");
    }

    @Test
    void optionalMissionInTheMainlineSlotIsAnError() {
        CompoundTag tag = runningTag();
        ActiveMission invalid = ActiveMission.createProposal(
                MissionType.RESCUE_SURVIVOR, 1, 0, 0L);
        invalid.transitionTo(MissionStage.ACTIVE);
        tag.put("active_mission", invalid.save(null));

        CampaignIntegrityPolicy.Report report =
                CampaignIntegrityPolicy.audit(CampaignSavedData.load(tag, null));

        assertFalse(report.healthy());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code() == CampaignIntegrityPolicy.Code.OPTIONAL_IN_MAINLINE_SLOT));
    }

    @Test
    void malformedOptionalListAndMissingTrainIdentityAreReportedTogether() {
        CompoundTag tag = runningTag();
        ListTag optionals = new ListTag();
        optionals.add(ActiveMission.create(MissionType.RAIL_BREAK, 1, 0).save(null));
        tag.put("optional_missions", optionals);
        tag.putBoolean("starter_train_assembled", true);

        CampaignIntegrityPolicy.Report report =
                CampaignIntegrityPolicy.audit(CampaignSavedData.load(tag, null));

        assertEquals(2, report.errors());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code() == CampaignIntegrityPolicy.Code.INVALID_OPTIONAL_SLOT));
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.code() == CampaignIntegrityPolicy.Code.ASSEMBLED_TRAIN_WITHOUT_ID));
    }

    private static CompoundTag runningTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", CampaignSavedData.CURRENT_SCHEMA);
        tag.putString("campaign_id", UUID.randomUUID().toString());
        tag.putString("status", CampaignStatus.RUNNING.name());
        tag.putInt("day", 1);
        return tag;
    }
}
