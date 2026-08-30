package dev.ywsabc.lasttrain.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.ywsabc.lasttrain.campaign.CampaignIntegrityPolicy;
import dev.ywsabc.lasttrain.campaign.CampaignMode;
import dev.ywsabc.lasttrain.campaign.CampaignStatus;
import dev.ywsabc.lasttrain.campaign.InfectionPolicy;
import dev.ywsabc.lasttrain.campaign.PursuitPolicy;
import dev.ywsabc.lasttrain.mission.MissionBriefing;
import dev.ywsabc.lasttrain.mission.MissionStage;
import dev.ywsabc.lasttrain.mission.MissionType;
import dev.ywsabc.lasttrain.route.RouteProgressPolicy;
import org.junit.jupiter.api.Test;

class TranslationKeysTest {
    @Test
    void everyDomainValueUsesOneStableNamingRule() {
        assertEquals(
                "campaign.lasttrain.mode.endless",
                TranslationKeys.campaignMode(CampaignMode.ENDLESS));
        assertEquals(
                "campaign.lasttrain.status.safe_mode",
                TranslationKeys.campaignStatus(CampaignStatus.SAFE_MODE));
        assertEquals(
                "mission.lasttrain.track_clearance",
                TranslationKeys.mission(MissionType.TRACK_CLEARANCE));
        assertEquals(
                "mission.lasttrain.stage.reward_pending",
                TranslationKeys.missionStage(MissionStage.REWARD_PENDING));
        assertEquals(
                "attention.lasttrain.out_of_control",
                TranslationKeys.attention(PursuitPolicy.AttentionLevel.OUT_OF_CONTROL));
        assertEquals(
                "infection.lasttrain.stage.spread",
                TranslationKeys.infectionStage(InfectionPolicy.Stage.SPREAD));
        assertEquals(
                "briefing.lasttrain.risk.high",
                TranslationKeys.briefingRisk(MissionBriefing.Risk.HIGH));
        assertEquals(
                "briefing.lasttrain.reward.upgrade",
                TranslationKeys.briefingReward(MissionBriefing.RewardCategory.UPGRADE));
        assertEquals(
                "pace.lasttrain.on_track",
                TranslationKeys.pace(RouteProgressPolicy.Pace.ON_TRACK));
        assertEquals(
                "integrity.lasttrain.non_contiguous_route_plan",
                TranslationKeys.integrity(
                        CampaignIntegrityPolicy.Code.NON_CONTIGUOUS_ROUTE_PLAN));
    }

    @Test
    void nullDomainValuesAreRejectedAtTheSingleBoundary() {
        assertThrows(NullPointerException.class, () -> TranslationKeys.campaignStatus(null));
        assertThrows(NullPointerException.class, () -> TranslationKeys.mission(null));
        assertThrows(NullPointerException.class, () -> TranslationKeys.missionStage(null));
    }
}
